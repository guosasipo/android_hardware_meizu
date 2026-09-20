/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include "Controller.h"

#include <aidl/vendor/meizu/hardware/aw_light/BnAwLight.h>
#include <android/binder_auto_utils.h>

#include <sys/types.h>
#include <map>
#include <mutex>

namespace aidl::android::system::suspend {
class ISystemSuspend;
class IWakeLock;
}  // namespace aidl::android::system::suspend

namespace meizu::aw_light {

namespace ipc = ::aidl::vendor::meizu::hardware::aw_light;

class AwLightService : public ipc::BnAwLight {
  public:
    explicit AwLightService(std::shared_ptr<const Catalog> catalog);
    ~AwLightService() override;

    ndk::ScopedAStatus request(const std::shared_ptr<ipc::IAwLightClient>& client,
                               int32_t requestId, const ipc::LightRequest& request) override;
    ndk::ScopedAStatus cancel(const std::shared_ptr<ipc::IAwLightClient>& client,
                              int32_t requestId) override;
    ndk::ScopedAStatus release(const std::shared_ptr<ipc::IAwLightClient>& client) override;
    ndk::ScopedAStatus getState(ipc::LightState* state) override;
    binder_status_t dump(int fd, const char** args, uint32_t numArgs) override;

  private:
    struct Cookie {
        std::weak_ptr<AwLightService> service;
        uint64_t owner;
    };
    struct Client {
        std::shared_ptr<ipc::IAwLightClient> callback;
        ndk::SpAIBinder binder;
        uint64_t owner;
        uid_t uid;
        Cookie* cookie;
    };
    struct BinderLess {
        bool operator()(const AIBinder* left, const AIBinder* right) const {
            return AIBinder_lt(left, right);
        }
    };

    ndk::ScopedAStatus findClientLocked(const std::shared_ptr<ipc::IAwLightClient>& callback,
                                        bool create, uint64_t* owner);
    void clientDied(uint64_t owner);
    void changed(const Controller::State& state);
    bool setAwake(bool awake);
    static ipc::LightState toState(const Controller::State& state);
    static void died(void* cookie);
    static void unlinked(void* cookie);

    std::mutex mClientsMutex;
    std::map<AIBinder*, Client, BinderLess> mClients;
    uint64_t mNextOwner = 0;
    ndk::ScopedAIBinder_DeathRecipient mDeathRecipient;
    std::shared_ptr<::aidl::android::system::suspend::ISystemSuspend> mSuspend;
    std::shared_ptr<::aidl::android::system::suspend::IWakeLock> mWakeLock;
    std::unique_ptr<Controller> mController;
};

}  // namespace meizu::aw_light
