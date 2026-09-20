/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "vendor.lineage.health-service.meizu"

#include "ChargingControl.h"

#include <aidl/vendor/lineage/health/ChargingControlSupportedMode.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>

#include <unistd.h>
#include <string>

namespace aidl::vendor::lineage::health {
namespace {

constexpr char kEnabledPath[] = "/sys/class/qcom-battery/charge_control_en";
constexpr char kStartPath[] = "/sys/class/power_supply/battery/charge_control_start_threshold";
constexpr char kEndPath[] = "/sys/class/power_supply/battery/charge_control_end_threshold";

constexpr int kStartMinimum = 50;
constexpr int kStartMaximum = 95;
constexpr int kEndMinimum = 55;
constexpr int kEndMaximum = 100;
constexpr int kNodeWaitRetries = 100;
constexpr useconds_t kNodeWaitDelayUs = 10000;

bool readInt(const std::string& path, int* value) {
    std::string content;
    if (!android::base::ReadFileToString(path, &content, true) ||
        !android::base::ParseInt(android::base::Trim(content), value)) {
        PLOG(ERROR) << "Failed to read " << path;
        return false;
    }
    return true;
}

bool writeInt(const std::string& path, int value) {
    if (!android::base::WriteStringToFile(std::to_string(value), path, true)) {
        PLOG(ERROR) << "Failed to write " << value << " to " << path;
        return false;
    }
    return true;
}

ndk::ScopedAStatus unsupported() {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

}  // namespace

ChargingControl::ChargingControl() : mReady(initialize()) {}

bool ChargingControl::isReady() const {
    return mReady;
}

bool ChargingControl::initialize() {
    bool nodesReady = false;
    for (int retry = 0; retry < kNodeWaitRetries; ++retry) {
        if (access(kEnabledPath, R_OK | W_OK) == 0 && access(kStartPath, R_OK | W_OK) == 0 &&
            access(kEndPath, R_OK | W_OK) == 0) {
            nodesReady = true;
            break;
        }
        usleep(kNodeWaitDelayUs);
    }
    if (!nodesReady) {
        LOG(ERROR) << "Charging control nodes are unavailable";
        return false;
    }

    int enabled;
    if (!readInt(kEnabledPath, &enabled)) {
        return false;
    }
    if (enabled != 0 && enabled != 1) {
        LOG(ERROR) << "Unexpected charging control state: " << enabled;
        return false;
    }

    if (enabled == 1) {
        int start;
        int end;
        if (!readInt(kStartPath, &start) || !readInt(kEndPath, &end) || start < kStartMinimum ||
            start > kStartMaximum || end < kEndMinimum || end > kEndMaximum || start >= end) {
            LOG(WARNING) << "Disabling invalid active charging limit";
            return setEnabled(false);
        }
    }

    return true;
}

bool ChargingControl::setEnabled(bool enabled) {
    const int requested = enabled ? 1 : 0;
    int actual = -1;
    if (!writeInt(kEnabledPath, requested) || !readInt(kEnabledPath, &actual) ||
        actual != requested) {
        LOG(ERROR) << "Charging control state verification failed: requested " << requested
                   << ", got " << actual;
        return false;
    }
    return true;
}

bool ChargingControl::setLimit(int start, int end) {
    if (!setEnabled(true) || !writeInt(kEndPath, end) || !writeInt(kStartPath, start)) {
        setEnabled(false);
        return false;
    }

    int actualEnabled = -1;
    int actualStart = -1;
    int actualEnd = -1;
    if (!readInt(kEnabledPath, &actualEnabled) || !readInt(kStartPath, &actualStart) ||
        !readInt(kEndPath, &actualEnd) || actualEnabled != 1 || actualStart != start ||
        actualEnd != end) {
        LOG(ERROR) << "Charging limit verification failed: requested " << start << '-' << end
                   << ", got " << actualStart << '-' << actualEnd;
        setEnabled(false);
        return false;
    }

    return true;
}

ndk::ScopedAStatus ChargingControl::getChargingEnabled(bool* /* _aidl_return */) {
    return unsupported();
}

ndk::ScopedAStatus ChargingControl::setChargingEnabled(bool /* enabled */) {
    return unsupported();
}

ndk::ScopedAStatus ChargingControl::setChargingDeadline(int64_t /* deadline */) {
    return unsupported();
}

ndk::ScopedAStatus ChargingControl::getSupportedMode(int* _aidl_return) {
    *_aidl_return = mReady ? static_cast<int>(ChargingControlSupportedMode::LIMIT) : 0;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus ChargingControl::getChargingDeadline(int64_t* /* _aidl_return */) {
    return unsupported();
}

ndk::ScopedAStatus ChargingControl::getChargingLimit(ChargingLimitInfo* _aidl_return) {
    int enabled;
    if (!mReady || !readInt(kEnabledPath, &enabled)) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }
    if (enabled == 0) {
        _aidl_return->min = 0;
        _aidl_return->max = 100;
        return ndk::ScopedAStatus::ok();
    }
    if (enabled != 1 || !readInt(kStartPath, &_aidl_return->min) ||
        !readInt(kEndPath, &_aidl_return->max)) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus ChargingControl::setChargingLimit(const ChargingLimitInfo& limit) {
    if (!mReady) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    if (limit.min == 0 && limit.max == 100) {
        if (!setEnabled(false)) {
            return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
        }
        return ndk::ScopedAStatus::ok();
    }

    const int start = limit.min;
    const int end = limit.max;
    if (start < kStartMinimum || start > kStartMaximum || end < kEndMinimum || end > kEndMaximum ||
        start >= end) {
        LOG(ERROR) << "Invalid charging limit: " << start << '-' << end;
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    if (!setLimit(start, end)) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    return ndk::ScopedAStatus::ok();
}

binder_status_t ChargingControl::dump(int fd, const char** /* args */, uint32_t /* numArgs */) {
    int enabled = -1;
    int start = -1;
    int end = -1;
    readInt(kEnabledPath, &enabled);
    readInt(kStartPath, &start);
    readInt(kEndPath, &end);

    dprintf(fd, "Meizu charging control ready: %s\n", mReady ? "true" : "false");
    dprintf(fd, "Charging control enabled: %d\n", enabled);
    dprintf(fd, "Charging limit: %d-%d\n", start, end);
    return STATUS_OK;
}

}  // namespace aidl::vendor::lineage::health
