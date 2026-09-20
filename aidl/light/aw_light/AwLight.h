/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <array>
#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace meizu::aw_light {

using Color = std::array<uint8_t, 3>;
struct Pixel {
    Color color{};
    uint8_t brightness = 0;
};
using Frame = std::array<Pixel, 16>;

struct Segment {
    uint16_t milliseconds;
    uint8_t start;
    uint8_t end;
    Color color;
};
struct Group {
    uint16_t repeat;
    std::vector<uint8_t> leds;
    std::vector<Segment> steps;
};
struct ColorSequence {
    uint16_t repeat;
    uint16_t milliseconds;
    std::vector<uint8_t> leds;
    std::vector<Color> palette;
};
struct Effect {
    int id = 0;
    int next = 0;
    int model = 0;
    uint32_t duration = 0;
    int direction = 1;
    uint16_t repeat = 0;
    uint16_t milliseconds = 0;
    std::vector<Color> palette;
    std::vector<Group> groups;
    std::vector<ColorSequence> colors;
};

class Catalog {
  public:
    static std::shared_ptr<const Catalog> load(const std::string& path, std::string* error);
    const Effect* find(int id) const;
    int hardwarePreset(const Color& color) const;
    uint8_t interpolate(uint32_t samples, uint32_t tick, uint8_t start, uint8_t end) const;
    std::array<uint8_t, 96> encode(const Frame& frame) const;

  private:
    friend class AwLight;
    std::array<uint8_t, 64> mGamma{};
    std::array<uint8_t, 96> mFrameMap{};
    std::array<std::array<uint8_t, 2>, 26> mLetters{};
    std::array<std::array<Color, 16>, 6> mMusicPalettes{};
    std::map<Color, int> mHardwarePresets;
    std::map<int, Effect> mEffects;
};

struct Options {
    Color color{255, 255, 255};
    float strength = 1.0f;
    uint8_t amplitude = 0;
    uint8_t progress = 0;
    char letter = '\0';
};

struct Output {
    enum class Type { Off, Hardware, Frame };
    Type type = Type::Off;
    int hardwarePreset = 0;
    Frame frame{};
    // Zero means the output is held by hardware until the next input or cancellation.
    uint32_t delayMs = 0;
};

// The owner serializes calls and owns timing, output and cancellation.
class AwLight {
  public:
    explicit AwLight(std::shared_ptr<const Catalog> catalog);
    bool supports(int id) const;
    bool start(int id, const Options& options = {});
    bool update(const Options& options);
    // Completion returns false with Off output; the owner must also stop the device.
    bool next(Output* output);
    void stop();

  private:
    struct Cursor {
        size_t index = 0;
        uint32_t tick = 0;
        uint32_t repeats = 0;
        bool done = false;
    };
    bool validOptions(int id, const Options& options) const;
    void select(const Effect* effect);
    bool advance();
    bool advanceGroups();
    bool advanceColors();
    void renderGroups();
    void renderColors();
    void renderColorful();
    void updateMusicPalette();
    size_t progressPhase() const;
    void patchProgress();
    bool isHeld() const;

    std::shared_ptr<const Catalog> mCatalog;
    const Effect* mEffect = nullptr;
    Options mOptions;
    Frame mFrame{};
    bool mAdvance = false;
    std::vector<Cursor> mGroups;
    std::vector<Cursor> mColors;
    std::vector<Group> mProgressGroups;
    size_t mAppliedPhase = 0;
    Cursor mRotation;
    size_t mPhase = 0;
    uint8_t mAmplitude = 0;
    bool mDecay = false;
    size_t mMusicPhase = 0;
    uint32_t mMusicCounter = 0;
    bool mMorph = false;
    std::array<Color, 16> mMusicPalette{};
};

}  // namespace meizu::aw_light
