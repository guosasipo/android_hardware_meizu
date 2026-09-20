/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.settings;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import com.android.settingslib.widget.SliderPreference;
import com.android.settingslib.PrimarySwitchPreference;
import com.google.android.material.slider.Slider;

import java.text.NumberFormat;
import java.util.function.Consumer;

import org.meizu.awlight.AwLightApplication;
import org.meizu.awlight.AwLightSettingsActivity;
import org.meizu.awlight.R;
import org.meizu.awlight.manager.AwLightController;
import org.meizu.awlight.preference.StockMainPreviewPreference;

public final class RingSettingsFragment extends SettingsBasePreferenceFragment {
    private static final int[] WEATHER_NAMES = {R.string.weather_sunny, R.string.weather_cloudy,
            R.string.weather_overcast, R.string.weather_rain, R.string.weather_snow,
            R.string.weather_thunder};
    private static final int[] WEATHER_EFFECTS = {51, 52, 53, 55, 54, 69};

    private AwLightController mController;
    private StockMainPreviewPreference mPreview;
    private MainSwitchPreference mAutomatic;
    private SliderPreference mBrightness;
    private Slider mBrightnessSlider;
    private boolean mDraggingBrightness;
    private final Runnable mUpdateBrightness = () -> {
        if (isResumed() && mDraggingBrightness && mBrightnessSlider != null) {
            mController.setTemporaryAutoBrightness(mBrightnessSlider.getValue() / 100f);
        }
    };
    private Preference mSchedule;
    private SwitchPreferenceCompat mDisableInPowerSave;
    private SwitchPreferenceCompat mDisableWhenFaceUp;
    private PrimarySwitchPreference mCharging;
    private SwitchPreferenceCompat mReverseCharging;
    private PrimarySwitchPreference mCalls;
    private PrimarySwitchPreference mNotifications;
    private PrimarySwitchPreference mVolume;
    private PrimarySwitchPreference mMusic;
    private PrimarySwitchPreference mAssistant;
    private Preference mFireworks;
    private Preference mWeather;
    private Preference mLetter;
    private final AwLightController.Observer mObserver = this::render;
    private final ActivityResultLauncher<String> mPhonePermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                mController.setCallsEnabled(granted);
                render(mController.getSnapshot());
            });
    private final ActivityResultLauncher<String> mAudioPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                mController.setMusicEnabled(granted);
                render(mController.getSnapshot());
            });

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        mController = AwLightApplication.controller(requireContext());
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);

        mPreview = new StockMainPreviewPreference(requireContext());
        screen.addPreference(mPreview);

        mAutomatic = new MainSwitchPreference(requireContext());
        configure(mAutomatic, "automatic", R.string.app_name);
        mAutomatic.setOnPreferenceChangeListener((preference, value) -> {
            if (!(value instanceof Boolean)) return false;
            mController.setAutoEnabled((Boolean) value);
            return true;
        });
        screen.addPreference(mAutomatic);

        mBrightness = new SliderPreference(requireContext());
        configure(mBrightness, "auto_brightness", R.string.auto_brightness);
        mBrightness.setMin(0);
        mBrightness.setMax(100);
        mBrightness.setSliderIncrement(1);
        mBrightness.setAdjustable(true);
        mBrightness.setUpdatesContinuously(false);
        mBrightness.setShowSliderValue(true);
        NumberFormat percent = NumberFormat.getPercentInstance(
                getResources().getConfiguration().getLocales().get(0));
        percent.setMaximumFractionDigits(0);
        mBrightness.setLabelFormater(value -> percent.format(value / 100.0));
        mBrightness.setOnPreferenceChangeListener((preference, value) -> {
            if (!(value instanceof Integer)) return false;
            mController.setAutoBrightness((Integer) value / 100f);
            return true;
        });
        mBrightness.setExtraChangeListener((slider, value, fromUser) -> {
            if (fromUser && mDraggingBrightness) {
                slider.removeCallbacks(mUpdateBrightness);
                slider.postOnAnimation(mUpdateBrightness);
            }
        });
        mBrightness.setExtraTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) {
                mBrightnessSlider = slider;
                mDraggingBrightness = true;
            }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                slider.removeCallbacks(mUpdateBrightness);
                mDraggingBrightness = false;
                mBrightnessSlider = null;
                mController.clearTemporaryAutoBrightness();
            }
        });
        screen.addPreference(mBrightness);

        mSchedule = new Preference(requireContext());
        configure(mSchedule, "schedule", R.string.remind_light_effect_enable_period);
        mSchedule.setIntent(new Intent(requireContext(), AwLightSettingsActivity.class)
                .putExtra(AwLightSettingsActivity.EXTRA_FEATURE, "schedule"));
        screen.addPreference(mSchedule);

        mDisableInPowerSave = new SwitchPreferenceCompat(requireContext());
        configure(mDisableInPowerSave, "disable_in_power_save", R.string.disable_in_power_save);
        mDisableInPowerSave.setOnPreferenceChangeListener((preference, value) -> {
            if (!(value instanceof Boolean enabled)) return false;
            mController.setDisableInPowerSave(enabled);
            return true;
        });
        screen.addPreference(mDisableInPowerSave);

        mDisableWhenFaceUp = new SwitchPreferenceCompat(requireContext());
        configure(mDisableWhenFaceUp, "disable_when_face_up", R.string.disable_when_face_up);
        mDisableWhenFaceUp.setOnPreferenceChangeListener((preference, value) -> {
            if (!(value instanceof Boolean enabled)) return false;
            mController.setDisableWhenFaceUp(enabled);
            return true;
        });
        screen.addPreference(mDisableWhenFaceUp);

        PreferenceCategory reminders = category(screen, R.string.remind_light_effect);
        mCalls = feature(reminders, "calls", R.string.incall_light_effect,
                mController::setCallsEnabled);
        mCalls.setOnPreferenceChangeListener((preference, value) -> {
            if (!(value instanceof Boolean enabled)) return false;
            if (enabled && requireContext().checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                mPhonePermission.launch(Manifest.permission.READ_PHONE_STATE);
                if (mCalls.getSwitch() != null) mCalls.getSwitch().setChecked(false);
                return false;
            }
            mController.setCallsEnabled(enabled);
            return true;
        });
        mNotifications = feature(reminders, "notifications", R.string.notification_light_effect,
                mController::setNotificationsEnabled);
        PreferenceCategory specials = category(screen, R.string.more_light_effect);
        mCharging = feature(specials, "charging", R.string.charging_light_effect,
                mController::setChargingEnabled);
        mReverseCharging = new SwitchPreferenceCompat(requireContext());
        configure(mReverseCharging, "reverse_charging", R.string.charge_reverse);
        mReverseCharging.setOnPreferenceChangeListener((preference, value) -> {
            if (!(value instanceof Boolean enabled)) return false;
            mController.setReverseChargingEnabled(enabled);
            return true;
        });
        specials.addPreference(mReverseCharging);
        mVolume = feature(specials, "volume", R.string.volume_adjust_light_effect,
                mController::setVolumeEnabled);
        mMusic = feature(specials, "music", R.string.music_light_effect,
                mController::setMusicEnabled);
        mMusic.setOnPreferenceChangeListener((preference, value) -> {
            if (!(value instanceof Boolean enabled)) return false;
            if (enabled && requireContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                mAudioPermission.launch(Manifest.permission.RECORD_AUDIO);
                if (mMusic.getSwitch() != null) mMusic.getSwitch().setChecked(false);
                return false;
            }
            mController.setMusicEnabled(enabled);
            return true;
        });
        mAssistant = feature(specials, "assistant", R.string.assistant_light_effect,
                mController::setAssistantEnabled);
        mFireworks = action(specials, "fireworks", R.string.fireworks, () -> preview(56, 0));
        mWeather = action(specials, "weather", R.string.weather_effect, this::chooseWeather);
        mLetter = action(specials, "letter", R.string.letter_effect, this::chooseLetter);
        render(mController.getSnapshot());
    }

    private void configure(Preference preference, String key, int title) {
        preference.setKey(key);
        preference.setTitle(title);
        preference.setPersistent(false);
        preference.setIconSpaceReserved(false);
    }

    private PreferenceCategory category(PreferenceGroup parent, int title) {
        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setTitle(title);
        category.setIconSpaceReserved(false);
        parent.addPreference(category);
        return category;
    }

    private PrimarySwitchPreference feature(PreferenceGroup parent, String feature, int title,
            Consumer<Boolean> setter) {
        PrimarySwitchPreference preference = new PrimarySwitchPreference(requireContext());
        configure(preference, feature, title);
        preference.setIntent(new Intent(requireContext(), AwLightSettingsActivity.class)
                .putExtra(AwLightSettingsActivity.EXTRA_FEATURE, feature));
        preference.setOnPreferenceChangeListener((row, value) -> {
            if (!(value instanceof Boolean)) return false;
            setter.accept((Boolean) value);
            return true;
        });
        parent.addPreference(preference);
        return preference;
    }

    private Preference action(PreferenceGroup parent, String key, int title, Runnable action) {
        Preference preference = new Preference(requireContext());
        configure(preference, key, title);
        preference.setOnPreferenceClickListener(clicked -> {
            action.run();
            return true;
        });
        parent.addPreference(preference);
        return preference;
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.app_name);
        mController.addObserver(mObserver);
        mPreview.setResumed(true);
    }

    @Override
    public void onPause() {
        if (mBrightnessSlider != null) {
            mBrightnessSlider.removeCallbacks(mUpdateBrightness);
            if (mDraggingBrightness) {
                mController.setAutoBrightness(mBrightnessSlider.getValue() / 100f);
            }
        }
        mDraggingBrightness = false;
        mBrightnessSlider = null;
        mController.clearTemporaryAutoBrightness();
        mPreview.setResumed(false);
        mController.removeObserver(mObserver);
        mController.cancelPreview();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        mPreview.release();
        super.onDestroyView();
    }

    private void render(AwLightController.Snapshot state) {
        if (mAutomatic == null || !isAdded()) return;
        mAutomatic.setChecked(state.preferences.autoEnabled);
        mAutomatic.setEnabled(state.canEdit);
        mPreview.setHaloEnabled(state.preferences.autoEnabled);
        mSchedule.setSummary(state.preferences.scheduleEnabled
                ? RingScheduleSettingsFragment.formatTime(requireContext(),
                        state.preferences.scheduleStartMinute) + " – "
                        + RingScheduleSettingsFragment.formatTime(requireContext(),
                                state.preferences.scheduleEndMinute)
                : getString(R.string.enable_always));
        mSchedule.setEnabled(state.canEdit);
        mDisableInPowerSave.setChecked(state.preferences.disableInPowerSave);
        mDisableInPowerSave.setEnabled(state.canEdit);
        mDisableWhenFaceUp.setChecked(state.preferences.disableWhenFaceUp);
        mDisableWhenFaceUp.setEnabled(state.canEdit
                && (state.postureSupported || state.preferences.disableWhenFaceUp));
        if (!mDraggingBrightness) mBrightness.setValue(Math.round(state.autoBrightness * 100));
        mBrightness.setEnabled(state.canEdit && state.preferences.autoEnabled);
        boolean canConfigureEffects = state.canEdit && state.preferences.autoEnabled;
        mCharging.setChecked(state.preferences.chargingEnabled);
        mReverseCharging.setChecked(state.preferences.reverseChargingEnabled);
        mReverseCharging.setEnabled(canConfigureEffects);
        mCalls.setChecked(state.preferences.callsEnabled);
        mNotifications.setChecked(state.preferences.notificationsEnabled);
        mVolume.setChecked(state.preferences.volumeEnabled);
        mMusic.setChecked(state.preferences.musicEnabled);
        mAssistant.setChecked(state.preferences.assistantEnabled);
        for (PrimarySwitchPreference preference : new PrimarySwitchPreference[] {
                mCharging, mCalls, mNotifications, mVolume, mMusic}) {
            preference.setEnabled(canConfigureEffects);
            preference.setSwitchEnabled(canConfigureEffects);
        }
        mAssistant.setEnabled(canConfigureEffects);
        mAssistant.setSwitchEnabled(canConfigureEffects
                && (state.assistantAvailable || state.preferences.assistantEnabled));
        boolean canPreview = state.canEdit && state.preferencesValid
                && state.connected && !state.safetyBlocked && !state.powerSaveBlocked;
        mFireworks.setEnabled(canPreview);
        mWeather.setEnabled(canPreview);
        mLetter.setEnabled(canPreview);
    }

    private void preview(int effect, int letter) {
        mController.startPreview(effect, 0xffffff, letter, 0);
    }

    private void chooseWeather() {
        CharSequence[] entries = new CharSequence[WEATHER_NAMES.length];
        for (int i = 0; i < entries.length; ++i) entries[i] = getString(WEATHER_NAMES[i]);
        new AlertDialog.Builder(requireContext()).setTitle(R.string.weather_effect)
                .setItems(entries, (dialog, which) -> preview(WEATHER_EFFECTS[which], 0))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseLetter() {
        String[] letters = new String[26];
        for (int i = 0; i < letters.length; ++i) letters[i] = Character.toString((char) ('A' + i));
        new AlertDialog.Builder(requireContext()).setTitle(R.string.letter_effect)
                .setItems(letters, (dialog, which) -> preview(62, 'A' + which))
                .setNegativeButton(android.R.string.cancel, null).show();
    }
}
