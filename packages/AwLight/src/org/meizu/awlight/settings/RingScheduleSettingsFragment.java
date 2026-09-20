/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.settings;

import android.app.TimePickerDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.format.DateFormat;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.widget.SelectorWithWidgetPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.Calendar;

import org.meizu.awlight.AwLightApplication;
import org.meizu.awlight.R;
import org.meizu.awlight.manager.AwLightController;
import org.meizu.awlight.utils.LightPreferences;

public final class RingScheduleSettingsFragment extends SettingsBasePreferenceFragment {
    private AwLightController mController;
    private SelectorWithWidgetPreference mAlways;
    private SelectorWithWidgetPreference mScheduled;
    private PreferenceCategory mTimes;
    private Preference mStart;
    private Preference mEnd;
    private TimePickerDialog mPicker;
    private final AwLightController.Observer mObserver = this::render;

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        mController = AwLightApplication.controller(requireContext());
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
        mAlways = new SelectorWithWidgetPreference(requireContext(), false);
        configure(mAlways, "always", R.string.enable_always);
        mAlways.setOnClickListener(preference -> {
            if (!preference.isChecked()) mController.setScheduleEnabled(false);
        });
        screen.addPreference(mAlways);
        mScheduled = new SelectorWithWidgetPreference(requireContext(), false);
        configure(mScheduled, "scheduled", R.string.enable_scheduled);
        mScheduled.setOnClickListener(preference -> {
            if (!preference.isChecked()) mController.setScheduleEnabled(true);
        });
        screen.addPreference(mScheduled);
        mTimes = new PreferenceCategory(requireContext());
        screen.addPreference(mTimes);
        mStart = timePreference(mTimes, "start", R.string.start_time, true);
        mEnd = timePreference(mTimes, "end", R.string.end_time, false);
        render(mController.getSnapshot());
    }

    private void configure(Preference preference, String key, int title) {
        preference.setKey(key);
        preference.setTitle(title);
        preference.setPersistent(false);
        preference.setIconSpaceReserved(false);
    }

    private Preference timePreference(PreferenceCategory category, String key, int title, boolean start) {
        Preference preference = new Preference(requireContext());
        configure(preference, key, title);
        preference.setOnPreferenceClickListener(row -> {
            LightPreferences.Values values = mController.getSnapshot().preferences;
            int minute = start ? values.scheduleStartMinute : values.scheduleEndMinute;
            TimePickerDialog picker = new TimePickerDialog(requireContext(), (view, hour, minutes) -> {
                if (start) mController.setScheduleStartMinute(hour * 60 + minutes);
                else mController.setScheduleEndMinute(hour * 60 + minutes);
            }, minute / 60, minute % 60, DateFormat.is24HourFormat(requireContext()));
            picker.setTitle(title);
            picker.setOnDismissListener(dialog -> {
                if (mPicker == picker) mPicker = null;
            });
            if (mPicker != null) mPicker.dismiss();
            mPicker = picker;
            picker.show();
            return true;
        });
        category.addPreference(preference);
        return preference;
    }

    static String formatTime(Context context, int minute) {
        Calendar time = Calendar.getInstance();
        time.set(Calendar.HOUR_OF_DAY, minute / 60);
        time.set(Calendar.MINUTE, minute % 60);
        time.set(Calendar.SECOND, 0);
        time.set(Calendar.MILLISECOND, 0);
        return DateFormat.getTimeFormat(context).format(time.getTime());
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.remind_light_effect_enable_period);
        mController.addObserver(mObserver);
    }

    @Override
    public void onPause() {
        if (mPicker != null) {
            mPicker.dismiss();
            mPicker = null;
        }
        mController.removeObserver(mObserver);
        super.onPause();
    }

    private void render(AwLightController.Snapshot state) {
        if (mAlways == null || !isAdded()) return;
        LightPreferences.Values values = state.preferences;
        mAlways.setChecked(!values.scheduleEnabled);
        mScheduled.setChecked(values.scheduleEnabled);
        mAlways.setEnabled(state.canEdit);
        mScheduled.setEnabled(state.canEdit && state.exactAlarmsAllowed);
        mTimes.setVisible(values.scheduleEnabled);
        mStart.setEnabled(state.canEdit && state.exactAlarmsAllowed);
        mEnd.setEnabled(state.canEdit && state.exactAlarmsAllowed);
        mStart.setSummary(formatTime(requireContext(), values.scheduleStartMinute));
        mEnd.setSummary(formatTime(requireContext(), values.scheduleEndMinute));
    }
}
