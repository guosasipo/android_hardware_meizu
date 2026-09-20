/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.UserManager;

import androidx.fragment.app.Fragment;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import org.meizu.awlight.settings.RingEffectSettingsFragment;
import org.meizu.awlight.settings.RingCallSettingsFragment;
import org.meizu.awlight.settings.RingNotificationAppsFragment;
import org.meizu.awlight.settings.RingScheduleSettingsFragment;
import org.meizu.awlight.settings.RingSettingsFragment;

public final class AwLightSettingsActivity extends CollapsingToolbarBaseActivity {
    public static final String EXTRA_FEATURE = "feature";

    public static boolean canOpen(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        try {
            return users != null && keyguard != null && users.isUserUnlocked()
                    && users.isUserForeground() && !users.isManagedProfile()
                    && !keyguard.isKeyguardLocked();
        } catch (RuntimeException error) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!canOpen(this)) {
            finish();
            return;
        }
        String feature = getIntent().getStringExtra(EXTRA_FEATURE);
        if (feature != null && !"charging".equals(feature) && !"notifications".equals(feature)
                && !"volume".equals(feature) && !"schedule".equals(feature)
                && !"notification_apps".equals(feature) && !"calls".equals(feature)
                && !"music".equals(feature) && !"assistant".equals(feature)) {
            finish();
            return;
        }
        if (state == null) {
            Fragment fragment;
            if (feature == null) fragment = new RingSettingsFragment();
            else if ("calls".equals(feature)) fragment = new RingCallSettingsFragment();
            else if ("schedule".equals(feature)) fragment = new RingScheduleSettingsFragment();
            else if ("notification_apps".equals(feature)) fragment = new RingNotificationAppsFragment();
            else fragment = new RingEffectSettingsFragment();
            if (feature != null) {
                Bundle arguments = new Bundle();
                arguments.putString(EXTRA_FEATURE, feature);
                fragment.setArguments(arguments);
            }
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame, fragment)
                    .commit();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!canOpen(this)) finish();
    }
}
