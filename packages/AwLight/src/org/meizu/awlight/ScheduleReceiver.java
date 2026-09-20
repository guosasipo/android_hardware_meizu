/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ScheduleReceiver extends BroadcastReceiver {
    public static final String ACTION = "org.meizu.awlight.SCHEDULE_BOUNDARY";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        PendingResult result = goAsync();
        AwLightApplication.controller(context).onScheduleAlarm(result::finish);
    }
}
