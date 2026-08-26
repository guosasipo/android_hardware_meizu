/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package vendor.aac.hardware.richtap.vibrator;

import vendor.aac.hardware.richtap.vibrator.IRichtapCallback;

@VintfStability
interface IRichtapVibrator {
    oneway void init(in IRichtapCallback callback);
    oneway void setDynamicScale(in int scale, in IRichtapCallback callback);
    oneway void setF0(in int f0, in IRichtapCallback callback);
    oneway void stop(in IRichtapCallback callback);
    oneway void setAmplitude(in int amplitude, in IRichtapCallback callback);
    oneway void performHeParam(in int interval, in int amplitude, in int freq,
            in IRichtapCallback callback);
    oneway void off(in IRichtapCallback callback);
    oneway void on(in int timeoutMs, in IRichtapCallback callback);
    int perform(in int effect_id, in byte strength, in IRichtapCallback callback);
    oneway void performEnvelope(in int[] envInfo, in boolean fastFlag,
            in IRichtapCallback callback);
    oneway void performRtp(in ParcelFileDescriptor hdl, in IRichtapCallback callback);
    oneway void performHe(in int looper, in int interval, in int amplitude, in int freq,
            in int[] he, in IRichtapCallback callback);
}
