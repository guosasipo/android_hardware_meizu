/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "RichtapVibrator.h"

#include <android/binder_status.h>
#include <fcntl.h>
#include <log/log.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <thread>
#include <utility>

#include "AacVibrator.h"

namespace aidl::vendor::aac::hardware::richtap::vibrator {
namespace {

using AacVibrator = ::aidl::android::hardware::vibrator::meizu::AacVibrator;

constexpr int32_t kDefaultCallbackDelayMs = 10;
constexpr int32_t kCallbackSuccess = 1;
constexpr int32_t kCallbackFailed = 2;
constexpr int32_t kMaxAmplitude = 255;
constexpr int32_t kMaxDynamicScale = 100;
constexpr int32_t kMinF0 = 51;
constexpr int32_t kMaxF0 = 499;
constexpr size_t kEnvelopeSize = 12;

void sendCallback(const std::shared_ptr<IRichtapCallback>& callback, int32_t delayMs,
                  int32_t status) {
    if (callback == nullptr) {
        return;
    }

    std::thread([callback, delayMs = std::max(delayMs, 0), status] {
        std::this_thread::sleep_for(std::chrono::milliseconds(delayMs));
        if (!callback->onCallback(status).isOk()) {
            ALOGW("Failed to deliver RichTap completion callback");
        }
    }).detach();
}

ndk::ScopedAStatus illegalArgument(const std::shared_ptr<IRichtapCallback>& callback) {
    sendCallback(callback, kDefaultCallbackDelayMs, kCallbackFailed);
    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
}

ndk::ScopedAStatus controlResult(bool success, const std::shared_ptr<IRichtapCallback>& callback) {
    sendCallback(callback, kDefaultCallbackDelayMs, success ? kCallbackSuccess : kCallbackFailed);
    return success ? ndk::ScopedAStatus::ok()
                   : ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
}

ndk::ScopedAStatus durationResult(int32_t durationMs,
                                  const std::shared_ptr<IRichtapCallback>& callback,
                                  bool requirePositive = false) {
    if (durationMs < 0 || (requirePositive && durationMs == 0)) {
        sendCallback(callback, kDefaultCallbackDelayMs, kCallbackFailed);
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    sendCallback(callback, durationMs, kCallbackSuccess);
    return ndk::ScopedAStatus::ok();
}

bool hasValidParameters(int32_t interval, int32_t amplitude, int32_t freq) {
    return interval >= -1 && amplitude >= -1 && amplitude <= kMaxAmplitude && freq >= -1;
}

}  // namespace

RichtapVibrator::RichtapVibrator(std::shared_ptr<AacVibrator> aacVibrator)
    : mAacVibrator(std::move(aacVibrator)) {}

ndk::ScopedAStatus RichtapVibrator::init(const std::shared_ptr<IRichtapCallback>& callback) {
    return controlResult(true, callback);
}

ndk::ScopedAStatus RichtapVibrator::setDynamicScale(
        int32_t scale, const std::shared_ptr<IRichtapCallback>& callback) {
    if (scale < 0 || scale > kMaxDynamicScale) {
        return illegalArgument(callback);
    }

    return controlResult(mAacVibrator->setDynamicScale(static_cast<uint8_t>(scale)) == 0, callback);
}

ndk::ScopedAStatus RichtapVibrator::setF0(int32_t f0,
                                          const std::shared_ptr<IRichtapCallback>& callback) {
    if (f0 < kMinF0 || f0 > kMaxF0) {
        return illegalArgument(callback);
    }

    return controlResult(mAacVibrator->setF0(f0) == 0, callback);
}

ndk::ScopedAStatus RichtapVibrator::stop(const std::shared_ptr<IRichtapCallback>& callback) {
    const bool stopped = mAacVibrator->stop();
    mAacVibrator->completePending();
    return controlResult(stopped, callback);
}

ndk::ScopedAStatus RichtapVibrator::setAmplitude(
        int32_t amplitude, const std::shared_ptr<IRichtapCallback>& callback) {
    if (amplitude < -1 || amplitude > kMaxAmplitude) {
        return illegalArgument(callback);
    }

    const uint8_t aacAmplitude =
            amplitude == -1 ? static_cast<uint8_t>(kMaxAmplitude) : static_cast<uint8_t>(amplitude);
    return controlResult(mAacVibrator->setAmplitude(aacAmplitude) == 0, callback);
}

ndk::ScopedAStatus RichtapVibrator::performHeParam(
        int32_t interval, int32_t amplitude, int32_t freq,
        const std::shared_ptr<IRichtapCallback>& callback) {
    if (!hasValidParameters(interval, amplitude, freq)) {
        return illegalArgument(callback);
    }

    if (interval == 0 && amplitude == 0 && freq == 0) {
        const bool stopped = mAacVibrator->stop();
        mAacVibrator->completePending();
        return controlResult(stopped, callback);
    }

    return controlResult(mAacVibrator->performParam(interval, amplitude, freq), callback);
}

ndk::ScopedAStatus RichtapVibrator::off(const std::shared_ptr<IRichtapCallback>& callback) {
    return controlResult(mAacVibrator->off() == 0, callback);
}

ndk::ScopedAStatus RichtapVibrator::on(int32_t timeoutMs,
                                       const std::shared_ptr<IRichtapCallback>& callback) {
    if (timeoutMs <= 0) {
        return illegalArgument(callback);
    }

    return durationResult(mAacVibrator->on(static_cast<uint32_t>(timeoutMs)), callback);
}

ndk::ScopedAStatus RichtapVibrator::perform(int32_t effectId, int8_t strength,
                                            const std::shared_ptr<IRichtapCallback>& callback,
                                            int32_t* _aidl_return) {
    if (_aidl_return == nullptr) {
        return illegalArgument(callback);
    }
    *_aidl_return = 0;

    if (effectId < 0 || strength < 1 || strength > 100) {
        return illegalArgument(callback);
    }

    const int32_t duration = mAacVibrator->performPrebaked(static_cast<uint32_t>(effectId),
                                                           static_cast<int32_t>(strength));
    if (duration < 0) {
        sendCallback(callback, kDefaultCallbackDelayMs, kCallbackFailed);
        return ndk::ScopedAStatus::fromServiceSpecificError(duration);
    }

    *_aidl_return = duration;
    sendCallback(callback, duration, kCallbackSuccess);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus RichtapVibrator::performEnvelope(
        const std::vector<int32_t>& envInfo, bool fastFlag,
        const std::shared_ptr<IRichtapCallback>& callback) {
    if (envInfo.size() != kEnvelopeSize ||
        std::any_of(envInfo.begin(), envInfo.end(), [](int32_t value) { return value < 0; })) {
        return illegalArgument(callback);
    }

    return durationResult(mAacVibrator->performEnvelope(envInfo.data(), envInfo.size(), fastFlag),
                          callback);
}

ndk::ScopedAStatus RichtapVibrator::performRtp(const ndk::ScopedFileDescriptor& fd,
                                               const std::shared_ptr<IRichtapCallback>& callback) {
    if (fd.get() < 0) {
        return illegalArgument(callback);
    }

    const int duplicateFd = fcntl(fd.get(), F_DUPFD_CLOEXEC, 0);
    if (duplicateFd < 0) {
        return controlResult(false, callback);
    }

    const int32_t duration = mAacVibrator->performRtp(duplicateFd);
    if (duration < 0) {
        close(duplicateFd);
    }
    return durationResult(duration, callback, true);
}

ndk::ScopedAStatus RichtapVibrator::performHe(int32_t looper, int32_t interval, int32_t amplitude,
                                              int32_t freq, const std::vector<int32_t>& patternInfo,
                                              const std::shared_ptr<IRichtapCallback>& callback) {
    if (looper < 0 || patternInfo.empty() ||
        patternInfo.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max()) ||
        !hasValidParameters(interval, amplitude, freq)) {
        return illegalArgument(callback);
    }

    return durationResult(mAacVibrator->post(patternInfo.data(), patternInfo.size(), interval,
                                             looper, amplitude, freq),
                          callback);
}

}  // namespace aidl::vendor::aac::hardware::richtap::vibrator
