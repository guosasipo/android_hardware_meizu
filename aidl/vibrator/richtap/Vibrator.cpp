/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "Vibrator.h"

#include <android/binder_status.h>
#include <log/log.h>

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <utility>

#include "AacVibrator.h"
#include "HapticEffects.h"

namespace aidl::android::hardware::vibrator {
namespace {

constexpr int32_t kRawLoopCount = 1;
constexpr int32_t kRawIntervalMs = 0;
constexpr int32_t kRawFrequency = 0;
constexpr int32_t kMaxAacAmplitude = 255;
constexpr int32_t kCompositionDelayMaxMs = 1000;
constexpr float kMinimumScale = 0.1f;

float toScale(EffectStrength strength) {
    switch (strength) {
        case EffectStrength::LIGHT:
            return 0.5f;
        case EffectStrength::MEDIUM:
            return 0.75f;
        case EffectStrength::STRONG:
            return 1.0f;
    }
    return 0;
}

void appendPattern(std::vector<int32_t>& output, const meizu::haptics::Pattern& pattern,
                   int32_t offsetMs, float scale) {
    for (size_t i = 0; i < pattern.size; ++i) {
        auto event = pattern.events[i];
        event[1] += offsetMs;
        event[2] = std::max(
                1, static_cast<int32_t>(std::lround(event[2] * std::max(scale, kMinimumScale))));
        output.insert(output.end(), event.begin(), event.end());
    }
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
                    IVibrator::CAP_AMPLITUDE_CONTROL | IVibrator::CAP_COMPOSE_EFFECTS;
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

    const auto* pattern = meizu::haptics::getPattern(effect);
    if (pattern == nullptr) {
        return unsupported();
    }

    std::vector<int32_t> output;
    output.reserve(1 + pattern->size * meizu::haptics::Event{}.size());
    output.push_back(meizu::haptics::kFormat);
    appendPattern(output, *pattern, 0, toScale(strength));
    const int32_t result = mAacVibrator->post(output.data(), output.size(), kRawIntervalMs,
                                              kRawLoopCount, kMaxAacAmplitude, kRawFrequency);
    if (result <= 0) {
        ALOGE("AAC effect %d failed: %d", static_cast<int32_t>(effect), result);
        return backendError(result < 0 ? result : -EIO);
    }

    *_aidl_return = std::max(result, pattern->durationMs);
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

ndk::ScopedAStatus Vibrator::getCompositionDelayMax(int32_t* maxDelayMs) {
    if (maxDelayMs == nullptr) {
        return illegalArgument();
    }
    *maxDelayMs = kCompositionDelayMaxMs;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getCompositionSizeMax(int32_t* maxSize) {
    if (maxSize == nullptr) {
        return illegalArgument();
    }
    *maxSize = meizu::haptics::kMaxEvents;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getSupportedPrimitives(std::vector<CompositePrimitive>* supported) {
    if (supported == nullptr) {
        return illegalArgument();
    }
    supported->assign(meizu::haptics::kSupportedPrimitives.begin(),
                      meizu::haptics::kSupportedPrimitives.end());
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getPrimitiveDuration(CompositePrimitive primitive,
                                                  int32_t* durationMs) {
    if (durationMs == nullptr) {
        return illegalArgument();
    }
    const auto* pattern = meizu::haptics::getPattern(primitive);
    if (pattern == nullptr) {
        return unsupported();
    }
    *durationMs = pattern->durationMs;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::compose(const std::vector<CompositeEffect>& composite,
                                     const std::shared_ptr<IVibratorCallback>& callback) {
    if (composite.empty() || composite.size() > meizu::haptics::kMaxEvents) {
        return illegalArgument();
    }
    for (const auto& effect : composite) {
        if (effect.delayMs < 0 || effect.delayMs > kCompositionDelayMaxMs ||
            !std::isfinite(effect.scale) || effect.scale < 0.0f || effect.scale > 1.0f) {
            return illegalArgument();
        }
        if (meizu::haptics::getPattern(effect.primitive) == nullptr) {
            return unsupported();
        }
    }

    std::vector<int32_t> output;
    output.reserve(1 + composite.size() * meizu::haptics::Event{}.size());
    output.push_back(meizu::haptics::kFormat);
    int32_t durationMs = 0;
    int32_t lastEventEndMs = 0;
    for (const auto& effect : composite) {
        const auto& pattern = *meizu::haptics::getPattern(effect.primitive);
        durationMs += effect.delayMs;
        appendPattern(output, pattern, durationMs, effect.scale);
        durationMs += pattern.durationMs;
        if (pattern.size != 0) {
            lastEventEndMs = durationMs;
        }
    }
    if (output.size() > 1) {
        const int32_t result = mAacVibrator->post(output.data(), output.size(), kRawIntervalMs,
                                                  kRawLoopCount, kMaxAacAmplitude, kRawFrequency);
        if (result <= 0) {
            ALOGE("AAC composition failed: %d", result);
            return backendError(result < 0 ? result : -EIO);
        }
        durationMs = std::max(durationMs, result + durationMs - lastEventEndMs);
    }
    mAacVibrator->scheduleCompletion(durationMs, makeCompletion(callback));
    return ndk::ScopedAStatus::ok();
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
