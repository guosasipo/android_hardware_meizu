/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "AacVibrator.h"

#include <log/log.h>

#include <cerrno>
#include <limits>
#include <utility>

#include "aac_vibra_function.h"

namespace aidl::android::hardware::vibrator::meizu {

AacVibrator::AacVibrator()
    : mReady(initialize()), mCompletionThread(&AacVibrator::completionLoop, this) {}

AacVibrator::~AacVibrator() {
    Completion completion;
    {
        std::lock_guard lock(mCompletionMutex);
        mStopping = true;
        ++mGeneration;
        completion.swap(mPendingCompletion.completion);
    }
    mCompletionCv.notify_all();

    if (mCompletionThread.joinable()) {
        mCompletionThread.join();
    }

    if (completion) {
        completion();
    }

    std::lock_guard lock(mMutex);
    if (mReady) {
        (void)aac_vibra_looper_stopPerformHe();
    }
}

bool AacVibrator::initialize() {
    uint32_t deviceType = 0;
    const int32_t status = aac_vibra_init(&deviceType);
    if (status != 0) {
        ALOGE("aac_vibra_init failed: %d", status);
        return false;
    }

    aac_vibra_looper_start();

    ALOGI("AAC RichTap backend initialized (device type %u)", deviceType);
    return true;
}

int32_t AacVibrator::on(uint32_t timeoutMs) {
    std::lock_guard lock(mMutex);
    return aac_vibra_looper_on(timeoutMs);
}

int32_t AacVibrator::off() {
    std::lock_guard lock(mMutex);
    return aac_vibra_off();
}

bool AacVibrator::stop() {
    std::lock_guard lock(mMutex);
    return aac_vibra_looper_stopPerformHe();
}

int32_t AacVibrator::setAmplitude(uint8_t amplitude) {
    std::lock_guard lock(mMutex);
    return aac_vibra_setAmplitude(amplitude);
}

int32_t AacVibrator::setDynamicScale(uint8_t scale) {
    std::lock_guard lock(mMutex);
    return aac_vibra_dynamic_scale(scale);
}

int32_t AacVibrator::setF0(int32_t f0) {
    std::lock_guard lock(mMutex);
    return aac_vibra_setting_f0(0, f0);
}

int32_t AacVibrator::performSystemPrebaked(uint32_t effectId, int32_t strength) {
    std::lock_guard lock(mMutex);

    const int32_t amplitudeResult = aac_vibra_setAmplitude(std::numeric_limits<uint8_t>::max());
    if (amplitudeResult != 0) {
        return amplitudeResult;
    }
    return aac_vibra_looper_prebaked_effect(effectId, strength);
}

int32_t AacVibrator::performPrebaked(uint32_t effectId, int32_t strength) {
    std::lock_guard lock(mMutex);
    return aac_vibra_looper_prebaked_effect(effectId, strength);
}

int32_t AacVibrator::performEnvelope(const int32_t* envelope, size_t envelopeSize, bool fastFlag) {
    if (envelope == nullptr || envelopeSize == 0 ||
        envelopeSize > static_cast<size_t>(std::numeric_limits<uint32_t>::max())) {
        return -EINVAL;
    }

    std::lock_guard lock(mMutex);
    return aac_vibra_looper_envelope(envelope, static_cast<uint32_t>(envelopeSize), fastFlag);
}

int32_t AacVibrator::performRtp(int32_t fd) {
    if (fd < 0) {
        return -EINVAL;
    }

    std::lock_guard lock(mMutex);
    return aac_vibra_looper_rtp(fd);
}

bool AacVibrator::performParam(int32_t interval, int32_t amplitude, int32_t frequency) {
    std::lock_guard lock(mMutex);
    return aac_vibra_looper_performParam(interval, amplitude, frequency);
}

int32_t AacVibrator::post(const int32_t* pattern, size_t patternSize, int32_t intervalMs,
                          int32_t loopCount, int32_t amplitude, int32_t frequency) {
    if (pattern == nullptr || patternSize == 0 ||
        patternSize > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        return -EINVAL;
    }

    std::lock_guard lock(mMutex);
    return aac_vibra_looper_post(pattern, static_cast<int32_t>(patternSize), intervalMs, loopCount,
                                 amplitude, frequency);
}

void AacVibrator::scheduleCompletion(int32_t durationMs, Completion completion) {
    Completion replaced;
    {
        std::lock_guard lock(mCompletionMutex);
        ++mGeneration;
        replaced.swap(mPendingCompletion.completion);
        mPendingCompletion = {
                .deadline =
                        std::chrono::steady_clock::now() + std::chrono::milliseconds(durationMs),
                .completion = std::move(completion),
        };
    }
    mCompletionCv.notify_all();

    if (replaced) {
        replaced();
    }
}

void AacVibrator::completePending() {
    Completion completion;
    {
        std::lock_guard lock(mCompletionMutex);
        ++mGeneration;
        completion.swap(mPendingCompletion.completion);
    }
    mCompletionCv.notify_all();

    if (completion) {
        completion();
    }
}

void AacVibrator::completionLoop() {
    std::unique_lock lock(mCompletionMutex);
    while (!mStopping) {
        mCompletionCv.wait(lock, [this] {
            return mStopping || static_cast<bool>(mPendingCompletion.completion);
        });
        if (mStopping) {
            break;
        }

        const uint64_t generation = mGeneration;
        const auto deadline = mPendingCompletion.deadline;
        if (mCompletionCv.wait_until(lock, deadline, [this, generation] {
                return mStopping || mGeneration != generation;
            })) {
            continue;
        }

        Completion completion;
        completion.swap(mPendingCompletion.completion);
        ++mGeneration;
        lock.unlock();
        if (completion) {
            completion();
        }
        lock.lock();
    }
}

}  // namespace aidl::android::hardware::vibrator::meizu
