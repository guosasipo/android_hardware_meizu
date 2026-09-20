/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.settings;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import com.android.settingslib.widget.SelectorWithWidgetPreference;

import org.meizu.awlight.AwLightApplication;
import org.meizu.awlight.AwLightNotificationService;
import org.meizu.awlight.AwLightSettingsActivity;
import org.meizu.awlight.R;
import org.meizu.awlight.manager.AwLightController;
import org.meizu.awlight.preference.LightColorPreference;
import org.meizu.awlight.preference.StockPreviewPreference;
import org.meizu.awlight.utils.LightPreferences;

public final class RingEffectSettingsFragment extends SettingsBasePreferenceFragment {
    private static final String[] COLOR_ASSETS = {"white", "red", "green", "blue", "orange",
            "yellow", "pink", "lime_green", "cyan", "purple"};

    private AwLightController mController;
    private boolean mCharging;
    private boolean mNotifications;
    private boolean mMusic;
    private boolean mAssistant;
    private int mTitle;
    private StockPreviewPreference mPreview;
    private MainSwitchPreference mEnabled;
    private LightColorPreference mColor;
    private Preference mAccess;
    private Preference mApps;
    private SelectorWithWidgetPreference mAlways;
    private SelectorWithWidgetPreference mWhenMoved;
    private final AwLightController.Observer mObserver = this::render;
    private final ActivityResultLauncher<String> mAudioPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                mController.setMusicEnabled(granted);
                render(mController.getSnapshot());
            });

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        String feature = requireArguments().getString("feature");
        if (!"charging".equals(feature) && !"notifications".equals(feature)
                && !"volume".equals(feature) && !"music".equals(feature)
                && !"assistant".equals(feature)) {
            requireActivity().finish();
            return;
        }
        mCharging = "charging".equals(feature);
        mNotifications = "notifications".equals(feature);
        mMusic = "music".equals(feature);
        mAssistant = "assistant".equals(feature);
        mTitle = mCharging ? R.string.charging_light_effect
                : mNotifications ? R.string.notification_light_effect
                : mMusic ? R.string.music_light_effect
                : mAssistant ? R.string.assistant_light_effect : R.string.volume_adjust_light_effect;
        mController = AwLightApplication.controller(requireContext());
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);

        mPreview = new StockPreviewPreference(requireContext());
        mPreview.setAsset(mCharging ? "charging/charge.json"
                : mNotifications ? "notification/message_white.json"
                : mMusic ? "music/music.json"
                : mAssistant ? "assistant/aicy.json" : "volume/volume.json");
        mPreview.setSummary(mCharging ? R.string.charging_light_effect_tip
                : mNotifications ? R.string.notification_light_effect_tip
                : mMusic ? R.string.music_light_effect_tip
                : mAssistant ? R.string.assistant_light_effect_tip : R.string.volume_adjust_light_effect_tip);
        screen.addPreference(mPreview);

        mEnabled = new MainSwitchPreference(requireContext());
        configure(mEnabled, "enabled", mTitle);
        mEnabled.setOnPreferenceChangeListener((preference, value) -> {
            if (!(value instanceof Boolean enabled)) return false;
            if (mMusic && enabled
                    && requireContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                mAudioPermission.launch(Manifest.permission.RECORD_AUDIO);
                return false;
            }
            if (mCharging) mController.setChargingEnabled(enabled);
            else if (mNotifications) mController.setNotificationsEnabled(enabled);
            else if (mMusic) mController.setMusicEnabled(enabled);
            else if (mAssistant) mController.setAssistantEnabled(enabled);
            else mController.setVolumeEnabled(enabled);
            return true;
        });
        screen.addPreference(mEnabled);

        if (mCharging) {
            PreferenceCategory display = new PreferenceCategory(requireContext());
            display.setTitle(R.string.charging_light_effect_display);
            screen.addPreference(display);
            mAlways = new SelectorWithWidgetPreference(requireContext(), false);
            configure(mAlways, "show_always", R.string.show_always);
            mAlways.setOnClickListener(preference -> {
                if (!preference.isChecked()) mController.setChargingWhenMoved(false);
            });
            display.addPreference(mAlways);
            mWhenMoved = new SelectorWithWidgetPreference(requireContext(), false);
            configure(mWhenMoved, "shown_when_moved", R.string.shown_when_moved);
            mWhenMoved.setOnClickListener(preference -> {
                if (!preference.isChecked()) mController.setChargingWhenMoved(true);
            });
            display.addPreference(mWhenMoved);
        }

        if (mNotifications) {
            mAccess = new Preference(requireContext());
            configure(mAccess, "access", R.string.notification_access);
            mAccess.setOnPreferenceClickListener(preference -> {
                openAccess();
                return true;
            });
            screen.addPreference(mAccess);
            mApps = new Preference(requireContext());
            configure(mApps, "notification_apps", R.string.supported_applications);
            mApps.setIntent(new Intent(requireContext(), AwLightSettingsActivity.class)
                    .putExtra(AwLightSettingsActivity.EXTRA_FEATURE, "notification_apps"));
            screen.addPreference(mApps);

            PreferenceCategory colors = new PreferenceCategory(requireContext());
            colors.setTitle(R.string.light_color);
            screen.addPreference(colors);
            mColor = new LightColorPreference(requireContext());
            mColor.setKey("notification_color");
            mColor.setIconSpaceReserved(false);
            mColor.setOnPreferenceChangeListener((preference, value) -> {
                if (!(value instanceof Integer)) return false;
                int color = (Integer) value;
                if (!LightPreferences.isNotificationColor(color)) return false;
                mController.setNotificationColor(color);
                return true;
            });
            colors.addPreference(mColor);
        }

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
        if (mController == null) return;
        requireActivity().setTitle(mTitle);
        mController.addObserver(mObserver);
        mPreview.setResumed(true);
    }

    @Override
    public void onPause() {
        if (mController != null) {
            mPreview.setResumed(false);
            mController.removeObserver(mObserver);
            mController.cancelPreview();
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (mPreview != null) mPreview.release();
        super.onDestroyView();
    }

    private void render(AwLightController.Snapshot state) {
        if (mEnabled == null || !isAdded()) return;
        LightPreferences.Values preferences = state.preferences;
        mEnabled.setChecked(mCharging ? preferences.chargingEnabled
                : mNotifications ? preferences.notificationsEnabled
                : mMusic ? preferences.musicEnabled
                : mAssistant ? preferences.assistantEnabled
                : preferences.volumeEnabled);
        mEnabled.setEnabled(state.canEdit
                && (!mAssistant || state.assistantAvailable || preferences.assistantEnabled));
        if (mCharging) {
            mAlways.setChecked(!preferences.chargingWhenMoved);
            mWhenMoved.setChecked(preferences.chargingWhenMoved);
            mAlways.setEnabled(state.canEdit && preferences.chargingEnabled);
            mWhenMoved.setEnabled(state.canEdit && preferences.chargingEnabled && state.motionSupported);
        }
        if (mNotifications) {
            mApps.setEnabled(state.canEdit && preferences.notificationsEnabled);
            mColor.setValue(preferences.notificationColor);
            mColor.setEnabled(state.canEdit && preferences.notificationsEnabled);
            NotificationManager manager = requireContext().getSystemService(NotificationManager.class);
            boolean access = manager != null && manager.isNotificationListenerAccessGranted(
                    new ComponentName(requireContext(), AwLightNotificationService.class));
            mAccess.setSummary(access ? R.string.notification_access_granted
                    : R.string.notification_access_missing);
            mAccess.setEnabled(state.canEdit);
            mPreview.setAsset(notificationAsset(preferences.notificationColor));
        }
    }

    private String notificationAsset(int color) {
        for (int i = 0; i < LightPreferences.COLOR_PRESETS.length; ++i) {
            if (LightPreferences.COLOR_PRESETS[i] == color) {
                return "notification/message_" + COLOR_ASSETS[i] + ".json";
            }
        }
        return "notification/message_white.json";
    }

    private void openAccess() {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        new ComponentName(requireContext(), AwLightNotificationService.class)
                                .flattenToString());
        if (intent.resolveActivity(requireContext().getPackageManager()) != null) {
            startActivity(intent);
        }
    }
}
