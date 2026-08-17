/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "Vibrator.h"

#include <android/binder_status.h>
#include <log/log.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <utility>

#include "AacVibrator.h"

namespace aidl::android::hardware::vibrator {
namespace {

constexpr std::array<int32_t, 35> kRawTextureTick = {
        0x1,  0x1001, 0x0,   0x32, 0x21, 0x1d, 0x0,    0x0,  0x0,  0xc,  0x3b, 0x0,
        0x16, 0x4b,   -0x15, 0x1d, 0x0,  0x0,  0x1001, 0x1e, 0x64, 0x1e, 0x0,  0x0,
        0x0,  0x0,    0x0,   0x0,  0x0,  0x0,  0x0,    0x0,  0x0,  0x0,  0x0,
};

constexpr int32_t kRawLoopCount = 1;
constexpr int32_t kRawIntervalMs = 0;
constexpr int32_t kRawFrequency = 0;
constexpr int32_t kMaxAacAmplitude = 255;
constexpr int32_t kFallbackDurationMs = 30;
constexpr uint32_t kRichTapPrebakedEffectBase = 0x1000;
constexpr int32_t kLightStrength = 69;
constexpr int32_t kMediumStrength = 89;
constexpr int32_t kStrongStrength = 99;

bool isNativePrebaked(Effect effect) {
    switch (effect) {
        case Effect::CLICK:
        case Effect::DOUBLE_CLICK:
        case Effect::TICK:
        case Effect::THUD:
        case Effect::POP:
        case Effect::HEAVY_CLICK:
            return true;
        default:
            return false;
    }
}

int32_t toAacStrength(EffectStrength strength) {
    switch (strength) {
        case EffectStrength::LIGHT:
            return kLightStrength;
        case EffectStrength::MEDIUM:
            return kMediumStrength;
        case EffectStrength::STRONG:
            return kStrongStrength;
    }
    return 0;
}

ndk::ScopedAStatus unsupported() {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus illegalArgument() {
    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
}

ndk::ScopedAStatus backendError(int32_t error) {
    return ndk::ScopedAStatus::fromServiceSpecificError(error);
}

meizu::AacVibrator::Completion makeCompletion(const std::shared_ptr<IVibratorCallback>& callback) {
    if (callback == nullptr) {
        return {};
    }
    return [callback] {
        if (!callback->onComplete().isOk()) {
            ALOGW("Failed to deliver vibrator completion callback");
        }
    };
}

}  // namespace

Vibrator::Vibrator(std::shared_ptr<meizu::AacVibrator> aacVibrator)
    : mAacVibrator(std::move(aacVibrator)) {}

ndk::ScopedAStatus Vibrator::getCapabilities(int32_t* _aidl_return) {
    if (_aidl_return == nullptr) {
        return illegalArgument();
    }

    *_aidl_return = IVibrator::CAP_ON_CALLBACK | IVibrator::CAP_PERFORM_CALLBACK |
                    IVibrator::CAP_AMPLITUDE_CONTROL;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::off() {
    (void)mAacVibrator->stop();
    mAacVibrator->completePending();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::on(int32_t timeoutMs,
                                const std::shared_ptr<IVibratorCallback>& callback) {
    if (timeoutMs <= 0) {
        return illegalArgument();
    }

    const int32_t durationMs = mAacVibrator->on(static_cast<uint32_t>(timeoutMs));
    if (durationMs < 0) {
        ALOGE("aac_vibra_looper_on(%d) failed: %d", timeoutMs, durationMs);
        return backendError(durationMs);
    }

    mAacVibrator->scheduleCompletion(durationMs, makeCompletion(callback));
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::perform(Effect effect, EffectStrength strength,
                                     const std::shared_ptr<IVibratorCallback>& callback,
                                     int32_t* _aidl_return) {
    if (_aidl_return == nullptr) {
        return illegalArgument();
    }

    if (strength != EffectStrength::LIGHT && strength != EffectStrength::MEDIUM &&
        strength != EffectStrength::STRONG) {
        return unsupported();
    }

    int32_t result;
    if (isNativePrebaked(effect)) {
        result = mAacVibrator->performSystemPrebaked(
                kRichTapPrebakedEffectBase + static_cast<uint32_t>(effect),
                toAacStrength(strength));
    } else if (effect == Effect::TEXTURE_TICK) {
        result = mAacVibrator->post(kRawTextureTick.data(), kRawTextureTick.size(), kRawIntervalMs,
                                    kRawLoopCount, kMaxAacAmplitude, kRawFrequency);
    } else {
        return unsupported();
    }

    if (result < 0) {
        ALOGE("AAC effect %d failed: %d", static_cast<int32_t>(effect), result);
        return backendError(result);
    }

    *_aidl_return = result > 0 ? result : kFallbackDurationMs;
    mAacVibrator->scheduleCompletion(*_aidl_return, makeCompletion(callback));
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getSupportedEffects(std::vector<Effect>* _aidl_return) {
    if (_aidl_return == nullptr) {
        return illegalArgument();
    }

    *_aidl_return = {
            Effect::CLICK, Effect::DOUBLE_CLICK, Effect::TICK,         Effect::THUD,
            Effect::POP,   Effect::HEAVY_CLICK,  Effect::TEXTURE_TICK,
    };
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::setAmplitude(float amplitude) {
    if (!std::isfinite(amplitude) || amplitude <= 0.0f || amplitude > 1.0f) {
        return illegalArgument();
    }

    const int32_t aacAmplitude = std::clamp(
            static_cast<int32_t>(std::lround(amplitude * kMaxAacAmplitude)), 1, kMaxAacAmplitude);
    const int32_t result = mAacVibrator->setAmplitude(static_cast<uint8_t>(aacAmplitude));
    if (result != 0) {
        ALOGE("aac_vibra_setAmplitude(%d) failed: %d", aacAmplitude, result);
        return backendError(result);
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::setExternalControl(bool /* enabled */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getCompositionDelayMax(int32_t* /* maxDelayMs */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getCompositionSizeMax(int32_t* /* maxSize */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getSupportedPrimitives(std::vector<CompositePrimitive>* supported) {
    if (supported == nullptr) {
        return illegalArgument();
    }
    supported->clear();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getPrimitiveDuration(CompositePrimitive /* primitive */,
                                                  int32_t* /* durationMs */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::compose(const std::vector<CompositeEffect>& /* composite */,
                                     const std::shared_ptr<IVibratorCallback>& /* callback */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getSupportedAlwaysOnEffects(std::vector<Effect>* /* _aidl_return */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::alwaysOnEnable(int32_t /* id */, Effect /* effect */,
                                            EffectStrength /* strength */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::alwaysOnDisable(int32_t /* id */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getResonantFrequency(float* /* resonantFreqHz */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getQFactor(float* /* qFactor */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getFrequencyResolution(float* /* freqResolutionHz */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getFrequencyMinimum(float* /* freqMinimumHz */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getBandwidthAmplitudeMap(std::vector<float>* /* _aidl_return */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getPwlePrimitiveDurationMax(int32_t* /* durationMs */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getPwleCompositionSizeMax(int32_t* /* maxSize */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getSupportedBraking(std::vector<Braking>* /* supported */) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::composePwle(const std::vector<PrimitivePwle>& /* composite */,
                                         const std::shared_ptr<IVibratorCallback>& /* callback */) {
    return unsupported();
}

}  // namespace aidl::android::hardware::vibrator
