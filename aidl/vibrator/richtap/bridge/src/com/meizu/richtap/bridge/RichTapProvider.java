/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.meizu.richtap.bridge;

import android.Manifest;
import android.content.AttributionSource;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.PermissionChecker;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import vendor.aac.hardware.richtap.vibrator.IRichtapVibrator;

public final class RichTapProvider extends ContentProvider {
    private static final int EFFECT_ID_START = 0x1000;
    private static final int MAX_PATTERN_INTS = 128 * 1024;
    private static final int NO_ACTIVE_UID = -1;
    private static final String VIBRATOR_SERVICE =
            "android.hardware.vibrator.IVibrator/default";

    private final Object mStateLock = new Object();
    private int mActiveUid = NO_ACTIVE_UID;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!isValidRequest(method, extras)) {
            return null;
        }

        int uid = Binder.getCallingUid();
        AttributionSource caller = getCallingAttributionSource();
        if (caller == null) {
            return null;
        }

        getContext().enforceCallingPermission(
                Manifest.permission.VIBRATE, "RichTap vibration");
        AttributionSource chain =
                new AttributionSource(getContext().getAttributionSource(), caller);
        int permissionResult = PermissionChecker.checkPermissionForDataDeliveryFromDataSource(
                getContext(), Manifest.permission.VIBRATE, -1,
                chain, "RichTap vibration");
        if (permissionResult != PermissionChecker.PERMISSION_GRANTED) {
            return null;
        }

        boolean stopRequest = isStopRequest(method, extras);
        long identity = Binder.clearCallingIdentity();
        try {
            IRichtapVibrator richtap = getRichtap();
            switch (method) {
                case "performHe" -> {
                    synchronized (mStateLock) {
                        richtap.performHe(
                                extras.getInt("looper"), extras.getInt("interval"),
                                extras.getInt("amplitude"), extras.getInt("freq"),
                                extras.getIntArray("pattern"), null);
                        mActiveUid = uid;
                    }
                }
                case "performHeParam" -> {
                    synchronized (mStateLock) {
                        if (mActiveUid != NO_ACTIVE_UID && mActiveUid != uid) {
                            return null;
                        }
                        richtap.performHeParam(
                                extras.getInt("interval"), extras.getInt("amplitude"),
                                extras.getInt("freq"), null);
                        if (stopRequest) {
                            mActiveUid = NO_ACTIVE_UID;
                        }
                    }
                }
                case "performEnvelope" -> {
                    synchronized (mStateLock) {
                        int[] envelope = packEnvelope(
                                extras.getIntArray("relativeTime"), extras.getIntArray("scale"),
                                extras.getIntArray("freq"));
                        richtap.setAmplitude(extras.getInt("amplitude"), null);
                        richtap.performEnvelope(
                                envelope, extras.getBoolean("steepMode"), null);
                        mActiveUid = uid;
                    }
                }
                case "performPrebaked" -> {
                    synchronized (mStateLock) {
                        richtap.perform(
                                extras.getInt("effectId") + EFFECT_ID_START,
                                (byte) extras.getInt("strength"), null);
                        mActiveUid = uid;
                    }
                }
            }
        } catch (RemoteException | RuntimeException ignored) {
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }

    private static boolean isValidRequest(String method, Bundle extras) {
        if (method == null || extras == null) {
            return false;
        }

        try {
            return switch (method) {
                case "performHe" -> {
                    Object value = extras.get("pattern");
                    if (!(value instanceof int[] pattern)
                            || pattern.length == 0 || pattern.length > MAX_PATTERN_INTS
                            || !hasInt(extras, "looper") || !hasInt(extras, "interval")
                            || !hasInt(extras, "amplitude") || !hasInt(extras, "freq")) {
                        yield false;
                    }
                    int loopCount = extras.getInt("looper");
                    yield loopCount >= 0 && hasValidParameters(
                            extras.getInt("interval"), extras.getInt("amplitude"),
                            extras.getInt("freq"));
                }
                case "performHeParam" ->
                        hasInt(extras, "interval") && hasInt(extras, "amplitude")
                                && hasInt(extras, "freq")
                                && hasValidParameters(
                                        extras.getInt("interval"), extras.getInt("amplitude"),
                                        extras.getInt("freq"));
                case "performEnvelope" -> {
                    Object relativeTimeValue = extras.get("relativeTime");
                    Object scaleValue = extras.get("scale");
                    Object freqValue = extras.get("freq");
                    if (!(relativeTimeValue instanceof int[] relativeTime)
                            || !(scaleValue instanceof int[] scale)
                            || !(freqValue instanceof int[] freq)
                            || !(extras.get("steepMode") instanceof Boolean)
                            || !hasInt(extras, "amplitude")) {
                        yield false;
                    }
                    int amplitude = extras.getInt("amplitude");
                    yield hasFourNonNegativeValues(relativeTime)
                            && hasFourNonNegativeValues(scale)
                            && hasFourNonNegativeValues(freq)
                            && (amplitude == -1 || (amplitude >= 1 && amplitude <= 255));
                }
                case "performPrebaked" -> {
                    if (!hasInt(extras, "effectId") || !hasInt(extras, "strength")) {
                        yield false;
                    }
                    int effectId = extras.getInt("effectId");
                    int strength = extras.getInt("strength");
                    yield effectId >= 0 && effectId <= Integer.MAX_VALUE - EFFECT_ID_START
                            && strength >= 1 && strength <= 100;
                }
                default -> false;
            };
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean hasInt(Bundle extras, String key) {
        return extras.get(key) instanceof Integer;
    }

    private static boolean hasValidParameters(int interval, int amplitude, int freq) {
        return interval >= -1 && amplitude >= -1 && amplitude <= 255 && freq >= -1;
    }

    private static boolean hasFourNonNegativeValues(int[] values) {
        if (values.length != 4) {
            return false;
        }
        for (int value : values) {
            if (value < 0) {
                return false;
            }
        }
        return true;
    }

    private static int[] packEnvelope(int[] relativeTime, int[] scale, int[] freq) {
        int[] envelope = new int[12];
        for (int i = 0; i < 4; i++) {
            int offset = i * 3;
            envelope[offset] = relativeTime[i];
            envelope[offset + 1] = scale[i];
            envelope[offset + 2] = freq[i];
        }
        return envelope;
    }

    private static boolean isStopRequest(String method, Bundle extras) {
        return "performHeParam".equals(method)
                && extras.getInt("interval") == 0
                && extras.getInt("amplitude") == 0
                && extras.getInt("freq") == 0;
    }

    private static IRichtapVibrator getRichtap() throws RemoteException {
        IBinder vibrator = ServiceManager.checkService(VIBRATOR_SERVICE);
        if (vibrator == null) {
            throw new RemoteException("Vibrator HAL is unavailable");
        }
        IBinder extension = vibrator.getExtension();
        if (extension == null) {
            throw new RemoteException("RichTap extension is unavailable");
        }
        return IRichtapVibrator.Stub.asInterface(Binder.allowBlocking(extension));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        return 0;
    }
}
