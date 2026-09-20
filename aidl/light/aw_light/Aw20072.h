/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include "AwLight.h"

namespace meizu::aw_light {

class Aw20072 {
  public:
    explicit Aw20072(std::shared_ptr<const Catalog> catalog,
                     std::string path = "/sys/class/leds/aw20072_led");
    ~Aw20072();
    Aw20072(const Aw20072&) = delete;
    Aw20072& operator=(const Aw20072&) = delete;
    bool apply(const Output& output);
    bool stop();

  private:
    bool pattern(int effect);
    void closeFrame();

    std::shared_ptr<const Catalog> mCatalog;
    const std::string mPath;
    int mFrameFd = -1;
    bool mOwnsOutput = false;
    Output::Type mType = Output::Type::Off;
    int mPreset = 0;
    std::array<uint8_t, 96> mLastFrame{};
};

}  // namespace meizu::aw_light
