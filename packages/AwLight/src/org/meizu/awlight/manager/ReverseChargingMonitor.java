/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.manager;

import android.content.Context;
import android.os.FileUtils;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UEventObserver;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

final class ReverseChargingMonitor {
    enum State { UNKNOWN, OFF, STANDBY, ACTIVE }

    private static final File STATUS =
            new File("/sys/class/qcom-battery/reverse_chg_state");

    private final Handler mHandler;
    private final Consumer<State> mListener;
    private final PowerManager.WakeLock mWakeLock;
    private final Executor mReader = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "AwLightReverse"));
    private UEventObserver mObserver;

    private boolean mEnabled;
    private boolean mReading;
    private boolean mPending;
    private int mGeneration;
    private int mWakeGeneration = -1;
    private State mState = State.UNKNOWN;

    ReverseChargingMonitor(Context context, Handler handler, Consumer<State> listener) {
        mHandler = handler;
        mListener = listener;
        PowerManager.WakeLock wake = null;
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            if (power != null) {
                wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AwLight:ReverseRead");
                wake.setReferenceCounted(false);
            }
        } catch (RuntimeException error) {
            wake = null;
        }
        mWakeLock = wake;
    }

    void setEnabled(boolean enabled) {
        if (mEnabled == enabled) return;
        mEnabled = enabled;
        mGeneration++;
        if (enabled) {
            mObserver = createObserver();
            try {
                mObserver.startObserving("POWER_SUPPLY_NAME=wireless");
            } catch (RuntimeException error) {
                mEnabled = false;
                stopObserving();
                publish(State.UNKNOWN);
                return;
            }
            refresh();
        } else {
            stopObserving();
            mPending = false;
            releaseWake(mWakeGeneration);
            publish(State.UNKNOWN);
        }
    }

    void refresh() {
        if (!mEnabled) return;
        mPending = true;
        if (!mReading) read();
    }

    private void read() {
        mPending = false;
        int generation = mGeneration;
        if (mWakeLock == null) {
            publish(State.UNKNOWN);
            return;
        }
        mWakeGeneration = generation;
        try {
            mWakeLock.acquire(3000);
        } catch (RuntimeException error) {
            releaseWake(generation);
            publish(State.UNKNOWN);
            return;
        }
        mReading = true;
        try {
            mReader.execute(() -> {
                State state = readState();
                mHandler.post(() -> {
                    releaseWake(generation);
                    mReading = false;
                    if (mEnabled && generation == mGeneration) publish(state);
                    if (mEnabled && mPending) read();
                });
            });
        } catch (RejectedExecutionException error) {
            mReading = false;
            releaseWake(generation);
            publish(State.UNKNOWN);
        }
    }

    private void releaseWake(int generation) {
        if (mWakeLock == null || mWakeGeneration != generation) return;
        mWakeGeneration = -1;
        try {
            if (mWakeLock.isHeld()) mWakeLock.release();
        } catch (RuntimeException ignored) {
        }
    }

    private void stopObserving() {
        UEventObserver observer = mObserver;
        mObserver = null;
        try {
            if (observer != null) observer.stopObserving();
        } catch (RuntimeException ignored) {
        }
    }

    private UEventObserver createObserver() {
        return new UEventObserver() {
            private final Runnable mRefresh = () -> {
                if (mEnabled && mObserver == this) refresh();
            };
            private final Runnable mRemoved = () -> {
                if (!mEnabled || mObserver != this) return;
                mGeneration++;
                mPending = false;
                releaseWake(mWakeGeneration);
                publish(State.UNKNOWN);
            };

            @Override
            public void onUEvent(UEvent event) {
                if (!"wireless".equals(event.get("POWER_SUPPLY_NAME"))) return;
                mHandler.removeCallbacks(mRefresh);
                mHandler.post("remove".equals(event.get("ACTION")) ? mRemoved : mRefresh);
            }
        };
    }

    private static State readState() {
        try {
            String status = FileUtils.readTextFile(STATUS, 32, null).trim();
            switch (status) {
                case "0": return State.OFF;
                case "1": return State.STANDBY;
                case "2": return State.ACTIVE;
                default: return State.UNKNOWN;
            }
        } catch (IOException | SecurityException error) {
            return State.UNKNOWN;
        }
    }

    private void publish(State state) {
        if (state == mState) return;
        mState = state;
        mListener.accept(state);
    }
}
