/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <cmath>

namespace android::hardware::sensors::V2_1::subhal::implementation::qsh_wrapper {

class RaiseGesture {
  public:
    void reset() { mHasBaseline = false; }

    bool update(float action) {
        if (!std::isfinite(action)) return false;
        if (!mHasBaseline) {
            mLastAction = action;
            mHasBaseline = true;
            return false;
        }
        const bool trigger =
                (action == 2.0f && mLastAction != 6.0f && mLastAction != 7.0f) || action == 6.0f;
        mLastAction = action;
        return trigger;
    }

  private:
    bool mHasBaseline = false;
    float mLastAction = 0.0f;
};

}  // namespace android::hardware::sensors::V2_1::subhal::implementation::qsh_wrapper
