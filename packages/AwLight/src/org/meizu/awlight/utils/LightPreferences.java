/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class LightPreferences {
    public static final int[] COLOR_PRESETS = {
        0xffffff, 0x7f0000, 0x007f00, 0x007fff, 0xff1f00,
        0xff7f00, 0x7f001f, 0x1f3f00, 0x3fffff, 0x7f007f
    };
    public static final int[] MANUAL_EFFECTS = {47, 32, 67};

    public static final class Values {
        public final boolean autoEnabled;
        public final boolean disableInPowerSave;
        public final boolean disableWhenFaceUp;
        public final float autoBrightness;
        public final boolean chargingEnabled;
        public final boolean reverseChargingEnabled;
        public final boolean notificationsEnabled;
        public final int notificationColor;
        public final int manualEffect;
        public final int manualColor;
        public final float manualBrightness;
        public final boolean scheduleEnabled;
        public final int scheduleStartMinute;
        public final int scheduleEndMinute;
        public final boolean chargingWhenMoved;
        public final boolean volumeEnabled;
        public final boolean musicEnabled;
        public final boolean assistantEnabled;
        public final Set<String> notificationBlockedPackages;
        public final boolean callsEnabled;
        public final int callColor;
        public final boolean callRhythm;

        private Values(Builder builder) {
            autoEnabled = builder.autoEnabled;
            disableInPowerSave = builder.disableInPowerSave;
            disableWhenFaceUp = builder.disableWhenFaceUp;
            autoBrightness = builder.autoBrightness;
            chargingEnabled = builder.chargingEnabled;
            reverseChargingEnabled = builder.reverseChargingEnabled;
            notificationsEnabled = builder.notificationsEnabled;
            notificationColor = builder.notificationColor;
            manualEffect = builder.manualEffect;
            manualColor = builder.manualColor;
            manualBrightness = builder.manualBrightness;
            scheduleEnabled = builder.scheduleEnabled;
            scheduleStartMinute = builder.scheduleStartMinute;
            scheduleEndMinute = builder.scheduleEndMinute;
            chargingWhenMoved = builder.chargingWhenMoved;
            volumeEnabled = builder.volumeEnabled;
            musicEnabled = builder.musicEnabled;
            assistantEnabled = builder.assistantEnabled;
            notificationBlockedPackages = Collections.unmodifiableSet(
                    new HashSet<>(builder.notificationBlockedPackages));
            callsEnabled = builder.callsEnabled;
            callColor = builder.callColor;
            callRhythm = builder.callRhythm;
        }

        public static Values defaults(boolean autoEnabled) {
            Builder builder = new Builder();
            builder.autoEnabled = autoEnabled;
            return builder.build();
        }

        public Builder buildUpon() {
            return new Builder(this);
        }

        public boolean isNotificationPackageAllowed(String packageName) {
            return !notificationBlockedPackages.contains(packageName);
        }

        public static final class Builder {
            public boolean autoEnabled = true;
            public boolean disableInPowerSave = true;
            public boolean disableWhenFaceUp = true;
            public float autoBrightness = 1.0f;
            public boolean chargingEnabled = true;
            public boolean reverseChargingEnabled = true;
            public boolean notificationsEnabled = true;
            public int notificationColor = 0xffffff;
            public int manualEffect = 47;
            public int manualColor = 0x007fff;
            public float manualBrightness = 0.5f;
            public boolean scheduleEnabled;
            public int scheduleStartMinute = 480;
            public int scheduleEndMinute = 1320;
            public boolean chargingWhenMoved;
            public boolean volumeEnabled;
            public boolean musicEnabled;
            public boolean assistantEnabled;
            public Set<String> notificationBlockedPackages = new HashSet<>();
            public boolean callsEnabled;
            public int callColor = 0xffffff;
            public boolean callRhythm;

            public Builder() {}

            public Builder(Values values) {
                Objects.requireNonNull(values);
                autoEnabled = values.autoEnabled;
                disableInPowerSave = values.disableInPowerSave;
                disableWhenFaceUp = values.disableWhenFaceUp;
                autoBrightness = values.autoBrightness;
                chargingEnabled = values.chargingEnabled;
                reverseChargingEnabled = values.reverseChargingEnabled;
                notificationsEnabled = values.notificationsEnabled;
                notificationColor = values.notificationColor;
                manualEffect = values.manualEffect;
                manualColor = values.manualColor;
                manualBrightness = values.manualBrightness;
                scheduleEnabled = values.scheduleEnabled;
                scheduleStartMinute = values.scheduleStartMinute;
                scheduleEndMinute = values.scheduleEndMinute;
                chargingWhenMoved = values.chargingWhenMoved;
                volumeEnabled = values.volumeEnabled;
                musicEnabled = values.musicEnabled;
                assistantEnabled = values.assistantEnabled;
                notificationBlockedPackages = new HashSet<>(values.notificationBlockedPackages);
                callsEnabled = values.callsEnabled;
                callColor = values.callColor;
                callRhythm = values.callRhythm;
            }

            public Values build() {
                if (!isNotificationColor(notificationColor) || !isNotificationColor(callColor)
                        || !isManualEffect(manualEffect)
                        || !isColor(manualColor) || !isBrightness(manualBrightness)
                        || !isBrightness(autoBrightness) || !isMinuteOfDay(scheduleStartMinute)
                        || !isMinuteOfDay(scheduleEndMinute)
                        || !validPackages(notificationBlockedPackages)) {
                    throw new IllegalArgumentException("Invalid light preferences");
                }
                return new Values(this);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Values value)) return false;
            return autoEnabled == value.autoEnabled && chargingEnabled == value.chargingEnabled
                    && disableInPowerSave == value.disableInPowerSave
                    && disableWhenFaceUp == value.disableWhenFaceUp
                    && reverseChargingEnabled == value.reverseChargingEnabled
                    && Float.compare(autoBrightness, value.autoBrightness) == 0
                    && notificationsEnabled == value.notificationsEnabled
                    && notificationColor == value.notificationColor && manualEffect == value.manualEffect
                    && manualColor == value.manualColor
                    && Float.compare(manualBrightness, value.manualBrightness) == 0
                    && scheduleEnabled == value.scheduleEnabled
                    && scheduleStartMinute == value.scheduleStartMinute
                    && scheduleEndMinute == value.scheduleEndMinute
                    && chargingWhenMoved == value.chargingWhenMoved
                    && volumeEnabled == value.volumeEnabled
                    && musicEnabled == value.musicEnabled
                    && assistantEnabled == value.assistantEnabled
                    && notificationBlockedPackages.equals(value.notificationBlockedPackages)
                    && callsEnabled == value.callsEnabled && callColor == value.callColor
                    && callRhythm == value.callRhythm;
        }

        @Override
        public int hashCode() {
            return Objects.hash(autoEnabled, disableInPowerSave, disableWhenFaceUp, chargingEnabled,
                    reverseChargingEnabled, notificationsEnabled,
                    notificationColor, manualEffect, manualColor, manualBrightness, autoBrightness,
                    scheduleEnabled, scheduleStartMinute, scheduleEndMinute, chargingWhenMoved,
                    volumeEnabled, musicEnabled, assistantEnabled, notificationBlockedPackages, callsEnabled,
                    callColor, callRhythm);
        }
    }

    public static final class Loaded {
        public final Values values;
        public final boolean valid;

        Loaded(Values values, boolean valid) {
            this.values = values;
            this.valid = valid;
        }
    }

    private static final String NAME = "aw_light";
    private static final String VERSION = "version";
    private static final String AUTO = "auto_enabled";
    private static final String DISABLE_IN_POWER_SAVE = "disable_in_power_save";
    private static final String DISABLE_WHEN_FACE_UP = "disable_when_face_up";
    private static final String AUTO_BRIGHTNESS = "auto_brightness";
    private static final String CHARGING = "charging_enabled";
    private static final String REVERSE_CHARGING = "reverse_charging_enabled";
    private static final String NOTIFICATIONS = "notifications_enabled";
    private static final String NOTIFICATION_COLOR = "notification_color";
    private static final String MANUAL_EFFECT = "manual_effect";
    private static final String MANUAL_COLOR = "manual_color";
    private static final String MANUAL_BRIGHTNESS = "manual_brightness";
    private static final String SCHEDULE = "schedule_enabled";
    private static final String SCHEDULE_START = "schedule_start_minute";
    private static final String SCHEDULE_END = "schedule_end_minute";
    private static final String CHARGING_WHEN_MOVED = "charging_when_moved";
    private static final String VOLUME = "volume_enabled";
    private static final String MUSIC = "music_enabled";
    private static final String ASSISTANT = "assistant_enabled";
    private static final String NOTIFICATION_BLOCKED_PACKAGES = "notification_blocked_packages";
    private static final String CALLS = "calls_enabled";
    private static final String CALL_COLOR = "call_color";
    private static final String CALL_RHYTHM = "call_rhythm";
    private final Context mStorage;

    public LightPreferences(Context context) {
        mStorage = context.createDeviceProtectedStorageContext();
    }

    public Loaded load() {
        try {
            File path = mStorage.getSharedPreferencesPath(NAME);
            boolean existed = path.exists() || new File(path.getPath() + ".bak").exists();
            return decode(preferences().getAll(), existed);
        } catch (RuntimeException error) {
            return invalid();
        }
    }

    static Loaded decode(Map<String, ?> data, boolean existed) {
        if (data == null) return invalid();
        if (!existed && data.isEmpty()) return new Loaded(Values.defaults(true), true);
        if (!(data.get(VERSION) instanceof Integer)) return invalid();
        int version = (Integer) data.get(VERSION);
        if ((version < 1 || version > 9)
                || data.size() != (version == 1 ? 8 : version == 2 ? 9 : version == 3 ? 15
                        : version == 4 ? 18 : version == 5 ? 19 : version == 6 ? 20
                        : version == 7 ? 21 : version == 8 ? 22 : 23)
                || !(data.get(AUTO) instanceof Boolean)
                || (version >= 8 && !(data.get(DISABLE_IN_POWER_SAVE) instanceof Boolean))
                || (version >= 9 && !(data.get(DISABLE_WHEN_FACE_UP) instanceof Boolean))
                || (version >= 2 && !(data.get(AUTO_BRIGHTNESS) instanceof Float))
                || !(data.get(CHARGING) instanceof Boolean)
                || (version >= 5 && !(data.get(REVERSE_CHARGING) instanceof Boolean))
                || (version >= 6 && !(data.get(MUSIC) instanceof Boolean))
                || (version >= 7 && !(data.get(ASSISTANT) instanceof Boolean))
                || !(data.get(NOTIFICATIONS) instanceof Boolean)
                || !(data.get(NOTIFICATION_COLOR) instanceof Integer)
                || !(data.get(MANUAL_EFFECT) instanceof Integer)
                || !(data.get(MANUAL_COLOR) instanceof Integer)
                || !(data.get(MANUAL_BRIGHTNESS) instanceof Float)
                || (version >= 3 && (!(data.get(SCHEDULE) instanceof Boolean)
                        || !(data.get(SCHEDULE_START) instanceof Integer)
                        || !(data.get(SCHEDULE_END) instanceof Integer)
                        || !(data.get(CHARGING_WHEN_MOVED) instanceof Boolean)
                        || !(data.get(VOLUME) instanceof Boolean)
                        || !(data.get(NOTIFICATION_BLOCKED_PACKAGES) instanceof Set<?>)))
                || (version >= 4 && (!(data.get(CALLS) instanceof Boolean)
                        || !(data.get(CALL_COLOR) instanceof Integer)
                        || !(data.get(CALL_RHYTHM) instanceof Boolean)))) {
            return invalid();
        }
        Values.Builder builder = new Values.Builder();
        builder.autoEnabled = (Boolean) data.get(AUTO);
        if (version >= 8) builder.disableInPowerSave = (Boolean) data.get(DISABLE_IN_POWER_SAVE);
        if (version >= 9) builder.disableWhenFaceUp = (Boolean) data.get(DISABLE_WHEN_FACE_UP);
        builder.autoBrightness = version == 1 ? 1.0f : (Float) data.get(AUTO_BRIGHTNESS);
        builder.chargingEnabled = (Boolean) data.get(CHARGING);
        builder.reverseChargingEnabled = version >= 5
                ? (Boolean) data.get(REVERSE_CHARGING) : builder.chargingEnabled;
        builder.notificationsEnabled = (Boolean) data.get(NOTIFICATIONS);
        builder.notificationColor = (Integer) data.get(NOTIFICATION_COLOR);
        builder.manualEffect = (Integer) data.get(MANUAL_EFFECT);
        builder.manualColor = (Integer) data.get(MANUAL_COLOR);
        builder.manualBrightness = (Float) data.get(MANUAL_BRIGHTNESS);
        if (version >= 3) {
            builder.scheduleEnabled = (Boolean) data.get(SCHEDULE);
            builder.scheduleStartMinute = (Integer) data.get(SCHEDULE_START);
            builder.scheduleEndMinute = (Integer) data.get(SCHEDULE_END);
            builder.chargingWhenMoved = (Boolean) data.get(CHARGING_WHEN_MOVED);
            builder.volumeEnabled = (Boolean) data.get(VOLUME);
            for (Object entry : (Set<?>) data.get(NOTIFICATION_BLOCKED_PACKAGES)) {
                if (!(entry instanceof String packageName) || !isPackageName(packageName)) {
                    return invalid();
                }
                builder.notificationBlockedPackages.add(packageName);
            }
        }
        if (version >= 4) {
            builder.callsEnabled = (Boolean) data.get(CALLS);
            builder.callColor = (Integer) data.get(CALL_COLOR);
            builder.callRhythm = (Boolean) data.get(CALL_RHYTHM);
        }
        if (version >= 6) builder.musicEnabled = (Boolean) data.get(MUSIC);
        if (version >= 7) builder.assistantEnabled = (Boolean) data.get(ASSISTANT);
        try {
            return new Loaded(builder.build(), true);
        } catch (IllegalArgumentException error) {
            return invalid();
        }
    }

    public boolean save(Values values) {
        if (values == null) return false;
        try {
            return preferences().edit().clear().putInt(VERSION, 9)
                    .putBoolean(AUTO, values.autoEnabled)
                    .putBoolean(DISABLE_IN_POWER_SAVE, values.disableInPowerSave)
                    .putBoolean(DISABLE_WHEN_FACE_UP, values.disableWhenFaceUp)
                    .putFloat(AUTO_BRIGHTNESS, values.autoBrightness)
                    .putBoolean(CHARGING, values.chargingEnabled)
                    .putBoolean(REVERSE_CHARGING, values.reverseChargingEnabled)
                    .putBoolean(NOTIFICATIONS, values.notificationsEnabled)
                    .putInt(NOTIFICATION_COLOR, values.notificationColor)
                    .putInt(MANUAL_EFFECT, values.manualEffect)
                    .putInt(MANUAL_COLOR, values.manualColor)
                    .putFloat(MANUAL_BRIGHTNESS, values.manualBrightness)
                    .putBoolean(SCHEDULE, values.scheduleEnabled)
                    .putInt(SCHEDULE_START, values.scheduleStartMinute)
                    .putInt(SCHEDULE_END, values.scheduleEndMinute)
                    .putBoolean(CHARGING_WHEN_MOVED, values.chargingWhenMoved)
                    .putBoolean(VOLUME, values.volumeEnabled)
                    .putBoolean(MUSIC, values.musicEnabled)
                    .putBoolean(ASSISTANT, values.assistantEnabled)
                    .putBoolean(CALLS, values.callsEnabled)
                    .putInt(CALL_COLOR, values.callColor)
                    .putBoolean(CALL_RHYTHM, values.callRhythm)
                    .putStringSet(NOTIFICATION_BLOCKED_PACKAGES,
                            new HashSet<>(values.notificationBlockedPackages)).commit();
        } catch (RuntimeException error) {
            return false;
        }
    }

    private SharedPreferences preferences() {
        return mStorage.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private static Loaded invalid() {
        return new Loaded(Values.defaults(false), false);
    }

    private static boolean validPackages(Set<?> packages) {
        if (packages == null) return false;
        for (Object entry : packages) {
            if (!(entry instanceof String packageName) || !isPackageName(packageName)) return false;
        }
        return true;
    }

    public static boolean isPackageName(String name) {
        if ("android".equals(name)) return true;
        if (name == null || name.isEmpty() || name.length() > 223) return false;
        boolean start = true;
        boolean separator = false;
        for (int i = 0; i < name.length(); ++i) {
            char c = name.charAt(i);
            if (c == '.') {
                if (start) return false;
                start = true;
                separator = true;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                start = false;
            } else if (start || !((c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return separator && !start;
    }

    public static boolean isMinuteOfDay(int minute) {
        return minute >= 0 && minute < 1440;
    }

    public static boolean isNotificationColor(int color) {
        return color == 0xffffff || color == 0x7f0000 || color == 0x007f00 || color == 0x007fff
                || color == 0xff1f00 || color == 0xff7f00 || color == 0x7f001f
                || color == 0x1f3f00 || color == 0x3fffff || color == 0x7f007f;
    }

    public static boolean isManualEffect(int effect) {
        return effect == 47 || effect == 32 || effect == 67;
    }

    public static boolean isColor(int color) {
        return color >= 0 && color <= 0xffffff;
    }

    public static boolean isBrightness(float brightness) {
        return Float.isFinite(brightness) && brightness >= 0 && brightness <= 1;
    }
}
