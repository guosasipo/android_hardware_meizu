/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

public final class CameraLightReceiver extends BroadcastReceiver {
    private static final String ACTION = "org.meizu.awlight.action.CAMERA_TIMER";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        PendingResult pending = null;
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            String command = extras.getString("command");
            IBinder token = extras.getBinder("token");
            long startedAt = extras.getLong("startedAt", -1);
            int durationMs = extras.getInt("durationMs", 0);
            long eventAt = extras.getLong("eventAt", -1);
            long sequence = extras.getLong("sequence", 0);
            PendingResult result = goAsync();
            pending = result;
            AwLightApplication.controller(context).onCameraEvent(command, token, startedAt,
                    durationMs, eventAt, sequence, result::finish);
        } catch (RuntimeException ignored) {
            if (pending != null) pending.finish();
        }
    }
}
