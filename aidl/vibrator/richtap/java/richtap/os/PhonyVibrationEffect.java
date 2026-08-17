/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package richtap.os;

import android.app.ActivityThread;
import android.app.Application;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;

import java.util.Arrays;

public final class PhonyVibrationEffect {
    private static final Uri BRIDGE_URI = Uri.parse("content://com.meizu.richtap.bridge");

    private PhonyVibrationEffect() {}

    public static int checkIfRichTapSupport() {
        return 0x0b1810;
    }

    public static VibrationEffect createExtPreBaked(int effectId, int strength) {
        Bundle args = new Bundle(2);
        args.putInt("effectId", effectId);
        args.putInt("strength", strength);
        call("performPrebaked", args);
        return null;
    }

    public static VibrationEffect createPatternHeWithParam(
            int[] pattern, int looper, int interval, int amplitude, int freq) {
        Bundle args = new Bundle(5);
        args.putIntArray("pattern", pattern);
        args.putInt("looper", looper);
        args.putInt("interval", interval);
        args.putInt("amplitude", amplitude);
        args.putInt("freq", freq);
        call("performHe", args);
        return null;
    }

    public static VibrationEffect createPatternHeParameter(
            int interval, int amplitude, int freq) {
        Bundle args = new Bundle(3);
        args.putInt("interval", interval);
        args.putInt("amplitude", amplitude);
        args.putInt("freq", freq);
        call("performHeParam", args);
        return null;
    }

    public static VibrationEffect createEnvelope(
            int[] relativeTime, int[] scale, int[] freq, boolean steepMode, int amplitude) {
        Bundle args = new Bundle(5);
        args.putIntArray("relativeTime", Arrays.copyOf(relativeTime, 4));
        args.putIntArray("scale", Arrays.copyOf(scale, 4));
        args.putIntArray("freq", Arrays.copyOf(freq, 4));
        args.putBoolean("steepMode", steepMode);
        args.putInt("amplitude", amplitude);
        call("performEnvelope", args);
        return null;
    }

    private static void call(String method, Bundle args) {
        Application application = ActivityThread.currentApplication();
        if (application == null) {
            return;
        }
        try {
            application.getContentResolver().call(BRIDGE_URI, method, null, args);
        } catch (RuntimeException ignored) {
        }
    }
}
