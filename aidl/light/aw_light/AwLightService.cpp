/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "AwLightService.h"

#include <aidl/android/system/suspend/ISystemSuspend.h>
#include <aidl/android/system/suspend/IWakeLock.h>
#include <aidl/android/system/suspend/WakeLockType.h>
#include <android-base/logging.h>
#include <android/binder_ibinder.h>
#include <android/binder_manager.h>

#include <cerrno>
#include <cmath>
#include <cstdio>
#include <vector>

namespace meizu::aw_light {

using ::aidl::android::system::suspend::ISystemSuspend;
using ::aidl::android::system::suspend::WakeLockType;

AwLightService::AwLightService(std::shared_ptr<const Catalog> catalog)
    : mDeathRecipient(AIBinder_DeathRecipient_new(died)) {
    AIBinder_DeathRecipient_setOnUnlinked(mDeathRecipient.get(), unlinked);
    if (catalog) {
        auto backend = std::make_unique<Aw20072>(catalog);
        mController = std::make_unique<Controller>(
                catalog, std::move(backend), [this](bool awake) { return setAwake(awake); },
                [this](const Controller::State& state) { changed(state); });
    } else {
        Aw20072 backend(nullptr);
        backend.stop();
    }
}

AwLightService::~AwLightService() {
    mController.reset();
    for (const auto& [binder, client] : mClients) {
        AIBinder_unlinkToDeath(binder, mDeathRecipient.get(), client.cookie);
    }
}

void AwLightService::died(void* value) {
    auto* cookie = static_cast<Cookie*>(value);
    if (auto service = cookie->service.lock()) service->clientDied(cookie->owner);
}

void AwLightService::unlinked(void* value) {
    delete static_cast<Cookie*>(value);
}

void AwLightService::clientDied(uint64_t owner) {
    {
        std::lock_guard lock(mClientsMutex);
        for (auto it = mClients.begin(); it != mClients.end(); ++it) {
            if (it->second.owner == owner) {
                mClients.erase(it);
                break;
            }
        }
    }
    if (mController) mController->clear(owner);
}

ndk::ScopedAStatus AwLightService::findClientLocked(
        const std::shared_ptr<ipc::IAwLightClient>& callback, bool create, uint64_t* owner) {
    *owner = 0;
    if (!callback) return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    auto binder = callback->asBinder();
    const uid_t uid = AIBinder_getCallingUid();
    auto found = mClients.find(binder.get());
    if (found != mClients.end()) {
        if (found->second.uid != uid) return ndk::ScopedAStatus::fromExceptionCode(EX_SECURITY);
        *owner = found->second.owner;
        return ndk::ScopedAStatus::ok();
    }
    if (!create) return ndk::ScopedAStatus::ok();
    if (mClients.size() >= 8) return ndk::ScopedAStatus::fromServiceSpecificError(ENOSPC);
    const uint64_t id = ++mNextOwner;
    auto* cookie = new Cookie{ref<AwLightService>(), id};
    const binder_status_t status =
            AIBinder_linkToDeath(binder.get(), mDeathRecipient.get(), cookie);
    if (status != STATUS_OK) return ndk::ScopedAStatus::fromStatus(status);
    mClients.emplace(binder.get(), Client{callback, binder, id, uid, cookie});
    *owner = id;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus AwLightService::request(const std::shared_ptr<ipc::IAwLightClient>& client,
                                           int32_t requestId, const ipc::LightRequest& request) {
    if (!mController) return ndk::ScopedAStatus::fromServiceSpecificError(ENODEV);
    if (requestId < 0 || requestId > 31 || request.color < 0 || request.color > 0xffffff ||
        !std::isfinite(request.strength) || request.strength < 0 || request.strength > 1 ||
        request.progress < 0 || request.progress > 100 || request.amplitude < 0 ||
        request.amplitude > 60 || request.letter < 0 || request.letter > 127 ||
        request.priority < 0 || request.priority > 100 || request.timeoutMs < 0 ||
        request.timeoutMs > 60000) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }
    Options options;
    options.color = {static_cast<uint8_t>(request.color >> 16),
                     static_cast<uint8_t>(request.color >> 8), static_cast<uint8_t>(request.color)};
    options.strength = request.strength;
    options.progress = request.progress;
    options.amplitude = request.amplitude;
    options.letter = request.letter;
    std::lock_guard lock(mClientsMutex);
    uint64_t owner;
    auto status = findClientLocked(client, true, &owner);
    if (!status.isOk()) return status;
    if (!mController->submit(owner, requestId, request.effect, options, request.priority,
                             request.timeoutMs, request.resume)) {
        return ndk::ScopedAStatus::fromServiceSpecificError(EINVAL);
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus AwLightService::cancel(const std::shared_ptr<ipc::IAwLightClient>& client,
                                          int32_t requestId) {
    if (!mController) return ndk::ScopedAStatus::fromServiceSpecificError(ENODEV);
    if (requestId < 0 || requestId > 31)
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    std::lock_guard lock(mClientsMutex);
    uint64_t owner;
    auto status = findClientLocked(client, false, &owner);
    if (!status.isOk()) return status;
    if (owner) mController->cancel(owner, requestId);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus AwLightService::release(const std::shared_ptr<ipc::IAwLightClient>& client) {
    ndk::SpAIBinder binder;
    Cookie* cookie = nullptr;
    uint64_t owner;
    {
        std::lock_guard lock(mClientsMutex);
        auto status = findClientLocked(client, false, &owner);
        if (!status.isOk()) return status;
        if (!owner) return ndk::ScopedAStatus::ok();
        binder = client->asBinder();
        auto found = mClients.find(binder.get());
        cookie = found->second.cookie;
        mClients.erase(found);
    }
    if (mController) mController->clear(owner);
    AIBinder_unlinkToDeath(binder.get(), mDeathRecipient.get(), cookie);
    return ndk::ScopedAStatus::ok();
}

ipc::LightState AwLightService::toState(const Controller::State& state) {
    ipc::LightState result;
    result.owner = state.owner;
    result.requestId = state.requestId;
    result.effect = state.effect;
    result.active = state.active;
    result.animated = state.animated;
    result.error = state.error;
    return result;
}

ndk::ScopedAStatus AwLightService::getState(ipc::LightState* state) {
    if (!mController) return ndk::ScopedAStatus::fromServiceSpecificError(ENODEV);
    *state = toState(mController->snapshot());
    return ndk::ScopedAStatus::ok();
}

void AwLightService::changed(const Controller::State& state) {
    std::shared_ptr<ipc::IAwLightClient> callback;
    {
        std::lock_guard lock(mClientsMutex);
        for (const auto& [binder, client] : mClients) {
            if (client.owner == state.owner) {
                callback = client.callback;
                break;
            }
        }
    }
    if (callback) callback->onStateChanged(toState(state));
}

bool AwLightService::setAwake(bool awake) {
    if (!awake) {
        if (mWakeLock) mWakeLock->release();
        mWakeLock.reset();
        return true;
    }
    if (mWakeLock) return true;
    if (!mSuspend) {
        mSuspend = ISystemSuspend::fromBinder(ndk::SpAIBinder(
                AServiceManager_checkService("android.system.suspend.ISystemSuspend/default")));
    }
    if (!mSuspend) return false;
    auto status = mSuspend->acquireWakeLock(WakeLockType::PARTIAL, "aw_light", &mWakeLock);
    if (!status.isOk() || !mWakeLock) {
        mWakeLock.reset();
        mSuspend.reset();
        return false;
    }
    return true;
}

binder_status_t AwLightService::dump(int fd, const char** /* args */, uint32_t /* numArgs */) {
    if (!mController) {
        dprintf(fd, "aw_light: unavailable\n");
        return STATUS_OK;
    }
    const auto state = mController->snapshot();
    dprintf(fd, "aw_light: active=%d effect=%d request=%d queued=%d wake=%d error=%d\n",
            state.active, state.effect, state.requestId, state.requestCount, state.wakeHeld,
            state.error);
    return STATUS_OK;
}

}  // namespace meizu::aw_light
