/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "SensorsSubHal.h"

#include <android-base/logging.h>
#include <dlfcn.h>

#include <algorithm>

using ::android::hardware::sensors::V1_0::SensorFlagBits;
using ::android::hardware::sensors::V2_0::implementation::ScopedWakelock;
using ::android::hardware::sensors::V2_1::implementation::ISensorsSubHal;

namespace android::hardware::sensors::V2_1::subhal::implementation::qsh_wrapper {

namespace {
constexpr char kLibrary[] = "sensors.qsh.so";
constexpr char kRaiseSensorType[] = "com.meizu.sensor.raise";
constexpr int64_t kRaiseSamplingPeriodNs = 200'000'000;
}  // namespace

SensorsSubHal::SensorsSubHal() {
    // QSH owns a static sub-HAL and worker threads; keep its code loaded for the process lifetime.
    void* library = dlopen(kLibrary, RTLD_NOW | RTLD_LOCAL);
    CHECK(library != nullptr) << "Cannot load " << kLibrary << ": " << dlerror();
    auto getSubHal = reinterpret_cast<ISensorsSubHal* (*)(uint32_t*)>(
            dlsym(library, "sensorsHalGetSubHal_2_1"));
    CHECK(getSubHal != nullptr) << "Missing QSH Multi-HAL 2.1 factory";
    uint32_t version = 0;
    mImpl = getSubHal(&version);
    CHECK(mImpl != nullptr && version == SUB_HAL_2_1_VERSION) << "Invalid QSH Multi-HAL ABI";

    const auto result = mImpl->getSensorsList_2_1([this](const auto& sensors) {
        mSensors = sensors;
        const bool hasPickup = std::any_of(sensors.begin(), sensors.end(), [](const auto& info) {
            return info.type == SensorType::PICK_UP_GESTURE;
        });
        for (auto& info : mSensors) {
            if (info.flags & SensorFlagBits::WAKE_UP) mWakeupHandles.insert(info.sensorHandle);
            if (hasPickup || mPickupHandle >= 0 || info.typeAsString != kRaiseSensorType ||
                !(info.flags & SensorFlagBits::WAKE_UP)) {
                continue;
            }
            mPickupHandle = info.sensorHandle;
            mRaiseType = info.type;
            info.name = "Meizu Pick Up Gesture";
            info.type = SensorType::PICK_UP_GESTURE;
            info.typeAsString = "android.sensor.pick_up_gesture";
            info.flags = SensorFlagBits::WAKE_UP | SensorFlagBits::ONE_SHOT_MODE;
            info.maxRange = 1.0f;
            info.resolution = 1.0f;
            info.minDelay = -1;
            info.maxDelay = 0;
            info.fifoReservedEventCount = 0;
            info.fifoMaxEventCount = 0;
            info.requiredPermission = "";
        }
        if (!hasPickup && mPickupHandle < 0) LOG(WARNING) << "No Meizu raise sensor found";
    });
    CHECK(result.isOk()) << "Cannot read QSH sensor list";
    if (mPickupHandle >= 0) mWorker = std::thread(&SensorsSubHal::run, this);
}

SensorsSubHal::~SensorsSubHal() {
    {
        std::lock_guard lock(mStateMutex);
        mStop = true;
    }
    mCondition.notify_one();
    if (mWorker.joinable()) mWorker.join();
}

Return<void> SensorsSubHal::getSensorsList_2_1(ISensors::getSensorsList_2_1_cb callback) {
    callback(mSensors);
    return Void();
}

Return<Result> SensorsSubHal::initialize(const sp<IHalProxyCallback>& callback) {
    if (callback == nullptr) return Result::BAD_VALUE;
    std::lock_guard operationLock(mOperationMutex);
    {
        std::lock_guard lock(mStateMutex);
        mCallback = callback;
        mArmed = false;
        mDisablePending = false;
        mRaiseGesture.reset();
        mWakeupHandles.clear();
        for (const auto& info : mSensors) {
            if (info.flags & SensorFlagBits::WAKE_UP) mWakeupHandles.insert(info.sensorHandle);
        }
    }
    return mImpl->initialize(this);
}

Return<Result> SensorsSubHal::setOperationMode(OperationMode mode) {
    std::lock_guard operationLock(mOperationMutex);
    return mImpl->setOperationMode(mode);
}

Return<Result> SensorsSubHal::activate(int32_t sensorHandle, bool enabled) {
    if (!isPickupHandle(sensorHandle)) return mImpl->activate(sensorHandle, enabled);
    std::lock_guard operationLock(mOperationMutex);
    bool needsStop;
    {
        std::lock_guard lock(mStateMutex);
        if (enabled && mArmed) return Result::OK;
        needsStop = mDisablePending;
        mDisablePending = false;
        mArmed = false;
        mRaiseGesture.reset();
    }
    if (needsStop && enabled) {
        auto result = mImpl->activate(sensorHandle, false);
        if (!result.isOk() || result != Result::OK) return result;
    }
    if (enabled) {
        auto result = mImpl->batch(sensorHandle, kRaiseSamplingPeriodNs, 0);
        if (!result.isOk() || result != Result::OK) return result;
        std::lock_guard lock(mStateMutex);
        mArmed = true;
    }
    auto result = mImpl->activate(sensorHandle, enabled);
    if (!result.isOk() || result != Result::OK) {
        std::lock_guard lock(mStateMutex);
        mArmed = false;
    }
    return result;
}

Return<Result> SensorsSubHal::batch(int32_t sensorHandle, int64_t samplingPeriodNs,
                                    int64_t maxReportLatencyNs) {
    if (!isPickupHandle(sensorHandle)) {
        return mImpl->batch(sensorHandle, samplingPeriodNs, maxReportLatencyNs);
    }
    if (samplingPeriodNs < 0 || maxReportLatencyNs < 0) return Result::BAD_VALUE;
    std::lock_guard operationLock(mOperationMutex);
    return mImpl->batch(sensorHandle, kRaiseSamplingPeriodNs, 0);
}

Return<Result> SensorsSubHal::flush(int32_t sensorHandle) {
    if (isPickupHandle(sensorHandle)) return Result::BAD_VALUE;
    return mImpl->flush(sensorHandle);
}

Return<Result> SensorsSubHal::injectSensorData_2_1(const Event& event) {
    if (isPickupHandle(event.sensorHandle)) return Result::INVALID_OPERATION;
    return mImpl->injectSensorData_2_1(event);
}

Return<void> SensorsSubHal::registerDirectChannel(const SharedMemInfo& mem,
                                                  ISensors::registerDirectChannel_cb callback) {
    return mImpl->registerDirectChannel(mem, callback);
}

Return<Result> SensorsSubHal::unregisterDirectChannel(int32_t channelHandle) {
    return mImpl->unregisterDirectChannel(channelHandle);
}

Return<void> SensorsSubHal::configDirectReport(int32_t sensorHandle, int32_t channelHandle,
                                               RateLevel rate,
                                               ISensors::configDirectReport_cb callback) {
    if (isPickupHandle(sensorHandle)) {
        callback(Result::INVALID_OPERATION, 0);
        return Void();
    }
    return mImpl->configDirectReport(sensorHandle, channelHandle, rate, callback);
}

Return<void> SensorsSubHal::debug(const hidl_handle& fd, const hidl_vec<hidl_string>& args) {
    return mImpl->debug(fd, args);
}

const std::string SensorsSubHal::getName() {
    return mImpl->getName();
}

sp<IHalProxyCallback> SensorsSubHal::getCallback() {
    std::lock_guard lock(mStateMutex);
    return mCallback;
}

Return<void> SensorsSubHal::onDynamicSensorsConnected(const hidl_vec<V1_0::SensorInfo>& sensors) {
    {
        std::lock_guard lock(mStateMutex);
        for (const auto& info : sensors) {
            if (info.flags & SensorFlagBits::WAKE_UP) {
                mWakeupHandles.insert(info.sensorHandle);
            } else {
                mWakeupHandles.erase(info.sensorHandle);
            }
        }
    }
    return getCallback()->onDynamicSensorsConnected(sensors);
}

Return<void> SensorsSubHal::onDynamicSensorsConnected_2_1(const hidl_vec<SensorInfo>& sensors) {
    {
        std::lock_guard lock(mStateMutex);
        for (const auto& info : sensors) {
            if (info.flags & SensorFlagBits::WAKE_UP) {
                mWakeupHandles.insert(info.sensorHandle);
            } else {
                mWakeupHandles.erase(info.sensorHandle);
            }
        }
    }
    return getCallback()->onDynamicSensorsConnected_2_1(sensors);
}

Return<void> SensorsSubHal::onDynamicSensorsDisconnected(const hidl_vec<int32_t>& handles) {
    {
        std::lock_guard lock(mStateMutex);
        for (int32_t handle : handles) mWakeupHandles.erase(handle);
    }
    return getCallback()->onDynamicSensorsDisconnected(handles);
}

void SensorsSubHal::postEvents(const std::vector<Event>& events, ScopedWakelock wakelock) {
    const auto callback = getCallback();
    if (events.empty() || callback == nullptr) return;
    if (mPickupHandle < 0 ||
        std::none_of(
                events.begin(), events.end(),
                [this](const auto& event) { return event.sensorHandle == mPickupHandle; })) {
        callback->postEvents(events, std::move(wakelock));
        return;
    }

    std::vector<Event> output;
    output.reserve(events.size());
    bool wakeup = false;
    bool disable = false;
    {
        std::lock_guard lock(mStateMutex);
        for (const auto& event : events) {
            if (event.sensorHandle == mPickupHandle) {
                if (!mArmed || event.sensorType != mRaiseType ||
                    !mRaiseGesture.update(event.u.scalar)) {
                    continue;
                }
                mArmed = false;
                mDisablePending = true;
                disable = true;
                Event pickup = event;
                pickup.sensorType = SensorType::PICK_UP_GESTURE;
                pickup.u.scalar = 1.0f;
                output.push_back(pickup);
            } else {
                output.push_back(event);
            }
            wakeup |= mWakeupHandles.count(event.sensorHandle) != 0;
        }
    }
    if (disable) mCondition.notify_one();
    if (!output.empty()) {
        auto outputWakelock = callback->createScopedWakelock(wakeup);
        callback->postEvents(output, std::move(outputWakelock));
    }
}

ScopedWakelock SensorsSubHal::createScopedWakelock(bool lock) {
    return getCallback()->createScopedWakelock(lock);
}

void SensorsSubHal::run() {
    for (;;) {
        {
            std::unique_lock lock(mStateMutex);
            mCondition.wait(lock, [this] { return mStop || mDisablePending; });
            if (mStop) return;
        }
        std::lock_guard operationLock(mOperationMutex);
        {
            std::lock_guard lock(mStateMutex);
            if (!mDisablePending) continue;
            mDisablePending = false;
        }
        // QSH can hold internal locks while posting events; do not deactivate from its callback.
        const auto result = mImpl->activate(mPickupHandle, false);
        if (!result.isOk() || result != Result::OK) LOG(ERROR) << "Cannot deactivate pickup sensor";
    }
}

}  // namespace android::hardware::sensors::V2_1::subhal::implementation::qsh_wrapper

ISensorsSubHal* sensorsHalGetSubHal_2_1(uint32_t* version) {
    static ::android::hardware::sensors::V2_1::subhal::implementation::qsh_wrapper::SensorsSubHal
            subHal;
    *version = SUB_HAL_2_1_VERSION;
    return &subHal;
}
