/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.manager;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.UserHandle;

import java.util.List;
import java.util.function.Consumer;

final class PlaybackVisualizer {
    private static final long CAPTURE_TIMEOUT_MS = 1000;

    static final class Sample {
        final boolean available;
        final boolean active;
        final int amplitude;

        Sample(boolean available, boolean active, int amplitude) {
            this.available = available;
            this.active = active;
            this.amplitude = amplitude;
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final AudioManager mAudio;
    private final int mUsage;
    private final int mUserId;
    private final Consumer<Sample> mCallback;
    private boolean mEnabled;
    private boolean mRegistered;
    private int mSession;
    private int mUid = -1;
    private Visualizer mVisualizer;
    private int mSilentFrames;
    private boolean mActive;
    private Sample mLast = new Sample(false, false, 0);

    private AudioManager.AudioPlaybackCallback mPlaybackCallback;
    private AudioManager.OnModeChangedListener mModeListener;
    private final Runnable mCaptureTimeout = () -> {
        if (mVisualizer == null || !mEnabled) return;
        mSilentFrames = 0;
        mActive = false;
        publish(false, false, 0);
    };

    private final Visualizer.OnDataCaptureListener mCapture =
            new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int rate) {}

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int rate) {
                    if (visualizer != mVisualizer || !mEnabled || fft == null || fft.length != 128) {
                        return;
                    }
                    int amplitude = amplitude(fft);
                    if (amplitude != 0) {
                        mSilentFrames = 0;
                        mActive = true;
                    } else if (mSilentFrames >= 10) {
                        mActive = false;
                    } else {
                        ++mSilentFrames;
                    }
                    mHandler.removeCallbacks(mCaptureTimeout);
                    mHandler.postDelayed(mCaptureTimeout, CAPTURE_TIMEOUT_MS);
                    publish(true, mActive, amplitude);
                }
            };

    PlaybackVisualizer(Context context, Handler handler, int usage, Consumer<Sample> callback) {
        if (usage != AudioAttributes.USAGE_MEDIA
                && usage != AudioAttributes.USAGE_NOTIFICATION_RINGTONE) {
            throw new IllegalArgumentException("Unsupported playback usage");
        }
        mContext = context;
        mHandler = handler;
        mAudio = context.getSystemService(AudioManager.class);
        mUsage = usage;
        mUserId = context.getUserId();
        mCallback = callback;
    }

    boolean isAvailable() {
        return mAudio != null
                && mContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                && mContext.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
                        == PackageManager.PERMISSION_GRANTED;
    }

    void setEnabled(boolean enabled) {
        if (mEnabled == enabled) return;
        mEnabled = enabled;
        refresh();
    }

    void refresh() {
        if (!mEnabled || !isAvailable()) {
            stop();
            return;
        }
        try {
            if (!mRegistered) {
                mPlaybackCallback = new AudioManager.AudioPlaybackCallback() {
                    @Override
                    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                        if (this == mPlaybackCallback && mRegistered && mEnabled) {
                            selectSession(configs);
                        }
                    }
                };
                mRegistered = true;
                mAudio.registerAudioPlaybackCallback(mPlaybackCallback, mHandler);
                if (mUsage == AudioAttributes.USAGE_MEDIA) {
                    mModeListener = new AudioManager.OnModeChangedListener() {
                        @Override
                        public void onModeChanged(int mode) {
                            if (this == mModeListener && mRegistered && mEnabled) refresh();
                        }
                    };
                    mAudio.addOnModeChangedListener(mHandler::post, mModeListener);
                }
            }
            if (mVisualizer == null) {
                mSession = 0;
                mUid = -1;
            }
            selectSession(mAudio.getActivePlaybackConfigurations());
        } catch (RuntimeException e) {
            stop();
        }
    }

    private void selectSession(List<AudioPlaybackConfiguration> configs) {
        int session = 0;
        int uid = -1;
        boolean media = mUsage == AudioAttributes.USAGE_MEDIA;
        if (!media || mediaAllowed()) {
            for (AudioPlaybackConfiguration config : configs) {
                int candidateUid = media ? config.getClientUid() : -1;
                if (config.isActive() && !config.isMuted() && config.getSessionId() > 0
                        && config.getAudioAttributes().getUsage() == mUsage
                        && (!media || (candidateUid >= 0
                                && UserHandle.getUserId(candidateUid) == mUserId))) {
                    if (config.getSessionId() == mSession && candidateUid == mUid) {
                        session = mSession;
                        uid = candidateUid;
                        break;
                    }
                    if (session == 0) {
                        session = config.getSessionId();
                        uid = candidateUid;
                    }
                }
            }
        }
        if (session == mSession && uid == mUid) return;
        release();
        mSession = session;
        mUid = uid;
        publish(false, false, 0);
        if (session == 0 || !mEnabled || !mRegistered || mSession != session || mUid != uid) return;
        try {
            Visualizer visualizer = new Visualizer(session);
            mVisualizer = visualizer;
            int rate = Visualizer.getMaxCaptureRate() / 2;
            if (visualizer.setCaptureSize(128) != Visualizer.SUCCESS
                    || visualizer.setDataCaptureListener(mCapture, rate, false, true)
                            != Visualizer.SUCCESS) {
                release();
                return;
            }
            visualizer.setServerDiedListener(() -> {
                if (mVisualizer == visualizer) {
                    release();
                    publish(false, false, 0);
                }
            });
            if (visualizer.setEnabled(true) != Visualizer.SUCCESS) release();
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            release();
        }
    }

    private boolean mediaAllowed() {
        try {
            return mAudio.getMode() == AudioManager.MODE_NORMAL
                    && !mAudio.isStreamMute(AudioManager.STREAM_MUSIC)
                    && mAudio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void stop() {
        AudioManager.AudioPlaybackCallback callback = mPlaybackCallback;
        mPlaybackCallback = null;
        AudioManager.OnModeChangedListener modeListener = mModeListener;
        mModeListener = null;
        if (mRegistered) {
            mRegistered = false;
            try {
                if (callback != null) mAudio.unregisterAudioPlaybackCallback(callback);
            } catch (RuntimeException e) {
                // The audio service may have died; local capture still needs releasing.
            }
        }
        if (modeListener != null) {
            try {
                mAudio.removeOnModeChangedListener(modeListener);
            } catch (RuntimeException e) {
                // The audio service may already have removed this listener.
            }
        }
        release();
        mSession = 0;
        mUid = -1;
        publish(false, false, 0);
    }

    private void release() {
        mHandler.removeCallbacks(mCaptureTimeout);
        Visualizer visualizer = mVisualizer;
        mVisualizer = null;
        mSilentFrames = 0;
        mActive = false;
        if (visualizer != null) {
            try {
                visualizer.release();
            } catch (RuntimeException e) {
                // A dead effect must not prevent the fallback state from being published.
            }
        }
    }

    private void publish(boolean available, boolean active, int amplitude) {
        if (mLast.available == available && mLast.active == active
                && mLast.amplitude == amplitude) return;
        mLast = new Sample(available, active, amplitude);
        mCallback.accept(mLast);
    }

    static int amplitude(byte[] fft) {
        float a = magnitude(fft, 0);
        float b = magnitude(fft, 1);
        float c = magnitude(fft, 2);
        float d = magnitude(fft, 3);
        float e = magnitude(fft, 4);
        float f = magnitude(fft, 5);
        // Stock divides the first four smoothed bins by three, not four.
        int level = (int) ((a + (a + b + c) / 3f + (a + b + c + d + e) / 5f
                + (b + c + d + e + f) / 5f) / 3f);
        for (int i = 4; i < 50; ++i) level = Math.max(level, (int) magnitude(fft, i));
        level = Math.min(level, 127);
        if (level > 100) level = (level - 100) * 22 / 27 + 50;
        else if (level > 90) level = (level - 90) * 5 / 10 + 45;
        else if (level > 75) level = (level - 75) * 10 / 15 + 35;
        else if (level > 60) level = (level - 60) * 5 / 15 + 30;
        else if (level >= 1) level = (level - 1) * 25 / 59 + 5;
        return Math.min(level, 60);
    }

    private static float magnitude(byte[] fft, int bin) {
        int index = 2 + bin * 2;
        return (float) Math.hypot(fft[index], fft[index + 1]);
    }
}
