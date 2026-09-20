/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "Aw20072.h"

#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

namespace meizu::aw_light {

Aw20072::Aw20072(std::shared_ptr<const Catalog> catalog, std::string path)
    : mCatalog(std::move(catalog)), mPath(std::move(path)) {}

Aw20072::~Aw20072() {
    if (mOwnsOutput) stop();
    closeFrame();
}

void Aw20072::closeFrame() {
    if (mFrameFd >= 0) close(mFrameFd);
    mFrameFd = -1;
}

bool Aw20072::pattern(int effect) {
    int fd = TEMP_FAILURE_RETRY(
            open((mPath + "/effect").c_str(), O_WRONLY | O_CLOEXEC | O_NOFOLLOW));
    if (fd < 0) return false;
    auto value = std::to_string(effect) + "\n";
    ssize_t count = TEMP_FAILURE_RETRY(write(fd, value.data(), value.size()));
    int saved = errno;
    close(fd);
    errno = count >= 0 && static_cast<size_t>(count) != value.size() ? EIO : saved;
    return count == static_cast<ssize_t>(value.size());
}

bool Aw20072::stop() {
    bool success = pattern(0);
    if (success) {
        mOwnsOutput = false;
        mType = Output::Type::Off;
    }
    return success;
}

bool Aw20072::apply(const Output& output) {
    if (!mCatalog) {
        errno = EINVAL;
        return false;
    }
    if (output.type == Output::Type::Off) return stop();
    bool success;
    if (output.type == Output::Type::Hardware) {
        if (output.hardwarePreset < 6 || output.hardwarePreset > 15) {
            errno = EINVAL;
            return false;
        }
        if (mOwnsOutput && mType == output.type && mPreset == output.hardwarePreset) return true;
        success = pattern(output.hardwarePreset);
        if (success) mPreset = output.hardwarePreset;
    } else if (output.type == Output::Type::Frame) {
        const auto wire = mCatalog->encode(output.frame);
        if (mOwnsOutput && mType == output.type && wire == mLastFrame) return true;
        if (mFrameFd < 0) {
            mFrameFd = TEMP_FAILURE_RETRY(
                    open((mPath + "/frame").c_str(), O_WRONLY | O_CLOEXEC | O_NOFOLLOW));
        }
        if (mFrameFd < 0) return false;
        ssize_t count = TEMP_FAILURE_RETRY(pwrite(mFrameFd, wire.data(), wire.size(), 0));
        success = count == static_cast<ssize_t>(wire.size());
        if (count >= 0 && !success) errno = EIO;
        if (success) mLastFrame = wire;
    } else {
        errno = EINVAL;
        return false;
    }
    if (!success) {
        int saved = errno;
        closeFrame();
        stop();
        mType = Output::Type::Off;
        errno = saved;
        return false;
    }
    mType = output.type;
    mOwnsOutput = true;
    return true;
}

}  // namespace meizu::aw_light
