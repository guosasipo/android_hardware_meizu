/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <mutex>
#include <thread>

namespace aidl::android::hardware::vibrator::meizu {

class AacVibrator final {
  public:
    using Completion = std::function<void()>;

    AacVibrator();
    ~AacVibrator();

    AacVibrator(const AacVibrator&) = delete;
    AacVibrator& operator=(const AacVibrator&) = delete;

    [[nodiscard]] bool isReady() const { return mReady; }

    int32_t on(uint32_t timeoutMs);
    int32_t off();
    bool stop();
    int32_t setAmplitude(uint8_t amplitude);
    int32_t setDynamicScale(uint8_t scale);
    int32_t setF0(int32_t f0);
    int32_t performPrebaked(uint32_t effectId, int32_t strength);
    int32_t performSystemPrebaked(uint32_t effectId, int32_t strength);
    int32_t performEnvelope(const int32_t* envelope, size_t envelopeSize, bool fastFlag);
    int32_t performRtp(int32_t fd);
    bool performParam(int32_t interval, int32_t amplitude, int32_t frequency);
    int32_t post(const int32_t* pattern, size_t patternSize, int32_t intervalMs, int32_t loopCount,
                 int32_t amplitude, int32_t frequency);

    void scheduleCompletion(int32_t durationMs, Completion completion);
    void completePending();

  private:
    struct PendingCompletion {
        std::chrono::steady_clock::time_point deadline;
        Completion completion;
    };

    bool initialize();
    void completionLoop();

    std::mutex mMutex;
    const bool mReady;

    std::mutex mCompletionMutex;
    std::condition_variable mCompletionCv;
    PendingCompletion mPendingCompletion;
    uint64_t mGeneration = 0;
    bool mStopping = false;
    std::thread mCompletionThread;
};

}  // namespace aidl::android::hardware::vibrator::meizu
