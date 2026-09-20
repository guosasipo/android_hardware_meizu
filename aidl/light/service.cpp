/*
 * SPDX-FileCopyrightText: 2021 The LineageOS Project
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "android.hardware.light-service.meizu"

#include "AwLightService.h"
#include "Lights.h"

#include <android-base/logging.h>
#include <android/binder_ibinder.h>
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

    std::string error;
    auto catalog = meizu::aw_light::Catalog::load("/vendor/etc/meizu-lights/effects.json", &error);
    if (!catalog) LOG(ERROR) << "AW effect catalog unavailable: " << error;
    auto awLight = ndk::SharedRefBase::make<meizu::aw_light::AwLightService>(catalog);
    ndk::SpAIBinder binder = service->asBinder();
    CHECK_EQ(AIBinder_setExtension(binder.get(), awLight->asBinder().get()), STATUS_OK);

    const std::string instance = std::string(Lights::descriptor) + "/default";
    const binder_status_t status = AServiceManager_addService(binder.get(), instance.c_str());
    CHECK_EQ(status, STATUS_OK);

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;
}
