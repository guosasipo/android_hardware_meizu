/*
 * SPDX-FileCopyrightText: 2021 The LineageOS Project
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <aidl/android/hardware/light/BnLights.h>
#include <aidl/android/hardware/light/HwLightState.h>

#include <cstdint>
#include <mutex>
#include <vector>

namespace aidl::android::hardware::light {

struct Color {
    uint8_t red;
    uint8_t green;
    uint8_t blue;

    bool isLit() const;
    uint8_t brightness() const;
};

class Lights : public BnLights {
  public:
    Lights();

    bool isReady() const;

    ndk::ScopedAStatus getLights(std::vector<HwLight>* _aidl_return) override;
    ndk::ScopedAStatus setLightState(int32_t id, const HwLightState& state) override;

    binder_status_t dump(int fd, const char** args, uint32_t numArgs) override;

  private:
    bool initialize();
    bool setAwState(const HwLightState& state);
    bool setBacklightState(const HwLightState& state);
    bool setPmicState(const HwLightState& state);
    bool updateNotificationState();

    static Color colorFromState(const HwLightState& state);
    static bool stateIsLit(const HwLightState& state);

    bool mReady = false;
    bool mBacklightAvailable = false;
    bool mAwAvailable = false;
    bool mPmicAvailable = false;
    int mAwEffect = -1;
    HwLightState mBatteryState;
    HwLightState mNotificationsState;
    HwLightState mAttentionState;
    HwLightState mMicrophoneState;
    HwLightState mCameraState;
    std::vector<HwLight> mLights;
    std::mutex mMutex;
};

}  // namespace aidl::android::hardware::light
