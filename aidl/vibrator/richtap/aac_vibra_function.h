/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <cstdint>

extern "C" {
int32_t aac_vibra_init(uint32_t* deviceType);
int32_t aac_vibra_off();
int32_t aac_vibra_setAmplitude(uint8_t amplitude);
int32_t aac_vibra_dynamic_scale(uint8_t scale);
int32_t aac_vibra_setting_f0(int32_t deviceIndex, int32_t f0);
}

void aac_vibra_looper_start();
int32_t aac_vibra_looper_on(uint32_t timeoutMs);
int32_t aac_vibra_looper_prebaked_effect(uint32_t effectId, int32_t strength);
int32_t aac_vibra_looper_envelope(const int32_t* envelope, uint32_t envelopeSize, bool fastFlag);
int32_t aac_vibra_looper_rtp(int32_t fd);
int32_t aac_vibra_looper_post(const int32_t* pattern, int32_t patternSize, int32_t intervalMs,
                              int32_t loopCount, int32_t amplitude, int32_t frequency);
bool aac_vibra_looper_performParam(int32_t interval, int32_t amplitude, int32_t frequency);
bool aac_vibra_looper_stopPerformHe();
