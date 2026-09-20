/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include "RaiseGesture.h"

#include <V2_1/SubHal.h>

#include <condition_variable>
#include <mutex>
#include <thread>
#include <unordered_set>

namespace android::hardware::sensors::V2_1::subhal::implementation::qsh_wrapper {

using ::android::hardware::sensors::V1_0::OperationMode;
using ::android::hardware::sensors::V1_0::RateLevel;
using ::android::hardware::sensors::V1_0::Result;
using ::android::hardware::sensors::V1_0::SharedMemInfo;
using ::android::hardware::sensors::V2_1::implementation::IHalProxyCallback;
using ::android::hardware::sensors::V2_1::implementation::ISensorsSubHal;

class SensorsSubHal : public ISensorsSubHal, public IHalProxyCallback {
  public:
    SensorsSubHal();
    ~SensorsSubHal() override;

    Return<Result> setOperationMode(OperationMode mode) override;
    Return<Result> activate(int32_t sensorHandle, bool enabled) override;
    Return<Result> batch(int32_t sensorHandle, int64_t samplingPeriodNs,
                         int64_t maxReportLatencyNs) override;
    Return<Result> flush(int32_t sensorHandle) override;
    Return<void> registerDirectChannel(const SharedMemInfo& mem,
                                       ISensors::registerDirectChannel_cb callback) override;
    Return<Result> unregisterDirectChannel(int32_t channelHandle) override;
    Return<void> configDirectReport(int32_t sensorHandle, int32_t channelHandle, RateLevel rate,
                                    ISensors::configDirectReport_cb callback) override;
    Return<void> getSensorsList_2_1(ISensors::getSensorsList_2_1_cb callback) override;
    Return<Result> injectSensorData_2_1(const Event& event) override;
    Return<void> debug(const hidl_handle& fd, const hidl_vec<hidl_string>& args) override;
    const std::string getName() override;
    Return<Result> initialize(const sp<IHalProxyCallback>& callback) override;

    Return<void> onDynamicSensorsConnected(const hidl_vec<V1_0::SensorInfo>& sensors) override;
    Return<void> onDynamicSensorsConnected_2_1(const hidl_vec<SensorInfo>& sensors) override;
    Return<void> onDynamicSensorsDisconnected(const hidl_vec<int32_t>& handles) override;
    void postEvents(const std::vector<Event>& events, ScopedWakelock wakelock) override;
    ScopedWakelock createScopedWakelock(bool lock) override;

  private:
    bool isPickupHandle(int32_t handle) const {
        return mPickupHandle >= 0 && handle == mPickupHandle;
    }
    sp<IHalProxyCallback> getCallback();
    void run();

    ISensorsSubHal* mImpl;
    hidl_vec<SensorInfo> mSensors;
    int32_t mPickupHandle = -1;
    SensorType mRaiseType = SensorType::META_DATA;
    std::mutex mOperationMutex;
    std::mutex mStateMutex;
    std::condition_variable mCondition;
    sp<IHalProxyCallback> mCallback;
    std::unordered_set<int32_t> mWakeupHandles;
    RaiseGesture mRaiseGesture;
    bool mArmed = false;
    bool mDisablePending = false;
    bool mStop = false;
    std::thread mWorker;
};

}  // namespace android::hardware::sensors::V2_1::subhal::implementation::qsh_wrapper
