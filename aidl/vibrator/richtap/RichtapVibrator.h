/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <aidl/vendor/aac/hardware/richtap/vibrator/BnRichtapVibrator.h>

#include <memory>
#include <vector>

namespace aidl::android::hardware::vibrator::meizu {
class AacVibrator;
}

namespace aidl::vendor::aac::hardware::richtap::vibrator {

class RichtapVibrator final : public BnRichtapVibrator {
  public:
    explicit RichtapVibrator(
            std::shared_ptr<::aidl::android::hardware::vibrator::meizu::AacVibrator> aacVibrator);

    ndk::ScopedAStatus init(const std::shared_ptr<IRichtapCallback>& callback) override;
    ndk::ScopedAStatus setDynamicScale(int32_t scale,
                                       const std::shared_ptr<IRichtapCallback>& callback) override;
    ndk::ScopedAStatus setF0(int32_t f0,
                             const std::shared_ptr<IRichtapCallback>& callback) override;
    ndk::ScopedAStatus stop(const std::shared_ptr<IRichtapCallback>& callback) override;
    ndk::ScopedAStatus setAmplitude(int32_t amplitude,
                                    const std::shared_ptr<IRichtapCallback>& callback) override;
    ndk::ScopedAStatus performHeParam(int32_t interval, int32_t amplitude, int32_t freq,
                                      const std::shared_ptr<IRichtapCallback>& callback) override;
    ndk::ScopedAStatus off(const std::shared_ptr<IRichtapCallback>& callback) override;
    ndk::ScopedAStatus on(int32_t timeoutMs,
                          const std::shared_ptr<IRichtapCallback>& callback) override;
    ndk::ScopedAStatus perform(int32_t effectId, int8_t strength,
                               const std::shared_ptr<IRichtapCallback>& callback,
                               int32_t* _aidl_return) override;
    ndk::ScopedAStatus performEnvelope(const std::vector<int32_t>& envInfo, bool fastFlag,
                                       const std::shared_ptr<IRichtapCallback>& callback) override;
    ndk::ScopedAStatus performRtp(const ndk::ScopedFileDescriptor& fd,
                                  const std::shared_ptr<IRichtapCallback>& callback) override;
    ndk::ScopedAStatus performHe(int32_t looper, int32_t interval, int32_t amplitude, int32_t freq,
                                 const std::vector<int32_t>& patternInfo,
                                 const std::shared_ptr<IRichtapCallback>& callback) override;

  private:
    const std::shared_ptr<::aidl::android::hardware::vibrator::meizu::AacVibrator> mAacVibrator;
};

}  // namespace aidl::vendor::aac::hardware::richtap::vibrator
