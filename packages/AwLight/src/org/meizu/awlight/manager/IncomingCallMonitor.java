/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.manager;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import java.util.function.Consumer;

final class IncomingCallMonitor {
    private final Context mContext;
    private final Handler mHandler;
    private final TelecomManager mTelecom;
    private final Consumer<Boolean> mCallback;
    private boolean mEnabled;
    private boolean mRegistered;
    private boolean mRinging;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mRegistered && TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(
                    intent.getAction())) {
                refresh();
            }
        }
    };

    IncomingCallMonitor(Context context, Handler handler, Consumer<Boolean> callback) {
        mContext = context;
        mHandler = handler;
        mTelecom = context.getSystemService(TelecomManager.class);
        mCallback = callback;
    }

    boolean isAvailable() {
        return mTelecom != null && mContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    void setEnabled(boolean enabled) {
        if (mEnabled == enabled) return;
        mEnabled = enabled;
        refresh();
    }

    void refresh() {
        if (!mEnabled || !isAvailable()) {
            if (mRegistered) {
                mContext.unregisterReceiver(mReceiver);
                mRegistered = false;
            }
            publish(false);
            return;
        }
        try {
            if (!mRegistered) {
                mContext.registerReceiver(mReceiver,
                        new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), null,
                        mHandler, Context.RECEIVER_EXPORTED);
                mRegistered = true;
            }
            publish(mTelecom.getCallState() == TelephonyManager.CALL_STATE_RINGING);
        } catch (SecurityException | IllegalStateException e) {
            publish(false);
        }
    }

    private void publish(boolean ringing) {
        if (mRinging == ringing) return;
        mRinging = ringing;
        mCallback.accept(ringing);
    }
}
