/*
 * SPDX-FileCopyrightText: 2019 The Android Open Source Project
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "SensorsSubHal.h"

#include <android/hardware/sensors/2.1/types.h>
#include <linux/input-event-codes.h>
#include <log/log.h>
#include <unistd.h>

#include <cstdio>

using ::android::hardware::sensors::V2_1::implementation::ISensorsSubHal;
using ::android::hardware::sensors::V2_1::subhal::implementation::SensorsSubHal;

namespace android::hardware::sensors::V2_1::subhal::implementation {

using ::android::hardware::Void;
using ::android::hardware::sensors::V2_0::implementation::ScopedWakelock;

namespace {

constexpr char kDoubleTapPath[] = "/sys/devices/platform/main_touch.0/gesture/double_en";

}  // namespace

SensorsSubHal::SensorsSubHal() {
    mSensors.emplace(1, std::make_unique<TapSensor>(1, this, "Meizu Double Tap Sensor",
                                                    "org.lineageos.sensor.double_tap",
                                                    kDoubleTapPath, KEY_NEXT_FAVORITE));
}

Return<void> SensorsSubHal::getSensorsList_2_1(ISensors::getSensorsList_2_1_cb callback) {
    std::vector<SensorInfo> sensors;
    for (const auto& entry : mSensors) {
        const auto& sensor = entry.second;
        if (sensor->opened()) {
            sensors.push_back(sensor->getSensorInfo());
        }
    }
    callback(sensors);
    return Void();
}

Return<Result> SensorsSubHal::injectSensorData_2_1(const Event& event) {
    auto sensor = mSensors.find(event.sensorHandle);
    if (sensor == mSensors.end()) {
        return Result::BAD_VALUE;
    }
    return sensor->second->injectEvent(event);
}

Return<Result> SensorsSubHal::initialize(const sp<IHalProxyCallback>& callback) {
    mCallback = callback;
    for (const auto& entry : mSensors) {
        const auto& sensor = entry.second;
        sensor->setOperationMode(OperationMode::NORMAL);
    }
    return Result::OK;
}

Return<Result> SensorsSubHal::setOperationMode(OperationMode mode) {
    for (const auto& entry : mSensors) {
        const auto& sensor = entry.second;
        sensor->setOperationMode(mode);
    }
    return Result::OK;
}

Return<Result> SensorsSubHal::activate(int32_t sensorHandle, bool enabled) {
    auto sensor = mSensors.find(sensorHandle);
    if (sensor == mSensors.end()) {
        return Result::BAD_VALUE;
    }
    return sensor->second->activate(enabled);
}

Return<Result> SensorsSubHal::batch(int32_t sensorHandle, int64_t samplingPeriodNs,
                                    int64_t /* maxReportLatencyNs */) {
    auto sensor = mSensors.find(sensorHandle);
    if (sensor == mSensors.end()) {
        return Result::BAD_VALUE;
    }
    return sensor->second->batch(samplingPeriodNs);
}

Return<Result> SensorsSubHal::flush(int32_t sensorHandle) {
    auto sensor = mSensors.find(sensorHandle);
    if (sensor == mSensors.end()) {
        return Result::BAD_VALUE;
    }
    return sensor->second->flush();
}

Return<void> SensorsSubHal::registerDirectChannel(const SharedMemInfo& /* mem */,
                                                  ISensors::registerDirectChannel_cb callback) {
    callback(Result::INVALID_OPERATION, -1);
    return Void();
}

Return<Result> SensorsSubHal::unregisterDirectChannel(int32_t /* channelHandle */) {
    return Result::INVALID_OPERATION;
}

Return<void> SensorsSubHal::configDirectReport(int32_t /* sensorHandle */,
                                               int32_t /* channelHandle */, RateLevel /* rate */,
                                               ISensors::configDirectReport_cb callback) {
    callback(Result::INVALID_OPERATION, 0);
    return Void();
}

Return<void> SensorsSubHal::debug(const hidl_handle& fd, const hidl_vec<hidl_string>& args) {
    if (fd.getNativeHandle() == nullptr || fd->numFds < 1) {
        ALOGE("Missing debug fd");
        return Void();
    }

    FILE* output = fdopen(dup(fd->data[0]), "w");
    if (output == nullptr) {
        return Void();
    }
    if (args.size() != 0) {
        fprintf(output, "Arguments are not supported.\n");
    }

    for (const auto& entry : mSensors) {
        const auto& sensor = entry.second;
        const SensorInfo& info = sensor->getSensorInfo();
        fprintf(output, "%s: %s\n", info.name.c_str(),
                sensor->opened() ? "available" : "unavailable");
    }
    fclose(output);
    return Void();
}

const std::string SensorsSubHal::getName() {
    return "MeizuSensorsSubHal";
}

void SensorsSubHal::postEvents(const std::vector<Event>& events, bool wakeup) {
    if (mCallback == nullptr) {
        return;
    }
    ScopedWakelock wakelock = mCallback->createScopedWakelock(wakeup);
    mCallback->postEvents(events, std::move(wakelock));
}

}  // namespace android::hardware::sensors::V2_1::subhal::implementation

ISensorsSubHal* sensorsHalGetSubHal_2_1(uint32_t* version) {
    static SensorsSubHal subHal;
    *version = SUB_HAL_2_1_VERSION;
    return &subHal;
}
