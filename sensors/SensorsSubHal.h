/*
 * SPDX-FileCopyrightText: 2019 The Android Open Source Project
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include "Sensor.h"
#include "V2_1/SubHal.h"

#include <map>
#include <memory>

namespace android::hardware::sensors::V2_1::subhal::implementation {

using ::android::hardware::sensors::V1_0::RateLevel;
using ::android::hardware::sensors::V1_0::SharedMemInfo;
using ::android::hardware::sensors::V2_1::implementation::IHalProxyCallback;
using ::android::hardware::sensors::V2_1::implementation::ISensorsSubHal;

class SensorsSubHal : public ISensorsSubHal, public ISensorsEventCallback {
  public:
    SensorsSubHal();

    Return<void> getSensorsList_2_1(ISensors::getSensorsList_2_1_cb callback) override;
    Return<Result> injectSensorData_2_1(const Event& event) override;
    Return<Result> initialize(const sp<IHalProxyCallback>& callback) override;
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
    Return<void> debug(const hidl_handle& fd, const hidl_vec<hidl_string>& args) override;

    const std::string getName() override;
    void postEvents(const std::vector<Event>& events, bool wakeup) override;

  private:
    sp<IHalProxyCallback> mCallback;
    std::map<int32_t, std::unique_ptr<TapSensor>> mSensors;
};

}  // namespace android::hardware::sensors::V2_1::subhal::implementation
