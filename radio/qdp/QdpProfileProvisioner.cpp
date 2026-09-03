/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "MeizuQdpProfile"

#include <dlfcn.h>
#include <log/log.h>

#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <optional>
#include <string_view>

namespace {

using QmiClient = void*;
using QmiServiceObject = void*;
using IndicationCallback = void (*)(QmiClient, unsigned int, void*, unsigned int, void*);
using ErrorCallback = void (*)(QmiClient, int, void*);

using GetServiceObject = QmiServiceObject (*)(int32_t, int32_t, int32_t);
using InitInstance = int (*)(QmiServiceObject, unsigned int, IndicationCallback, void*, void*,
                             uint32_t, QmiClient*);
using RegisterErrorCallback = int (*)(QmiClient, ErrorCallback, void*);
using SendRaw = int (*)(QmiClient, unsigned int, void*, unsigned int, void*, unsigned int,
                        unsigned int*, unsigned int);
using Release = int (*)(QmiClient);

constexpr unsigned int kAnyInstance = 0xffff;
constexpr unsigned int kIndicationRegister = 0x0003;
constexpr unsigned int kBindSubscription = 0x00af;
constexpr unsigned int kModifyProfile = 0x0028;
constexpr unsigned int kGetProfileList = 0x002a;
constexpr unsigned int kGetProfileSettings = 0x002b;
constexpr unsigned int kConfigureProfileEventList = 0x00a7;
constexpr unsigned int kProfileChanged = 0x00a8;
constexpr int kWdsIdlMinorVersion = 247;
constexpr std::string_view kInitialAttachProfile = "qdp_profile_ia";

uint16_t readLe16(const uint8_t* data) {
    return static_cast<uint16_t>(data[0]) | (static_cast<uint16_t>(data[1]) << 8);
}

struct Tlv {
    const uint8_t* data;
    uint16_t size;
};

std::optional<Tlv> findTlv(const uint8_t* response, size_t responseSize, uint8_t type) {
    size_t offset = 0;
    while (offset + 3 <= responseSize) {
        const uint8_t currentType = response[offset];
        const uint16_t size = readLe16(response + offset + 1);
        offset += 3;
        if (size > responseSize - offset) {
            return std::nullopt;
        }
        if (currentType == type) {
            return Tlv{response + offset, size};
        }
        offset += size;
    }
    return std::nullopt;
}

bool responseSucceeded(const uint8_t* response, size_t responseSize) {
    const auto result = findTlv(response, responseSize, 0x02);
    return result && result->size >= 4 && readLe16(result->data) == 0;
}

class QmiApi {
  public:
    ~QmiApi() {
        if (servicesHandle_ != nullptr) {
            dlclose(servicesHandle_);
        }
        if (cciHandle_ != nullptr) {
            dlclose(cciHandle_);
        }
    }

    bool load() {
        cciHandle_ = dlopen("libqmi_cci.so", RTLD_NOW | RTLD_LOCAL);
        servicesHandle_ = dlopen("libqmiservices.so", RTLD_NOW | RTLD_LOCAL);
        if (cciHandle_ == nullptr || servicesHandle_ == nullptr) {
            ALOGE("Unable to load QMI libraries: %s", dlerror());
            return false;
        }

        initInstance =
                reinterpret_cast<InitInstance>(dlsym(cciHandle_, "qmi_client_init_instance"));
        registerErrorCallback = reinterpret_cast<RegisterErrorCallback>(
                dlsym(cciHandle_, "qmi_client_register_error_cb"));
        sendRaw = reinterpret_cast<SendRaw>(dlsym(cciHandle_, "qmi_client_send_raw_msg_sync"));
        release = reinterpret_cast<Release>(dlsym(cciHandle_, "qmi_client_release"));
        getServiceObject = reinterpret_cast<GetServiceObject>(
                dlsym(servicesHandle_, "wds_get_service_object_internal_v01"));
        if (initInstance == nullptr || registerErrorCallback == nullptr || sendRaw == nullptr ||
            release == nullptr || getServiceObject == nullptr) {
            ALOGE("Unable to resolve QMI functions: %s", dlerror());
            return false;
        }
        return true;
    }

    QmiServiceObject getWdsServiceObject() const {
        return getServiceObject(1, kWdsIdlMinorVersion, 6);
    }

    InitInstance initInstance = nullptr;
    RegisterErrorCallback registerErrorCallback = nullptr;
    SendRaw sendRaw = nullptr;
    Release release = nullptr;

  private:
    void* cciHandle_ = nullptr;
    void* servicesHandle_ = nullptr;
    GetServiceObject getServiceObject = nullptr;
};

class Monitor;

struct CallbackContext {
    Monitor* monitor;
    size_t index;
};

class WdsClient {
  public:
    explicit WdsClient(QmiApi& api) : api_(api) {}

    ~WdsClient() { close(); }

    void close() {
        if (client_ != nullptr) {
            api_.release(client_);
            client_ = nullptr;
        }
        profileEventsEnabled_ = false;
    }

    bool connect(QmiServiceObject service, uint32_t subscription, IndicationCallback indication,
                 ErrorCallback error, CallbackContext* context) {
        close();
        if (api_.initInstance(service, kAnyInstance, indication, context, nullptr, 5000,
                              &client_) != 0 ||
            client_ == nullptr || api_.registerErrorCallback(client_, error, context) != 0 ||
            !bind(subscription)) {
            close();
            return false;
        }
        profileEventsEnabled_ = registerProfileChanges();
        if (!profileEventsEnabled_) {
            ALOGW("Profile-change indications unavailable for subscription %u; using polling",
                  subscription);
        }
        return true;
    }

    bool profileEventsEnabled() const { return profileEventsEnabled_; }

    bool reconcile() {
        std::optional<uint8_t> profile;
        if (!findInitialAttachProfile(&profile)) {
            return false;
        }
        return !profile || ensurePcscfUsingPco(*profile);
    }

  private:
    bool registerProfileChanges() {
        const std::array<uint8_t, 4> indicationRequest = {0x19, 0x01, 0x00, 0x01};
        std::array<uint8_t, 64> response{};
        unsigned int responseSize = 0;
        if (!send(kIndicationRegister, indicationRequest.data(), indicationRequest.size(),
                  response.data(), response.size(), &responseSize) ||
            !responseSucceeded(response.data(), responseSize)) {
            return false;
        }

        const std::array<uint8_t, 6> eventListRequest = {0x10, 0x03, 0x00, 0x01, 0xff, 0xff};
        response.fill(0);
        responseSize = 0;
        return send(kConfigureProfileEventList, eventListRequest.data(), eventListRequest.size(),
                    response.data(), response.size(), &responseSize) &&
               responseSucceeded(response.data(), responseSize);
    }

    bool bind(uint32_t subscription) {
        const std::array<uint8_t, 7> request = {
                0x01,
                0x04,
                0x00,
                static_cast<uint8_t>(subscription),
                static_cast<uint8_t>(subscription >> 8),
                static_cast<uint8_t>(subscription >> 16),
                static_cast<uint8_t>(subscription >> 24),
        };
        std::array<uint8_t, 64> response{};
        unsigned int responseSize = 0;
        return send(kBindSubscription, request.data(), request.size(), response.data(),
                    response.size(), &responseSize) &&
               responseSucceeded(response.data(), responseSize);
    }

    bool findInitialAttachProfile(std::optional<uint8_t>* profile) {
        *profile = std::nullopt;
        const std::array<uint8_t, 4> request = {0x10, 0x01, 0x00, 0x00};
        std::array<uint8_t, 16384> response{};
        unsigned int responseSize = 0;
        if (!send(kGetProfileList, request.data(), request.size(), response.data(), response.size(),
                  &responseSize) ||
            !responseSucceeded(response.data(), responseSize)) {
            return false;
        }

        const auto profiles = findTlv(response.data(), responseSize, 0x01);
        if (!profiles || profiles->size < 1) {
            return false;
        }

        const uint8_t count = profiles->data[0];
        size_t offset = 1;
        for (uint8_t i = 0; i < count; ++i) {
            if (offset + 3 > profiles->size) {
                return false;
            }
            const uint8_t profileType = profiles->data[offset];
            const uint8_t profileIndex = profiles->data[offset + 1];
            const uint8_t nameSize = profiles->data[offset + 2];
            offset += 3;
            if (nameSize > profiles->size - offset) {
                return false;
            }
            if (profileType == 0 && nameSize == kInitialAttachProfile.size() &&
                std::memcmp(profiles->data + offset, kInitialAttachProfile.data(), nameSize) == 0) {
                *profile = profileIndex;
                return true;
            }
            offset += nameSize;
        }
        return true;
    }

    bool ensurePcscfUsingPco(uint8_t profileIndex) {
        const std::array<uint8_t, 5> query = {0x01, 0x02, 0x00, 0x00, profileIndex};
        std::array<uint8_t, 4096> response{};
        unsigned int responseSize = 0;
        if (!send(kGetProfileSettings, query.data(), query.size(), response.data(), response.size(),
                  &responseSize) ||
            !responseSucceeded(response.data(), responseSize)) {
            return false;
        }

        const auto pco = findTlv(response.data(), responseSize, 0x1f);
        if (pco && pco->size >= 1 && pco->data[0] == 1) {
            return true;
        }

        const std::array<uint8_t, 9> modify = {0x01, 0x02, 0x00, 0x00, profileIndex,
                                               0x1f, 0x01, 0x00, 0x01};
        response.fill(0);
        responseSize = 0;
        if (!send(kModifyProfile, modify.data(), modify.size(), response.data(), response.size(),
                  &responseSize) ||
            !responseSucceeded(response.data(), responseSize)) {
            return false;
        }

        response.fill(0);
        responseSize = 0;
        if (!send(kGetProfileSettings, query.data(), query.size(), response.data(), response.size(),
                  &responseSize) ||
            !responseSucceeded(response.data(), responseSize)) {
            return false;
        }
        const auto verifiedPco = findTlv(response.data(), responseSize, 0x1f);
        if (!verifiedPco || verifiedPco->size < 1 || verifiedPco->data[0] != 1) {
            return false;
        }
        ALOGI("Enabled P-CSCF discovery on initial attach profile %u", profileIndex);
        return true;
    }

    bool send(unsigned int message, const void* request, size_t requestSize, void* response,
              size_t responseSize, unsigned int* actualResponseSize) {
        return api_.sendRaw(client_, message, const_cast<void*>(request), requestSize, response,
                            responseSize, actualResponseSize, 5000) == 0;
    }

    QmiApi& api_;
    QmiClient client_ = nullptr;
    bool profileEventsEnabled_ = false;
};

class Monitor {
  public:
    Monitor(QmiApi& api, QmiServiceObject service)
        : service_(service), primary_(api), secondary_(api), clients_{&primary_, &secondary_} {
        contexts_[0] = {this, 0};
        contexts_[1] = {this, 1};
    }

    [[noreturn]] void run() {
        while (true) {
            bool retrySoon = false;
            for (size_t i = 0; i < clients_.size(); ++i) {
                bool reconnect = false;
                {
                    std::lock_guard lock(mutex_);
                    reconnect = disconnected_[i];
                    disconnected_[i] = false;
                }
                if (reconnect) {
                    clients_[i]->close();
                    connected_[i] = false;
                }
                if (!connected_[i]) {
                    connected_[i] = clients_[i]->connect(service_, i + 1, indicationCallback,
                                                         errorCallback, &contexts_[i]);
                    retrySoon |= !connected_[i];
                }
                if (connected_[i] && !clients_[i]->reconcile()) {
                    clients_[i]->close();
                    connected_[i] = false;
                    retrySoon = true;
                }
            }

            const bool profileEventsEnabled = connected_[0] && connected_[1] &&
                                              clients_[0]->profileEventsEnabled() &&
                                              clients_[1]->profileEventsEnabled();
            const auto delay = retrySoon              ? std::chrono::seconds(2)
                               : profileEventsEnabled ? std::chrono::seconds(30 * 60)
                                                      : std::chrono::seconds(60);
            std::unique_lock lock(mutex_);
            condition_.wait_for(lock, delay, [this] { return pending_; });
            pending_ = false;
        }
    }

  private:
    static void indicationCallback(QmiClient, unsigned int message, void* data, unsigned int size,
                                   void* callbackData) {
        if (message != kProfileChanged) {
            return;
        }
        const auto event = findTlv(static_cast<const uint8_t*>(data), size, 0x10);
        if (!event || event->size != 3) {
            return;
        }
        const auto* context = static_cast<CallbackContext*>(callbackData);
        context->monitor->wake(false, context->index);
    }

    static void errorCallback(QmiClient, int, void* data) {
        const auto* context = static_cast<CallbackContext*>(data);
        context->monitor->wake(true, context->index);
    }

    void wake(bool disconnected, size_t index) {
        std::lock_guard lock(mutex_);
        disconnected_[index] |= disconnected;
        pending_ = true;
        condition_.notify_one();
    }

    QmiServiceObject service_;
    WdsClient primary_;
    WdsClient secondary_;
    std::array<WdsClient*, 2> clients_;
    std::array<CallbackContext, 2> contexts_{};
    std::array<bool, 2> connected_{};
    std::array<bool, 2> disconnected_{};
    std::mutex mutex_;
    std::condition_variable condition_;
    bool pending_ = false;
};

}  // namespace

int main() {
    QmiApi api;
    if (!api.load()) {
        return 1;
    }

    const auto service = api.getWdsServiceObject();
    if (service == nullptr) {
        ALOGE("Unable to get WDS service object");
        return 1;
    }

    Monitor(api, service).run();
}
