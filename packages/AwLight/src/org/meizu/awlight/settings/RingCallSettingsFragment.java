/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.settings;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import com.android.settingslib.widget.SelectorWithWidgetPreference;

import org.meizu.awlight.AwLightApplication;
import org.meizu.awlight.R;
import org.meizu.awlight.manager.AwLightController;
import org.meizu.awlight.preference.LightColorPreference;
import org.meizu.awlight.preference.StockPreviewPreference;
import org.meizu.awlight.utils.LightPreferences;

public final class RingCallSettingsFragment extends SettingsBasePreferenceFragment {
    private static final String[] COLOR_ASSETS = {"white", "red", "green", "blue", "orange",
            "yellow", "pink", "lime_green", "cyan", "purple"};

    private AwLightController mController;
    private StockPreviewPreference mPreview;
    private MainSwitchPreference mEnabled;
    private SelectorWithWidgetPreference mBreath;
    private SelectorWithWidgetPreference mRhythm;
    private LightColorPreference mColor;
    private final AwLightController.Observer mObserver = this::render;
    private final ActivityResultLauncher<String> mPhonePermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                mController.setCallsEnabled(granted);
                render(mController.getSnapshot());
            });
    private final ActivityResultLauncher<String> mAudioPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                mController.setCallRhythm(granted);
                render(mController.getSnapshot());
            });

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        mController = AwLightApplication.controller(requireContext());
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);

        mPreview = new StockPreviewPreference(requireContext());
        mPreview.setAsset("call/breath/phone_breath_white.json");
        mPreview.setSummary(R.string.incall_light_effect_tip);
        screen.addPreference(mPreview);

        mEnabled = new MainSwitchPreference(requireContext());
        configure(mEnabled, "enabled", R.string.incall_light_effect);
        mEnabled.setOnPreferenceChangeListener((preference, value) -> {
            if (!(value instanceof Boolean enabled)) return false;
            if (enabled && requireContext().checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                mPhonePermission.launch(Manifest.permission.READ_PHONE_STATE);
                return false;
            }
            mController.setCallsEnabled(enabled);
            return true;
        });
        screen.addPreference(mEnabled);

        PreferenceCategory mode = new PreferenceCategory(requireContext());
        mode.setTitle(R.string.incall_light_effect_mode);
        screen.addPreference(mode);
        mBreath = new SelectorWithWidgetPreference(requireContext(), false);
        configure(mBreath, "mode_flicker", R.string.mode_flicker);
        mBreath.setOnClickListener(preference -> {
            if (!preference.isChecked()) mController.setCallRhythm(false);
        });
        mode.addPreference(mBreath);
        mRhythm = new SelectorWithWidgetPreference(requireContext(), false);
        configure(mRhythm, "mode_with_music", R.string.mode_with_music);
        mRhythm.setOnClickListener(preference -> {
            if (preference.isChecked()) return;
            if (requireContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                mAudioPermission.launch(Manifest.permission.RECORD_AUDIO);
                return;
            }
            mController.setCallRhythm(true);
        });
        mode.addPreference(mRhythm);

        PreferenceCategory colors = new PreferenceCategory(requireContext());
        colors.setTitle(R.string.light_color);
        screen.addPreference(colors);
        mColor = new LightColorPreference(requireContext());
        mColor.setKey("call_color");
        mColor.setIconSpaceReserved(false);
        mColor.setOnPreferenceChangeListener((preference, value) -> {
            if (!(value instanceof Integer color) || !LightPreferences.isNotificationColor(color)) {
                return false;
            }
            mController.setCallColor(color);
            return true;
        });
        colors.addPreference(mColor);
        render(mController.getSnapshot());
    }

    private void configure(Preference preference, String key, int title) {
        preference.setKey(key);
        preference.setTitle(title);
        preference.setPersistent(false);
        preference.setIconSpaceReserved(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.incall_light_effect);
        mController.addObserver(mObserver);
        mPreview.setResumed(true);
    }

    @Override
    public void onPause() {
        mPreview.setResumed(false);
        mController.removeObserver(mObserver);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        mPreview.release();
        super.onDestroyView();
    }

    private void render(AwLightController.Snapshot state) {
        if (mEnabled == null || !isAdded()) return;
        LightPreferences.Values preferences = state.preferences;
        boolean enabled = preferences.callsEnabled;
        mEnabled.setChecked(enabled);
        mEnabled.setEnabled(state.canEdit);
        boolean rhythm = preferences.callRhythm;
        mBreath.setChecked(!rhythm);
        mRhythm.setChecked(rhythm);
        mBreath.setEnabled(state.canEdit && enabled);
        mRhythm.setEnabled(state.canEdit && enabled);
        mColor.setValue(preferences.callColor);
        mColor.setEnabled(state.canEdit && enabled);
        String color = "white";
        for (int i = 0; i < LightPreferences.COLOR_PRESETS.length; ++i) {
            if (LightPreferences.COLOR_PRESETS[i] == preferences.callColor) {
                color = COLOR_ASSETS[i];
                break;
            }
        }
        String path = rhythm ? "call/music/phone_music_" : "call/breath/phone_breath_";
        mPreview.setAsset(path + color + ".json");
        mPreview.setSummary(rhythm ? R.string.incall_light_effect_music_tip
                : R.string.incall_light_effect_tip);
    }
}
