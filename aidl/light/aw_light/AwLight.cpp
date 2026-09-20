/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "AwLight.h"

#include <json/json.h>

#include <algorithm>
#include <cmath>
#include <fstream>
#include <functional>
#include <iterator>
#include <set>

namespace meizu::aw_light {
namespace {

bool number(const Json::Value& value, uint32_t maximum) {
    return value.isUInt() && value.asUInt() <= maximum;
}

bool array(const Json::Value& value, size_t minimum, size_t maximum) {
    return value.isArray() && value.size() >= minimum && value.size() <= maximum;
}

template <size_t N>
bool bytes(const Json::Value& value, std::array<uint8_t, N>* result, uint32_t maximum = 255) {
    if (!array(value, N, N)) return false;
    for (size_t i = 0; i < N; ++i) {
        if (!number(value[static_cast<Json::ArrayIndex>(i)], maximum)) return false;
        (*result)[i] = value[static_cast<Json::ArrayIndex>(i)].asUInt();
    }
    return true;
}

bool leds(const Json::Value& value, std::vector<uint8_t>* result) {
    if (!array(value, 1, 16)) return false;
    std::set<uint8_t> seen;
    for (const auto& index : value) {
        if (!number(index, 15) || !seen.insert(index.asUInt()).second) return false;
        result->push_back(index.asUInt());
    }
    return true;
}

bool palette(const Json::Value& value, std::vector<Color>* result) {
    if (!array(value, 1, 256)) return false;
    for (const auto& entry : value) {
        Color color;
        if (!bytes(entry, &color)) return false;
        result->push_back(color);
    }
    return true;
}

bool groups(const Json::Value& value, std::vector<Group>* result) {
    if (!array(value, 1, 16)) return false;
    for (const auto& entry : value) {
        if (!entry.isObject() || !number(entry["repeat"], 255) || !array(entry["steps"], 1, 255))
            return false;
        Group group{static_cast<uint16_t>(entry["repeat"].asUInt()), {}, {}};
        if (!leds(entry["leds"], &group.leds)) return false;
        for (const auto& step : entry["steps"]) {
            if (!array(step, 6, 6) || !number(step[0], 65535)) return false;
            for (Json::ArrayIndex i = 1; i < 6; ++i) {
                if (!number(step[i], 255)) return false;
            }
            group.steps.push_back({static_cast<uint16_t>(step[0].asUInt()),
                                   static_cast<uint8_t>(step[1].asUInt()),
                                   static_cast<uint8_t>(step[2].asUInt()),
                                   {static_cast<uint8_t>(step[3].asUInt()),
                                    static_cast<uint8_t>(step[4].asUInt()),
                                    static_cast<uint8_t>(step[5].asUInt())}});
        }
        result->push_back(std::move(group));
    }
    return true;
}

bool config(const Json::Value& value, Effect* effect) {
    if (!value.isObject()) return false;
    if (effect->model == 0) return effect->id == 66;
    if (effect->model == 1) {
        if (!number(value["direction"], 2) || value["direction"].asUInt() == 0 ||
            !number(value["repeat"], 255) || !number(value["stepMs"], 65535) ||
            !palette(value["palette"], &effect->palette) || effect->palette.size() > 16) {
            return false;
        }
        effect->direction = value["direction"].asUInt();
        effect->repeat = value["repeat"].asUInt();
        effect->milliseconds = value["stepMs"].asUInt();
        return true;
    }
    if (effect->model != 4 && effect->model != 8 && effect->model != 9) return false;
    if (!groups(value["groups"], &effect->groups)) return false;
    // The current stock corpus has no base-color modulation for these effects.
    if (!value.isMember("base") || !value["base"].isNull()) return false;
    if (effect->model == 4) return true;
    if (!array(value["colors"], 1, 1)) return false;
    for (const auto& entry : value["colors"]) {
        if (!entry.isObject() || !number(entry["repeat"], 65535) || !number(entry["stepMs"], 65535))
            return false;
        ColorSequence color{static_cast<uint16_t>(entry["repeat"].asUInt()),
                            static_cast<uint16_t>(entry["stepMs"].asUInt()),
                            {},
                            {}};
        if (!leds(entry["leds"], &color.leds) || !palette(entry["palette"], &color.palette) ||
            color.palette.size() < 2)
            return false;
        effect->colors.push_back(std::move(color));
    }
    if (effect->model == 9) {
        if (effect->groups.size() != 16 || effect->colors[0].repeat != 0 ||
            effect->colors[0].palette.size() != 16 || effect->colors[0].milliseconds != 400)
            return false;
        for (size_t i = 0; i < 16; ++i) {
            const auto& group = effect->groups[i];
            if (group.leds.size() != 1 || group.leds[0] != i ||
                (effect->id == 37 && group.steps.size() < 3))
                return false;
        }
        switch (effect->id) {
            case 34:
            case 35:
            case 36:
            case 37:
            case 39:
            case 40:
            case 48:
            case 49:
                break;
            default:
                return false;
        }
    }
    return true;
}

uint32_t steps(uint32_t milliseconds) {
    return (milliseconds + 19) / 20;
}

bool hardware(int id) {
    return id == 42 || id == 66;
}

bool liveMusic(int id) {
    return id == 38 || id == 50 || id == 64;
}

}  // namespace

std::shared_ptr<const Catalog> Catalog::load(const std::string& path, std::string* error) {
    auto fail = [&](const char* message) -> std::shared_ptr<const Catalog> {
        if (error) *error = message;
        return nullptr;
    };
    std::ifstream file(path, std::ios::binary);
    if (!file) return fail("Cannot open effects");
    std::string input(512 * 1024 + 1, '\0');
    file.read(input.data(), input.size());
    input.resize(file.gcount());
    if (file.bad() || input.size() > 512 * 1024) return fail("Invalid effects size");
    Json::CharReaderBuilder builder;
    builder["rejectDupKeys"] = true;
    builder["failIfExtra"] = true;
    builder["allowComments"] = false;
    builder["stackLimit"] = 64;
    std::unique_ptr<Json::CharReader> reader(builder.newCharReader());
    Json::Value root;
    std::string parseError;
    if (!reader->parse(input.data(), input.data() + input.size(), &root, &parseError) ||
        !root.isObject() || !number(root["version"], 1) || root["version"].asUInt() != 1) {
        return fail("Invalid effects document");
    }
    auto result = std::shared_ptr<Catalog>(new Catalog);
    if (!bytes(root["gamma"], &result->mGamma) || result->mGamma.front() != 0 ||
        result->mGamma.back() != 255 ||
        std::adjacent_find(result->mGamma.begin(), result->mGamma.end(),
                           std::greater_equal<uint8_t>()) != result->mGamma.end() ||
        !bytes(root["frameMap"], &result->mFrameMap, 95))
        return fail("Invalid lookup tables");
    std::set<uint8_t> seen;
    for (size_t i = 0; i < result->mFrameMap.size(); ++i) {
        auto target = result->mFrameMap[i];
        if ((target & 1) != (i & 1) || !seen.insert(target).second) {
            return fail("Invalid frame permutation");
        }
    }
    if (!array(root["letters"], 26, 26) || !array(root["musicPalettes"], 6, 6) ||
        !array(root["hardwarePresets"], 10, 10) || !array(root["effects"], 1, 128)) {
        return fail("Invalid catalog sizes");
    }
    for (Json::ArrayIndex i = 0; i < 26; ++i) {
        if (!bytes(root["letters"][i], &result->mLetters[i], 15)) return fail("Invalid letter");
    }
    for (Json::ArrayIndex i = 0; i < 6; ++i) {
        if (!array(root["musicPalettes"][i], 16, 16)) return fail("Invalid music palette");
        for (Json::ArrayIndex j = 0; j < 16; ++j) {
            if (!bytes(root["musicPalettes"][i][j], &result->mMusicPalettes[i][j])) {
                return fail("Invalid music color");
            }
        }
    }
    for (const auto& preset : root["hardwarePresets"]) {
        Color color;
        if (!preset.isObject() || !bytes(preset["rgb"], &color) || !number(preset["effect"], 15) ||
            preset["effect"].asUInt() < 6 ||
            !result->mHardwarePresets.emplace(color, preset["effect"].asUInt()).second) {
            return fail("Invalid hardware preset");
        }
    }
    for (const auto& entry : root["effects"]) {
        if (!entry.isObject() || !number(entry["id"], 255) || entry["id"].asUInt() == 0 ||
            !number(entry["next"], 255) || !number(entry["model"], 13) ||
            !number(entry["duration"], 600000)) {
            return fail("Invalid effect header");
        }
        Effect effect;
        effect.id = entry["id"].asUInt();
        effect.next = entry["next"].asUInt();
        effect.model = entry["model"].asUInt();
        effect.duration = entry["duration"].asUInt();
        if (!config(entry["config"], &effect) ||
            !result->mEffects.emplace(effect.id, std::move(effect)).second) {
            return fail("Invalid or duplicate effect");
        }
    }
    for (const auto& [id, effect] : result->mEffects) {
        std::set<int> chain;
        const Effect* node = &effect;
        while (node) {
            if (!chain.insert(node->id).second) return fail("Cyclic successor graph");
            if (!node->next) break;
            node = result->find(node->next);
            if (!node) return fail("Missing successor");
        }
    }
    if (error) error->clear();
    return result;
}

const Effect* Catalog::find(int id) const {
    auto it = mEffects.find(id);
    return it == mEffects.end() ? nullptr : &it->second;
}

int Catalog::hardwarePreset(const Color& color) const {
    auto it = mHardwarePresets.find(color);
    return it == mHardwarePresets.end() ? -1 : it->second;
}

uint8_t Catalog::interpolate(uint32_t samples, uint32_t tick, uint8_t start, uint8_t end) const {
    if (tick == 0) return start;
    if (samples <= 1 || tick >= samples - 1) return end;
    auto lower = [&](uint8_t value) {
        return std::min<int>(
                63, std::lower_bound(mGamma.begin(), mGamma.end(), value) - mGamma.begin());
    };
    auto upper = [&](uint8_t value) {
        return std::max<int>(
                0, std::upper_bound(mGamma.begin(), mGamma.end(), value) - mGamma.begin() - 1);
    };
    int low = lower(std::min(start, end));
    int high = upper(std::max(start, end));
    int delta = static_cast<int>((static_cast<int64_t>(high - low) * tick) / (samples - 1));
    int index = end < start ? high - delta : low + delta;
    return mGamma[std::clamp(index, 0, 63)];
}

std::array<uint8_t, 96> Catalog::encode(const Frame& frame) const {
    std::array<uint8_t, 96> wire{};
    for (size_t i = 0; i < frame.size(); ++i) {
        for (size_t c = 0; c < 3; ++c) {
            uint8_t color = frame[i].color[c];
            wire[mFrameMap[6 * i + 2 * c]] = color ? std::max(1, color >> 2) : 0;
            wire[mFrameMap[6 * i + 2 * c + 1]] = std::min<uint8_t>(60, frame[i].brightness);
        }
    }
    return wire;
}

AwLight::AwLight(std::shared_ptr<const Catalog> catalog) : mCatalog(std::move(catalog)) {}

bool AwLight::supports(int id) const {
    const auto* effect = mCatalog ? mCatalog->find(id) : nullptr;
    while (effect) {
        if (!effect->next) return true;
        effect = mCatalog->find(effect->next);
    }
    return false;
}

bool AwLight::validOptions(int id, const Options& options) const {
    if (!std::isfinite(options.strength) || options.strength < 0 || options.strength > 1)
        return false;
    if (options.progress > (id == 49 ? 18 : 100)) return false;
    if (hardware(id) && (mCatalog->hardwarePreset(options.color) < 0 ||
                         (options.strength != 0 && options.strength != 1)))
        return false;
    unsigned char letter = options.letter;
    return id != 62 || letter == 0 || letter == ' ' || (letter >= 'A' && letter <= 'Z') ||
           (letter >= 'a' && letter <= 'z');
}

bool AwLight::start(int id, const Options& options) {
    if (!supports(id) || !validOptions(id, options)) return false;
    mOptions = options;
    select(mCatalog->find(id));
    return true;
}

void AwLight::select(const Effect* effect) {
    mEffect = effect;
    mAdvance = false;
    mFrame = {};
    mRotation = {};
    mPhase = 0;
    mGroups.assign(effect ? effect->groups.size() : 0, {});
    mColors.assign(effect ? effect->colors.size() : 0, {});
    mProgressGroups.clear();
    if (effect && effect->model == 9) patchProgress();
    mAmplitude = 0;
    mDecay = mOptions.amplitude < 6;
    mMusicCounter = 0;
    mMorph = false;
    if (effect && effect->id == 38) mMusicPalette = mCatalog->mMusicPalettes[mMusicPhase];
}

bool AwLight::update(const Options& options) {
    if (!mEffect || !validOptions(mEffect->id, options)) return false;
    mDecay = static_cast<int>(options.amplitude) - mAmplitude < 6;
    mOptions = options;
    return true;
}

void AwLight::stop() {
    select(nullptr);
}

bool AwLight::isHeld() const {
    return hardware(mEffect->id) || mEffect->id == 47 || mEffect->id == 62;
}

bool AwLight::advanceGroups() {
    bool active = false;
    bool lastWrapped = false;
    for (size_t i = 0; i < mGroups.size(); ++i) {
        auto& state = mGroups[i];
        const auto& group = mEffect->model == 9 ? mProgressGroups[i] : mEffect->groups[i];
        if (state.done) continue;
        if (++state.tick >= std::max(1u, steps(group.steps[state.index].milliseconds))) {
            state.tick = 0;
            if (++state.index == group.steps.size()) {
                if (i + 1 == mGroups.size()) lastWrapped = true;
                if (group.repeat && ++state.repeats >= group.repeat) {
                    state.done = true;
                    continue;
                }
                state.index = 0;
                if (!group.repeat) state.repeats = 0;
            }
        }
        active = true;
    }
    if (mEffect->model == 9 && lastWrapped && mAppliedPhase != progressPhase()) patchProgress();
    return active;
}

size_t AwLight::progressPhase() const {
    unsigned p = mOptions.progress;
    if (mEffect->id == 49) return p == 0 ? 0 : p <= 2 ? 1 : p <= 9 ? p - 1 : p - 2;
    if (mEffect->id == 34 || mEffect->id == 35 || mEffect->id == 37 || mEffect->id == 48) {
        return 16 * p / 100 - (p > 6 ? 1 : 0);
    }
    return p < 91 ? p / 6 : 15;
}

void AwLight::patchProgress() {
    mAppliedPhase = progressPhase();
    mProgressGroups = mEffect->groups;
    for (size_t i = 0; i < mProgressGroups.size(); ++i) {
        auto& group = mProgressGroups[i];
        switch (mEffect->id) {
            case 34:
                for (auto& step : group.steps) step.start = step.end = 20;
                break;
            case 35:
                group.steps.back().color =
                        i <= mAppliedPhase ? Color{0, 127, 0} : Color{255, 255, 255};
                break;
            case 37:
                for (auto& step : group.steps) step.start = step.end = 20;
                if (i < mAppliedPhase) {
                    group.steps[1].end = 255;
                    group.steps[2].start = 255;
                }
                break;
            case 48:
                group.steps[0].start = 20;
                group.steps[0].end = 0;
                group.steps[0].color = i <= mAppliedPhase ? Color{0, 127, 0} : Color{255, 255, 255};
                break;
            case 49:
                group.steps[0].start = group.steps[0].end = i < mAppliedPhase ? 100 : 0;
                break;
        }
    }
}

bool AwLight::advanceColors() {
    bool active = false;
    for (size_t i = 0; i < mColors.size(); ++i) {
        auto& state = mColors[i];
        const auto& color = mEffect->colors[i];
        if (state.done) continue;
        if (++state.tick >= steps(color.milliseconds) + 1) {
            state.tick = 0;
            state.index = (state.index + 1) % color.palette.size();
            if (state.index == color.palette.size() - 1 && color.repeat &&
                ++state.repeats >= color.repeat)
                state.done = true;
        }
        active |= !state.done;
    }
    return active;
}

bool AwLight::advance() {
    if (mEffect->model == 1) {
        if (++mRotation.tick < steps(mEffect->milliseconds) + 1) return true;
        mRotation.tick = 0;
        size_t count = mEffect->palette.size();
        mPhase = (mPhase + (mEffect->direction == 1 ? count - 1 : 1)) % count;
        return mPhase != 0 || !mEffect->repeat || ++mRotation.repeats < mEffect->repeat;
    }
    bool active = advanceGroups();
    if (mEffect->model == 8) active = advanceColors() && active;
    return active;
}

void AwLight::renderGroups() {
    for (size_t i = 0; i < mGroups.size(); ++i) {
        const auto& state = mGroups[i];
        const auto& group = mEffect->model == 9 ? mProgressGroups[i] : mEffect->groups[i];
        uint8_t brightness = 0;
        const auto& segment = group.steps[std::min(state.index, group.steps.size() - 1)];
        uint32_t count = steps(segment.milliseconds);
        if (!state.done && state.tick < count) {
            brightness = std::min<uint8_t>(
                    60, mCatalog->interpolate(count, state.tick, segment.start, segment.end));
        }
        for (auto led : group.leds) {
            mFrame[led].brightness = brightness;
            if (mEffect->model == 4) {
                mFrame[led].color = (mEffect->id == 41 || mEffect->id == 65 || mEffect->id == 68)
                                            ? mOptions.color
                                            : segment.color;
            } else if (mEffect->model == 9) {
                switch (mEffect->id) {
                    case 34:
                    case 37:
                        mFrame[led].color =
                                i <= progressPhase() ? Color{0, 128, 0} : Color{255, 255, 255};
                        break;
                    case 35:
                    case 36:
                    case 48:
                        mFrame[led].color = segment.color;
                        break;
                    case 49:
                        mFrame[led].color = mOptions.progress == 0 ? Color{}
                                            : i < progressPhase()  ? Color{255, 255, 255}
                                                                   : Color{100, 68, 60};
                        break;
                    default:
                        mFrame[led].color = mEffect->colors[0].palette[i];
                        break;
                }
            }
        }
    }
}

void AwLight::renderColors() {
    for (size_t i = 0; i < mColors.size(); ++i) {
        const auto& state = mColors[i];
        const auto& sequence = mEffect->colors[i];
        const auto& start = sequence.palette[state.index];
        const auto& end = sequence.palette[(state.index + 1) % sequence.palette.size()];
        Color color;
        for (size_t c = 0; c < 3; ++c) {
            color[c] = start[c] == end[c] ? start[c]
                                          : mCatalog->interpolate(steps(sequence.milliseconds) + 1,
                                                                  state.tick, start[c], end[c]);
        }
        for (auto led : sequence.leds) mFrame[led].color = color;
    }
}

void AwLight::updateMusicPalette() {
    if (mMusicCounter <= 1000) {
        ++mMusicCounter;
    } else {
        mMusicCounter = 0;
        mMusicPhase = (mMusicPhase + 1) % 6;
        mMorph = true;
    }
    if (!mMorph) return;
    if (mMusicCounter >= 51) {
        mMorph = false;
        mMusicCounter = 0;
        return;
    }
    if (!mMusicCounter || mMusicCounter % 2) return;
    const auto& source = mCatalog->mMusicPalettes[(mMusicPhase + 5) % 6];
    const auto& target = mCatalog->mMusicPalettes[mMusicPhase];
    for (size_t i = 0; i < 16; ++i) {
        for (size_t c = 0; c < 3; ++c) {
            int delta = static_cast<int>(target[i][c]) - source[i][c];
            int sign = delta > 0 ? 1 : -1;
            int distance = std::abs(delta);
            if (!distance) continue;
            if (distance <= 25) {
                if (mMusicPalette[i][c] != target[i][c]) mMusicPalette[i][c] += sign;
            } else if (mMusicCounter <= 49) {
                mMusicPalette[i][c] += sign * (distance / 25);
            } else {
                mMusicPalette[i][c] = target[i][c];
            }
        }
    }
}

void AwLight::renderColorful() {
    uint8_t brightness = 60;
    if (liveMusic(mEffect->id)) {
        if (!mDecay) {
            mAmplitude = mOptions.amplitude;
        } else {
            int difference = static_cast<int>(mAmplitude) - mOptions.amplitude;
            if (difference > 0 && mAmplitude >= 6) {
                int decay = difference < 10   ? 1
                            : difference < 20 ? 2
                            : difference < 35 ? 3
                            : difference < 50 ? 4
                                              : 5;
                mAmplitude -= decay;
            }
        }
        if (mAmplitude <= 2) mAmplitude = 5;
        brightness = std::min<uint8_t>(60, mAmplitude);
    }
    size_t count = mEffect->palette.size();
    auto colorAt = [&](size_t index) -> Color {
        if (mEffect->id == 38) return mMusicPalette[index];
        if (mEffect->id == 50 || mEffect->id == 64) return mOptions.color;
        return mEffect->palette[index];
    };
    for (size_t i = 0; i < 16; ++i) {
        if (i >= count) {
            mFrame[i].brightness = 0;
            continue;
        }
        size_t index = (i + mPhase) % count;
        auto start = colorAt(index);
        auto end = colorAt((index + (mEffect->direction == 1 ? count - 1 : 1)) % count);
        for (size_t c = 0; c < 3; ++c) {
            mFrame[i].color[c] = start[c] == end[c]
                                         ? start[c]
                                         : mCatalog->interpolate(steps(mEffect->milliseconds) + 1,
                                                                 mRotation.tick, start[c], end[c]);
        }
        mFrame[i].brightness = brightness;
    }
    if (mEffect->id == 38) updateMusicPalette();
}

bool AwLight::next(Output* output) {
    if (!output) return false;
    *output = {};
    if (!mEffect) return false;
    if (mOptions.strength == 0) return true;
    if (mAdvance && !isHeld() && !advance()) {
        const auto* next = mCatalog->find(mEffect->next);
        if (!next || !validOptions(next->id, mOptions)) {
            stop();
            return false;
        }
        select(next);
    }
    mAdvance = true;
    if (hardware(mEffect->id)) {
        output->type = Output::Type::Hardware;
        output->hardwarePreset = mCatalog->hardwarePreset(mOptions.color);
        return true;
    }
    if (mEffect->id == 47 || mEffect->id == 62) {
        for (auto& pixel : mFrame) pixel = {mOptions.color, 0};
        if (mEffect->id == 47) {
            for (auto& pixel : mFrame) pixel.brightness = 60;
        } else {
            unsigned char letter = mOptions.letter;
            if (letter >= 'a' && letter <= 'z') letter -= 'a' - 'A';
            if (letter >= 'A' && letter <= 'Z') {
                for (auto led : mCatalog->mLetters[letter - 'A']) mFrame[led].brightness = 60;
            }
        }
    } else if (mEffect->model == 1) {
        renderColorful();
    } else {
        if (mEffect->model == 8) renderColors();
        renderGroups();
    }
    output->type = Output::Type::Frame;
    output->frame = mFrame;
    for (auto& pixel : output->frame) {
        pixel.brightness = std::lround(pixel.brightness * mOptions.strength);
    }
    output->delayMs = isHeld() ? 0 : mEffect->id == 38 ? 5 : 20;
    return true;
}

}  // namespace meizu::aw_light
