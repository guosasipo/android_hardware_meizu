/*
 * SPDX-FileCopyrightText: 2019 The Android Open Source Project
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "Sensor.h"

#include <android/hardware/sensors/1.0/types.h>
#include <fcntl.h>
#include <linux/input.h>
#include <log/log.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <utils/SystemClock.h>

#include <cerrno>
#include <cstring>
#include <string>

namespace android::hardware::sensors::V2_1::subhal::implementation {
namespace {

using ::android::hardware::sensors::V1_0::SensorFlagBits;
using ::android::hardware::sensors::V2_1::SensorType;

constexpr char kInputDeviceName[] = "main_touch";
constexpr int32_t kSensorType = static_cast<int32_t>(SensorType::DEVICE_PRIVATE_BASE) + 100;
constexpr int64_t kFodTapMaxTimeUs = 250000;
constexpr int64_t kFodDoubleTapMinTimeUs = 40000;
constexpr int64_t kFodDoubleTapMaxTimeUs = 400000;

int openInputDevice() {
    for (int index = 0; index < 64; index++) {
        std::string path = "/dev/input/event" + std::to_string(index);
        int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) {
            continue;
        }

        char name[80] = {};
        if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) >= 0 && strcmp(name, kInputDeviceName) == 0) {
            return fd;
        }
        close(fd);
    }

    ALOGE("Unable to find input device %s", kInputDeviceName);
    return -1;
}

bool readGestureEnabled(const char* path, bool* enabled) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        ALOGE("Unable to open %s: %s", path, strerror(errno));
        return false;
    }

    char buffer[16] = {};
    ssize_t size = read(fd, buffer, sizeof(buffer) - 1);
    int savedErrno = errno;
    close(fd);
    if (size <= 0) {
        ALOGE("Unable to read %s: %s", path, size < 0 ? strerror(savedErrno) : "empty value");
        return false;
    }

    if (buffer[0] == '1' || strncmp(buffer, "enable", 6) == 0) {
        *enabled = true;
        return true;
    }
    if (buffer[0] == '0' || strncmp(buffer, "disable", 7) == 0) {
        *enabled = false;
        return true;
    }
    ALOGE("Invalid gesture state: %s", buffer);
    return false;
}

bool writeGestureEnabled(const char* path, bool enabled) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        ALOGE("Unable to open %s: %s", path, strerror(errno));
        return false;
    }

    const char value = enabled ? '1' : '0';
    ssize_t size = write(fd, &value, sizeof(value));
    close(fd);
    if (size != static_cast<ssize_t>(sizeof(value))) {
        ALOGE("Unable to write %s: %zd bytes", path, size);
        return false;
    }
    return true;
}

}  // namespace

TapSensor::TapSensor(int32_t sensorHandle, ISensorsEventCallback* callback, const char* name,
                     const char* type, const char* gesturePath, uint16_t eventCode)
    : mCallback(callback), mGesturePath(gesturePath), mEventCode(eventCode) {
    mSensorInfo.sensorHandle = sensorHandle;
    mSensorInfo.name = name;
    mSensorInfo.vendor = "The LineageOS Project";
    mSensorInfo.version = 1;
    mSensorInfo.type = static_cast<SensorType>(kSensorType + sensorHandle);
    mSensorInfo.typeAsString = type;
    mSensorInfo.maxRange = 1.0f;
    mSensorInfo.resolution = 1.0f;
    mSensorInfo.power = 0.0f;
    mSensorInfo.minDelay = -1;
    mSensorInfo.maxDelay = 0;
    mSensorInfo.fifoReservedEventCount = 0;
    mSensorInfo.fifoMaxEventCount = 0;
    mSensorInfo.requiredPermission = "";
    mSensorInfo.flags = static_cast<uint32_t>(SensorFlagBits::ONE_SHOT_MODE) |
                        static_cast<uint32_t>(SensorFlagBits::WAKE_UP);

    if (pipe2(mWakePipe, O_CLOEXEC | O_NONBLOCK) < 0) {
        ALOGE("Unable to initialize tap sensor: %s", strerror(errno));
        return;
    }

    mInputFd = openInputDevice();
    mThread = std::thread(&TapSensor::run, this);
}

TapSensor::~TapSensor() {
    {
        std::lock_guard<std::mutex> lock(mStateMutex);
        if (mEnabled) {
            updateGestureEnabled(false);
            mEnabled = false;
        }
        resetFodTap();
        mStopThread = true;
    }
    mStateCondition.notify_all();
    wakeThread();
    if (mThread.joinable()) {
        mThread.join();
    }
    if (mWakePipe[0] >= 0) {
        close(mWakePipe[0]);
    }
    if (mWakePipe[1] >= 0) {
        close(mWakePipe[1]);
    }
    {
        std::lock_guard<std::mutex> lock(mInputMutex);
        if (mInputFd >= 0) {
            close(mInputFd);
            mInputFd = -1;
        }
    }
}

bool TapSensor::opened() const {
    return mWakePipe[0] >= 0 && mWakePipe[1] >= 0;
}

const SensorInfo& TapSensor::getSensorInfo() const {
    return mSensorInfo;
}

Result TapSensor::activate(bool enable) {
    std::lock_guard<std::mutex> lock(mStateMutex);
    if (mEnabled == enable) {
        return Result::OK;
    }
    if (enable) {
        readInputEvents();
        resetFodTap();
    }
    if (!updateGestureEnabled(enable)) {
        return Result::INVALID_OPERATION;
    }

    mEnabled = enable;
    if (!enable) {
        resetFodTap();
    }
    mStateCondition.notify_all();
    if (!enable) {
        wakeThread();
    }
    return Result::OK;
}

Result TapSensor::batch(int64_t /* samplingPeriodNs */) {
    return Result::OK;
}

Result TapSensor::flush() {
    return Result::BAD_VALUE;
}

Result TapSensor::injectEvent(const Event& /* event */) {
    return Result::INVALID_OPERATION;
}

void TapSensor::setOperationMode(OperationMode mode) {
    {
        std::lock_guard<std::mutex> lock(mStateMutex);
        mMode = mode;
    }
    mStateCondition.notify_all();
    wakeThread();
}

bool TapSensor::updateGestureEnabled(bool enable) {
    bool current;
    if (!readGestureEnabled(mGesturePath.c_str(), &current)) {
        return false;
    }
    return current == enable || writeGestureEnabled(mGesturePath.c_str(), enable);
}

int TapSensor::getInputFd() const {
    std::lock_guard<std::mutex> lock(mInputMutex);
    return mInputFd;
}

void TapSensor::resetFodTap() {
    std::lock_guard<std::mutex> lock(mInputMutex);
    resetFodTapLocked();
}

void TapSensor::resetFodTapLocked() {
    mFodPressed = false;
    mFodDownTimeUs = 0;
    mFodTapTimeUs = 0;
    mInputSyncDropped = false;
}

bool TapSensor::reopenInputDevice() {
    std::lock_guard<std::mutex> lock(mInputMutex);
    if (mInputFd >= 0) {
        close(mInputFd);
        mInputFd = -1;
    }
    mInputFd = openInputDevice();
    resetFodTapLocked();
    return mInputFd >= 0;
}

bool TapSensor::readInputEvents() {
    std::lock_guard<std::mutex> lock(mInputMutex);
    if (mInputFd < 0) {
        return false;
    }
    input_event events[32];
    bool gestureDetected = false;

    while (true) {
        ssize_t size = read(mInputFd, events, sizeof(events));
        if (size < 0) {
            if (errno == EAGAIN) {
                break;
            }
            ALOGE("Unable to read %s: %s", kInputDeviceName, strerror(errno));
            break;
        }
        if (size == 0) {
            break;
        }

        size_t count = static_cast<size_t>(size) / sizeof(input_event);
        for (size_t index = 0; index < count; index++) {
            const input_event& event = events[index];
            int64_t eventTimeUs =
                    static_cast<int64_t>(event.time.tv_sec) * 1000000 + event.time.tv_usec;

            if (event.type == EV_SYN) {
                if (event.code == SYN_DROPPED) {
                    resetFodTapLocked();
                    mInputSyncDropped = true;
                } else if (event.code == SYN_REPORT && mInputSyncDropped) {
                    mInputSyncDropped = false;
                }
                continue;
            }
            if (mInputSyncDropped) {
                continue;
            }

            if (event.type == EV_KEY && event.code == mEventCode && event.value == 1) {
                gestureDetected = true;
            } else if (event.type == EV_KEY && event.code == BTN_TOUCH) {
                if (event.value == 1 && !mFodPressed) {
                    mFodPressed = true;
                    mFodDownTimeUs = eventTimeUs;
                } else if (event.value == 0 && mFodPressed) {
                    int64_t durationUs = eventTimeUs - mFodDownTimeUs;
                    mFodPressed = false;
                    if (durationUs < 0 || durationUs > kFodTapMaxTimeUs) {
                        mFodTapTimeUs = 0;
                    } else if (mFodTapTimeUs == 0) {
                        mFodTapTimeUs = eventTimeUs;
                    } else {
                        int64_t intervalUs = eventTimeUs - mFodTapTimeUs;
                        mFodTapTimeUs = eventTimeUs;
                        if (intervalUs >= kFodDoubleTapMinTimeUs &&
                            intervalUs <= kFodDoubleTapMaxTimeUs) {
                            mFodTapTimeUs = 0;
                            gestureDetected = true;
                        }
                    }
                }
            }
        }
    }

    return gestureDetected;
}

void TapSensor::run() {
    while (true) {
        {
            std::unique_lock<std::mutex> lock(mStateMutex);
            mStateCondition.wait(lock, [this] {
                return mStopThread || (mEnabled && mMode == OperationMode::NORMAL);
            });
            if (mStopThread) {
                break;
            }
        }

        int inputFd = getInputFd();
        if (inputFd < 0 && !reopenInputDevice()) {
            pollfd wakeFd = {.fd = mWakePipe[0], .events = POLLIN, .revents = 0};
            poll(&wakeFd, 1, 1000);
            if (wakeFd.revents & POLLIN) {
                char values[16];
                while (read(mWakePipe[0], values, sizeof(values)) > 0) {
                }
            }
            continue;
        }
        inputFd = getInputFd();

        pollfd fds[] = {
                {.fd = mWakePipe[0], .events = POLLIN, .revents = 0},
                {.fd = inputFd, .events = POLLIN, .revents = 0},
        };

        int result = poll(fds, 2, -1);
        if (result < 0) {
            if (errno == EINTR) {
                continue;
            }
            ALOGE("Input poll failed: %s", strerror(errno));
            return;
        }

        if (fds[0].revents & POLLIN) {
            char values[16];
            while (read(mWakePipe[0], values, sizeof(values)) > 0) {
            }
        }
        if (mStopThread) {
            break;
        }
        if (fds[1].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            ALOGW("Input device disconnected, reopening");
            reopenInputDevice();
            continue;
        }
        if (!(fds[1].revents & POLLIN) || !readInputEvents()) {
            continue;
        }

        {
            std::lock_guard<std::mutex> lock(mStateMutex);
            if (!mEnabled || mMode != OperationMode::NORMAL) {
                resetFodTap();
                continue;
            }
            if (!updateGestureEnabled(false)) {
                ALOGE("Unable to disable tap gesture after one-shot event");
                continue;
            }
            mEnabled = false;
            resetFodTap();
        }

        Event event = {};
        event.sensorHandle = mSensorInfo.sensorHandle;
        event.sensorType = mSensorInfo.type;
        event.timestamp = ::android::elapsedRealtimeNano();
        event.u.data[0] = 1.0f;
        mCallback->postEvents({event}, true);
    }
}

void TapSensor::wakeThread() {
    if (mWakePipe[1] < 0) {
        return;
    }
    char value = 1;
    write(mWakePipe[1], &value, sizeof(value));
}

}  // namespace android::hardware::sensors::V2_1::subhal::implementation
