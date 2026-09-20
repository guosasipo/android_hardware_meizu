/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.manager;

import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

final class CameraLightSession {
    private static final long MAX_EVENT_AGE_MS = 1000;
    private static final long COMPLETION_GRACE_MS = 3000;
    private static final long COMPLETION_TOLERANCE_MS = 250;

    record Request(int effect, long expires, long generation) {}

    private final Handler mHandler;
    private final Runnable mChanged;
    private final Runnable mExpire = this::clear;
    private IBinder mToken;
    private IBinder.DeathRecipient mDeath;
    private long mStartedAt = -1;
    private long mSequence;
    private long mEventAt;
    private long mGeneration;
    private int mDurationMs;
    private boolean mCompleted;
    private Request mRequest;

    CameraLightSession(Handler handler, Runnable changed) {
        mHandler = handler;
        mChanged = changed;
    }

    Request request() {
        return mRequest;
    }

    void finishVisual(long generation) {
        if (mRequest != null && mRequest.generation() == generation) mRequest = null;
    }

    void accept(String command, IBinder token, long startedAt, int durationMs,
            long eventAt, long sequence) {
        long now = SystemClock.elapsedRealtime();
        if (token == null || sequence <= 0 || startedAt < 0 || eventAt < startedAt
                || eventAt > now || now - eventAt > MAX_EVENT_AGE_MS
                || (durationMs != 3000 && durationMs != 10000)) return;
        if (!"start".equals(command) && !"complete".equals(command)
                && !"cancel".equals(command)) return;
        if (startedAt < mStartedAt || (startedAt == mStartedAt && sequence <= mSequence)) return;
        if ("start".equals(command)) {
            if (token.equals(mToken) || now - startedAt > MAX_EVENT_AGE_MS) return;
            long deadline = startedAt + durationMs;
            if (deadline <= now) return;
            IBinder.DeathRecipient death = () -> mHandler.post(() -> {
                if (token.equals(mToken)) clear();
            });
            try {
                token.linkToDeath(death, 0);
            } catch (RemoteException | RuntimeException error) {
                return;
            }
            reset();
            mToken = token;
            mDeath = death;
            mStartedAt = startedAt;
            mDurationMs = durationMs;
            mSequence = sequence;
            mEventAt = eventAt;
            mCompleted = false;
            mRequest = new Request(durationMs == 3000 ? 40 : 39, deadline, ++mGeneration);
            mHandler.postDelayed(mExpire, deadline + COMPLETION_GRACE_MS - now);
            mChanged.run();
            return;
        }
        if (!token.equals(mToken)) {
            if (now - startedAt > durationMs + COMPLETION_GRACE_MS) return;
            reset();
            mStartedAt = startedAt;
            mSequence = sequence;
            mEventAt = eventAt;
            mChanged.run();
            return;
        }
        if (eventAt < mEventAt || startedAt != mStartedAt
                || durationMs != mDurationMs || now > mStartedAt + mDurationMs + COMPLETION_GRACE_MS) {
            return;
        }
        if ("cancel".equals(command)) {
            mSequence = sequence;
            clear();
        } else if ("complete".equals(command) && !mCompleted) {
            mSequence = sequence;
            mEventAt = eventAt;
            if (eventAt < mStartedAt + mDurationMs - COMPLETION_TOLERANCE_MS) {
                clear();
                return;
            }
            mCompleted = true;
            mHandler.removeCallbacks(mExpire);
            mRequest = new Request(63, eventAt + 300, ++mGeneration);
            mHandler.postDelayed(mExpire, Math.max(0, mRequest.expires() - now));
            mChanged.run();
        }
    }

    void clear() {
        boolean changed = mToken != null || mRequest != null;
        reset();
        if (changed) mChanged.run();
    }

    private void reset() {
        mHandler.removeCallbacks(mExpire);
        if (mToken != null && mDeath != null) {
            try {
                mToken.unlinkToDeath(mDeath, 0);
            } catch (RuntimeException ignored) {
            }
        }
        mToken = null;
        mDeath = null;
        mRequest = null;
        mCompleted = false;
    }
}
