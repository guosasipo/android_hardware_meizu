/*
 * SPDX-FileCopyrightText: 2021 The LineageOS Project
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "android.hardware.light-service.meizu"

#include "Lights.h"

#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

#include <cstdlib>
#include <memory>
#include <string>

using aidl::android::hardware::light::Lights;

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(0);

    auto service = ndk::SharedRefBase::make<Lights>();
    if (!service->isReady()) {
        LOG(ERROR) << "Light initialization failed";
        return EXIT_FAILURE;
    }

    const std::string instance = std::string(Lights::descriptor) + "/default";
    const binder_status_t status =
            AServiceManager_addService(service->asBinder().get(), instance.c_str());
    CHECK_EQ(status, STATUS_OK);

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;
}
