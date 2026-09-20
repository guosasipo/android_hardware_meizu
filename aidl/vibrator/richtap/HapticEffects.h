/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <aidl/android/hardware/vibrator/CompositePrimitive.h>
#include <aidl/android/hardware/vibrator/Effect.h>

#include <array>
#include <cstddef>
#include <cstdint>

namespace aidl::android::hardware::vibrator::meizu::haptics {

// AAC format 3 retains up to 16 curve points in each 55-word event.
using Event = std::array<int32_t, 55>;

struct Pattern {
    const Event* events;
    size_t size;
    int32_t durationMs;
};

inline constexpr int32_t kFormat = 3;
inline constexpr size_t kMaxEvents = 16;

// D_click.he, B_homescreen_icon_snap.he, and 14_nav_multitask.he.
inline constexpr Event kClickEvents[] = {{0x1001, 0, 95, 85}};
inline constexpr Event kLightTickEvents[] = {{0x1001, 0, 80, 100}};
inline constexpr Event kLowTickEvents[] = {{0x1001, 0, 40, 35}};

// 3_desktop_long_press.he, 1_back.he, D_click.he, and 81_fingerprint_error.he.
inline constexpr Event kHeavyClickEvents[] = {{0x1001, 0, 100, 65}};
inline constexpr Event kThudEvents[] = {{0x1001, 0, 100, 45}};
inline constexpr Event kPopEvents[] = {{0x1001, 0, 95, 85}};
inline constexpr Event kDoubleClickEvents[] = {
        {0x1001, 5, 90, 70},
        {0x1001, 63, 90, 56},
};

// mEngine_demo.he events 76, 2, and 48, with their original curves.
inline constexpr Event kQuickRiseEvents[] = {{
        0x1000, 0, 65, 31, 200, 0, 5, 0, 0, 0, 88, 22, 0, 158, 27, -11, 199, 100, 21, 200, 0, 0,
}};
inline constexpr Event kSlowRiseEvents[] = {{
        0x1000, 0,  27,  15,  389, 0,   16,  0,  0,   15,  11, 10,  8,   94, 12,  3,   106, 20, 3,
        117,    19, 3,   152, 35,  4,   176, 28, 4,   235, 71, 4,   247, 66, 4,   270, 100, 4,  282,
        62,     5,  293, 100, 6,   305, 100, 7,  328, 100, 8,  375, 72,  8,  389, 0,   6,
}};
inline constexpr Event kQuickFallEvents[] = {{
        0x1000, 0, 56, 41, 38, 0, 4, 0, 0, 0, 1, 100, 6, 11, 31, -22, 38, 0, 0,
}};

inline constexpr Pattern kNoop{nullptr, 0, 0};
inline constexpr Pattern kClick{kClickEvents, 1, 16};
inline constexpr Pattern kLightTick{kLightTickEvents, 1, 8};
inline constexpr Pattern kLowTick{kLowTickEvents, 1, 23};
inline constexpr Pattern kHeavyClick{kHeavyClickEvents, 1, 15};
inline constexpr Pattern kThud{kThudEvents, 1, 24};
inline constexpr Pattern kPop{kPopEvents, 1, 16};
inline constexpr Pattern kDoubleClick{kDoubleClickEvents, 2, 87};
inline constexpr Pattern kQuickRise{kQuickRiseEvents, 1, 205};
inline constexpr Pattern kSlowRise{kSlowRiseEvents, 1, 395};
inline constexpr Pattern kQuickFall{kQuickFallEvents, 1, 42};

inline constexpr std::array kSupportedPrimitives = {
        CompositePrimitive::NOOP,       CompositePrimitive::CLICK,
        CompositePrimitive::QUICK_RISE, CompositePrimitive::SLOW_RISE,
        CompositePrimitive::QUICK_FALL, CompositePrimitive::LIGHT_TICK,
        CompositePrimitive::LOW_TICK,
};

inline constexpr const Pattern* getPattern(CompositePrimitive primitive) {
    switch (primitive) {
        case CompositePrimitive::NOOP:
            return &kNoop;
        case CompositePrimitive::CLICK:
            return &kClick;
        case CompositePrimitive::QUICK_RISE:
            return &kQuickRise;
        case CompositePrimitive::SLOW_RISE:
            return &kSlowRise;
        case CompositePrimitive::QUICK_FALL:
            return &kQuickFall;
        case CompositePrimitive::LIGHT_TICK:
            return &kLightTick;
        case CompositePrimitive::LOW_TICK:
            return &kLowTick;
        default:
            return nullptr;
    }
}

inline constexpr const Pattern* getPattern(Effect effect) {
    switch (effect) {
        case Effect::CLICK:
            return &kClick;
        case Effect::DOUBLE_CLICK:
            return &kDoubleClick;
        case Effect::TICK:
            return &kLightTick;
        case Effect::THUD:
            return &kThud;
        case Effect::POP:
            return &kPop;
        case Effect::HEAVY_CLICK:
            return &kHeavyClick;
        case Effect::TEXTURE_TICK:
            return &kLowTick;
        default:
            return nullptr;
    }
}

}  // namespace aidl::android::hardware::vibrator::meizu::haptics
