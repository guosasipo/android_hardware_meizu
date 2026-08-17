/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include <android-base/logging.h>
#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

#include <cstdlib>
#include <memory>
#include <string>

#include "AacVibrator.h"
#include "RichtapVibrator.h"
#include "Vibrator.h"

using aidl::android::hardware::vibrator::Vibrator;
using aidl::android::hardware::vibrator::meizu::AacVibrator;
using aidl::vendor::aac::hardware::richtap::vibrator::RichtapVibrator;

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(0);

    auto aacVibrator = std::make_shared<AacVibrator>();
    if (!aacVibrator->isReady()) {
        LOG(ERROR) << "AAC RichTap backend initialization failed";
        return EXIT_FAILURE;
    }

    auto vibrator = ndk::SharedRefBase::make<Vibrator>(aacVibrator);
    auto richtap = ndk::SharedRefBase::make<RichtapVibrator>(aacVibrator);

    ndk::SpAIBinder vibratorBinder = vibrator->asBinder();
    ndk::SpAIBinder richtapBinder = richtap->asBinder();
    CHECK_EQ(AIBinder_setExtension(vibratorBinder.get(), richtapBinder.get()), STATUS_OK);

    const std::string instance = std::string(Vibrator::descriptor) + "/default";

    CHECK_EQ(AServiceManager_addService(vibratorBinder.get(), instance.c_str()), STATUS_OK);

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;
}
