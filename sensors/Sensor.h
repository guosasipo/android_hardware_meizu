/*
 * SPDX-FileCopyrightText: 2019 The Android Open Source Project
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <android/hardware/sensors/2.1/types.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

using ::android::hardware::sensors::V1_0::OperationMode;
using ::android::hardware::sensors::V1_0::Result;
using ::android::hardware::sensors::V2_1::Event;
using ::android::hardware::sensors::V2_1::SensorInfo;

namespace android::hardware::sensors::V2_1::subhal::implementation {

class ISensorsEventCallback {
  public:
    virtual ~ISensorsEventCallback() = default;
    virtual void postEvents(const std::vector<Event>& events, bool wakeup) = 0;
};

class TapSensor {
  public:
    TapSensor(int32_t sensorHandle, ISensorsEventCallback* callback, const char* name,
              const char* type, const char* gesturePath, uint16_t eventCode);
    ~TapSensor();

    bool opened() const;
    const SensorInfo& getSensorInfo() const;
    Result activate(bool enable);
    Result batch(int64_t samplingPeriodNs);
    Result flush();
    Result injectEvent(const Event& event);
    void setOperationMode(OperationMode mode);

  private:
    bool updateGestureEnabled(bool enable);
    int getInputFd() const;
    void resetFodTap();
    void resetFodTapLocked();
    bool reopenInputDevice();
    bool readInputEvents();
    void run();
    void wakeThread();

    ISensorsEventCallback* const mCallback;
    const std::string mGesturePath;
    const uint16_t mEventCode;
    SensorInfo mSensorInfo;

    mutable std::mutex mInputMutex;
    int mInputFd = -1;
    int mWakePipe[2] = {-1, -1};
    std::atomic_bool mStopThread = false;
    std::condition_variable mStateCondition;
    std::mutex mStateMutex;
    std::thread mThread;
    bool mEnabled = false;
    bool mFodPressed = false;
    int64_t mFodDownTimeUs = 0;
    int64_t mFodTapTimeUs = 0;
    bool mInputSyncDropped = false;
    OperationMode mMode = OperationMode::NORMAL;
};

}  // namespace android::hardware::sensors::V2_1::subhal::implementation
