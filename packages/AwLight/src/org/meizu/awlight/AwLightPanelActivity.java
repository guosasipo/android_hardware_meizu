/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import org.meizu.awlight.manager.AwLightController;
import org.meizu.awlight.settings.AwLightPanel;

public final class AwLightPanelActivity extends AppCompatActivity {
    private AwLightController mController;
    private AwLightPanel mPanel;
    private boolean mReceiverRegistered;
    private final BroadcastReceiver mDismissReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        getTheme().applyStyle(
                com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight,
                true);
        super.onCreate(state);
        if (!AwLightSettingsActivity.canOpen(this)) {
            finish();
            return;
        }
        setFinishOnTouchOutside(true);
        mController = AwLightApplication.controller(this);
        mPanel = new AwLightPanel(this, mController);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mPanel == null || !AwLightSettingsActivity.canOpen(this)) {
            finish();
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_BACKGROUND);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        registerReceiver(mDismissReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        mReceiverRegistered = true;
        mPanel.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!AwLightSettingsActivity.canOpen(this)) finish();
    }

    @Override
    protected void onStop() {
        if (mReceiverRegistered) {
            unregisterReceiver(mDismissReceiver);
            mReceiverRegistered = false;
        }
        if (mPanel != null) mPanel.stop();
        if (mController != null) mController.cancelPreview();
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }
}
