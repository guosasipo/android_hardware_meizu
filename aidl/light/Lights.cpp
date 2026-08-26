/*
 * SPDX-FileCopyrightText: 2021 The LineageOS Project
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "android.hardware.light-service.meizu"

#include "Lights.h"

#include <aidl/android/hardware/light/FlashMode.h>
#include <aidl/android/hardware/light/LightType.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>

#include <unistd.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <limits>
#include <string>
#include <thread>

namespace aidl::android::hardware::light {
namespace {

using namespace std::chrono_literals;

constexpr char kBacklightPath[] = "/sys/class/backlight/panel0-backlight/brightness";
constexpr char kBacklightMaxPath[] = "/sys/class/backlight/panel0-backlight/max_brightness";
constexpr char kAwEffectPath[] = "/sys/class/leds/aw20072_led/effect";
constexpr char kAwAllLightPath[] = "/sys/class/leds/aw20072_led/all_light";
constexpr int kInitRetries = 200;
constexpr useconds_t kInitDelayUs = 10000;

struct AwColor {
    int effect;
    int red;
    int green;
    int blue;
};

constexpr std::array kBreathColors = {
        AwColor{6, 255, 255, 255}, AwColor{7, 255, 0, 0},      AwColor{8, 0, 255, 0},
        AwColor{9, 0, 0, 255},     AwColor{10, 255, 128, 0},   AwColor{11, 255, 255, 0},
        AwColor{12, 255, 64, 128}, AwColor{13, 128, 255, 128}, AwColor{14, 0, 255, 255},
        AwColor{15, 128, 0, 255},
};

bool readInt(const std::string& path, int* value) {
    std::string content;
    return ::android::base::ReadFileToString(path, &content, true) &&
           ::android::base::ParseInt(::android::base::Trim(content), value);
}

bool writeString(const std::string& path, const std::string& value) {
    if (!::android::base::WriteStringToFile(value, path, true)) {
        PLOG(ERROR) << "Failed to write " << path;
        return false;
    }
    return true;
}

bool writeInt(const std::string& path, int value) {
    return writeString(path, std::to_string(value));
}

bool pathExists(const std::string& path) {
    return access(path.c_str(), F_OK) == 0;
}

std::string ledPath(const std::string& name, const std::string& node) {
    return "/sys/class/leds/" + name + "/" + node;
}

int nearestEffect(const Color& color) {
    const int maximum = std::max({color.red, color.green, color.blue});
    if (maximum == 0) {
        return 0;
    }

    const int red = color.red * 255 / maximum;
    const int green = color.green * 255 / maximum;
    const int blue = color.blue * 255 / maximum;
    int bestEffect = 0;
    int bestDistance = std::numeric_limits<int>::max();

    const auto compare = [&](const AwColor& candidate) {
        const int deltaRed = red - candidate.red;
        const int deltaGreen = green - candidate.green;
        const int deltaBlue = blue - candidate.blue;
        const int distance = deltaRed * deltaRed + deltaGreen * deltaGreen + deltaBlue * deltaBlue;
        if (distance < bestDistance) {
            bestDistance = distance;
            bestEffect = candidate.effect;
        }
    };

    for (const auto& candidate : kBreathColors) {
        compare(candidate);
    }
    return bestEffect;
}

bool setPmicChannel(const std::string& name, uint8_t brightness, const HwLightState& state) {
    const std::string brightnessPath = ledPath(name, "brightness");
    const std::string breathPath = ledPath(name, "breath");
    const std::string triggerPath = ledPath(name, "trigger");
    if (!pathExists(brightnessPath)) {
        return false;
    }

    bool success = true;
    if (pathExists(triggerPath) && !writeString(triggerPath, "none")) {
        success = false;
    }
    if (pathExists(breathPath) && !writeInt(breathPath, 0)) {
        success = false;
    }
    if (!writeInt(brightnessPath, brightness)) {
        success = false;
    }
    if (!success || brightness == 0) {
        return success;
    }

    if (state.flashMode == FlashMode::HARDWARE && pathExists(breathPath)) {
        return writeInt(breathPath, 1);
    }
    if (state.flashMode != FlashMode::TIMED || !pathExists(triggerPath)) {
        return true;
    }

    if (!writeString(triggerPath, "timer")) {
        return false;
    }
    const std::string delayOnPath = ledPath(name, "delay_on");
    const std::string delayOffPath = ledPath(name, "delay_off");
    for (int retry = 0; retry < 20; ++retry) {
        if (writeInt(delayOnPath, std::max(1, state.flashOnMs)) &&
            writeInt(delayOffPath, std::max(1, state.flashOffMs))) {
            return true;
        }
        std::this_thread::sleep_for(2ms);
    }
    return false;
}

}  // namespace

bool Color::isLit() const {
    return red != 0 || green != 0 || blue != 0;
}

uint8_t Color::brightness() const {
    return (77 * red + 150 * green + 29 * blue) >> 8;
}

Lights::Lights() {
    mReady = initialize();

    const auto addLight = [this](LightType type) {
        mLights.push_back({
                .id = static_cast<int32_t>(type),
                .ordinal = 0,
                .type = type,
        });
    };
    addLight(LightType::BACKLIGHT);
    if (mAwAvailable || mPmicAvailable) {
        addLight(LightType::BATTERY);
        addLight(LightType::NOTIFICATIONS);
        addLight(LightType::ATTENTION);
        addLight(LightType::MICROPHONE);
        addLight(LightType::CAMERA);
    }
}

bool Lights::isReady() const {
    return mReady;
}

bool Lights::initialize() {
    for (int retry = 0; retry < kInitRetries; ++retry) {
        mBacklightAvailable =
                access(kBacklightPath, W_OK) == 0 && access(kBacklightMaxPath, R_OK) == 0;
        mAwAvailable = access(kAwEffectPath, W_OK) == 0 && access(kAwAllLightPath, W_OK) == 0;
        mPmicAvailable = access("/sys/class/leds/red/brightness", W_OK) == 0 &&
                         access("/sys/class/leds/green/brightness", W_OK) == 0 &&
                         access("/sys/class/leds/blue/brightness", W_OK) == 0;
        if (mBacklightAvailable && mAwAvailable && mPmicAvailable) {
            return true;
        }
        usleep(kInitDelayUs);
    }

    if (!mBacklightAvailable) {
        LOG(ERROR) << "Backlight nodes are unavailable";
        return false;
    }
    LOG(WARNING) << "Notification light nodes are unavailable";
    return true;
}

Color Lights::colorFromState(const HwLightState& state) {
    const uint32_t value = static_cast<uint32_t>(state.color);
    const uint8_t alpha = (value >> 24) & 0xff;
    Color color{
            .red = static_cast<uint8_t>((value >> 16) & 0xff),
            .green = static_cast<uint8_t>((value >> 8) & 0xff),
            .blue = static_cast<uint8_t>(value & 0xff),
    };
    if (alpha > 0 && alpha < 0xff) {
        color.red = color.red * alpha / 0xff;
        color.green = color.green * alpha / 0xff;
        color.blue = color.blue * alpha / 0xff;
    }
    return color;
}

bool Lights::stateIsLit(const HwLightState& state) {
    return colorFromState(state).isLit();
}

bool Lights::setBacklightState(const HwLightState& state) {
    if (!mBacklightAvailable) {
        return false;
    }

    int maximum;
    if (!readInt(kBacklightMaxPath, &maximum) || maximum <= 0) {
        return false;
    }
    const int brightness = colorFromState(state).brightness() * maximum / 0xff;
    return writeInt(kBacklightPath, brightness);
}

bool Lights::setAwState(const HwLightState& state) {
    if (!mAwAvailable) {
        return false;
    }
    const Color color = colorFromState(state);
    const int maximum = std::max({color.red, color.green, color.blue});

    if (!color.isLit() || state.flashMode == FlashMode::NONE) {
        int brightness = 0;
        int rgb = 0;
        if (maximum > 0) {
            brightness = std::max(1, (maximum * 63 + 254) / 255);
            const int red = color.red * 255 / maximum;
            const int green = color.green * 255 / maximum;
            const int blue = color.blue * 255 / maximum;
            rgb = (red << 16) | (green << 8) | blue;
        }
        char command[32];
        snprintf(command, sizeof(command), "%d %06x", brightness, rgb);
        const bool success = writeString(kAwAllLightPath, command);
        mAwEffect = -1;
        return success;
    }

    const int effect = nearestEffect(color);
    if (effect == mAwEffect) {
        return true;
    }
    if (!writeInt(kAwEffectPath, effect)) {
        return false;
    }
    mAwEffect = effect;
    return true;
}

bool Lights::setPmicState(const HwLightState& state) {
    if (!mPmicAvailable) {
        return false;
    }

    const Color color = colorFromState(state);
    const bool red = setPmicChannel("red", color.red, state);
    const bool green = setPmicChannel("green", color.green, state);
    const bool blue = setPmicChannel("blue", color.blue, state);
    return red && green && blue;
}

bool Lights::updateNotificationState() {
    const HwLightState* state = stateIsLit(mCameraState)          ? &mCameraState
                                : stateIsLit(mMicrophoneState)    ? &mMicrophoneState
                                : stateIsLit(mNotificationsState) ? &mNotificationsState
                                : stateIsLit(mAttentionState)     ? &mAttentionState
                                : stateIsLit(mBatteryState)       ? &mBatteryState
                                                                  : nullptr;
    const HwLightState off;
    if (state == nullptr) {
        state = &off;
    }
    bool attempted = false;
    bool success = true;
    if (mPmicAvailable) {
        attempted = true;
        if (!setPmicState(*state)) {
            LOG(ERROR) << "Failed to update PMIC notification LEDs";
            success = false;
        }
    }
    if (mAwAvailable) {
        attempted = true;
        if (!setAwState(*state)) {
            LOG(ERROR) << "Failed to update AW20072 notification ring";
            success = false;
        }
    }
    return attempted && success;
}

ndk::ScopedAStatus Lights::setLightState(int32_t id, const HwLightState& state) {
    std::lock_guard lock(mMutex);
    if (!mReady) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    bool success = false;
    switch (static_cast<LightType>(id)) {
        case LightType::BACKLIGHT:
            success = setBacklightState(state);
            break;
        case LightType::BATTERY:
            mBatteryState = state;
            success = updateNotificationState();
            break;
        case LightType::NOTIFICATIONS:
            mNotificationsState = state;
            success = updateNotificationState();
            break;
        case LightType::ATTENTION:
            mAttentionState = state;
            success = updateNotificationState();
            break;
        case LightType::MICROPHONE:
            mMicrophoneState = state;
            success = updateNotificationState();
            break;
        case LightType::CAMERA:
            mCameraState = state;
            success = updateNotificationState();
            break;
        default:
            return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }
    return success ? ndk::ScopedAStatus::ok() : ndk::ScopedAStatus::fromServiceSpecificError(EIO);
}

ndk::ScopedAStatus Lights::getLights(std::vector<HwLight>* _aidl_return) {
    std::lock_guard lock(mMutex);
    *_aidl_return = mLights;
    return ndk::ScopedAStatus::ok();
}

binder_status_t Lights::dump(int fd, const char** /* args */, uint32_t /* numArgs */) {
    std::lock_guard lock(mMutex);
    dprintf(fd, "Meizu Lights ready: %s\n", mReady ? "true" : "false");
    dprintf(fd, "Backlight available: %s\n", mBacklightAvailable ? "true" : "false");
    dprintf(fd, "AW20072 available: %s\n", mAwAvailable ? "true" : "false");
    dprintf(fd, "PMIC LEDs available: %s\n", mPmicAvailable ? "true" : "false");
    for (const auto& light : mLights) {
        dprintf(fd, "Light %d: %s\n", light.id, toString(light.type).c_str());
    }
    return STATUS_OK;
}

}  // namespace aidl::android::hardware::light
