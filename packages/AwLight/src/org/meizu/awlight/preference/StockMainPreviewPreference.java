/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.preference;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.GroupSectionDividerMixin;

import java.io.IOException;
import java.io.InputStream;

import org.meizu.awlight.R;

public final class StockMainPreviewPreference extends Preference implements GroupSectionDividerMixin {
    private TextureView mTexture;
    private ImageView mLastFrame;
    private ImageView mRing;
    private View mError;
    private MediaPlayer mPlayer;
    private Surface mSurface;
    private AnimatorSet mBreathe;
    private boolean mResumed;
    private boolean mHaloEnabled;
    private boolean mCompleted;
    private final View.OnAttachStateChangeListener mAttachment =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    if (view == mTexture) updatePlayback();
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    if (view == mTexture) {
                        stopPlayer();
                        stopBreathing();
                    }
                }
            };

    public StockMainPreviewPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.preference_stock_main_preview);
        setSelectable(false);
        setPersistent(false);
    }

    public void setResumed(boolean resumed) {
        mResumed = resumed;
        updatePlayback();
    }

    public void setHaloEnabled(boolean enabled) {
        if (mHaloEnabled == enabled) return;
        mHaloEnabled = enabled;
        updatePlayback();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        release();
        mCompleted = false;
        mError = holder.findViewById(R.id.main_preview_error);
        mError.setVisibility(View.GONE);
        ImageView first = (ImageView) holder.findViewById(R.id.main_preview_first);
        mLastFrame = (ImageView) holder.findViewById(R.id.main_preview_last);
        mRing = (ImageView) holder.findViewById(R.id.main_preview_ring);
        loadImage(first, "light_anim_frame_0.jpg");
        loadImage(mLastFrame, "light_anim_frame_1.jpg");
        loadImage(mRing, "light_breathe_ring.png");
        mLastFrame.setVisibility(View.INVISIBLE);
        mRing.setVisibility(View.INVISIBLE);
        mTexture = (TextureView) holder.findViewById(R.id.main_preview_video);
        mTexture.setAlpha(0);
        mTexture.setOpaque(false);
        mTexture.addOnAttachStateChangeListener(mAttachment);
        TextureView bound = mTexture;
        mTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (mTexture == bound) updatePlayback();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (mTexture == bound) {
                    stopPlayer();
                    stopBreathing();
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
        updatePlayback();
    }

    private void loadImage(ImageView image, String name) {
        try (InputStream stream = getContext().getAssets().open("preview/" + name)) {
            Drawable drawable = Drawable.createFromStream(stream, null);
            image.setImageDrawable(drawable);
            if (drawable == null) mError.setVisibility(View.VISIBLE);
        } catch (IOException error) {
            mError.setVisibility(View.VISIBLE);
        }
    }

    private void updatePlayback() {
        if (mTexture == null) return;
        if (!mResumed || !mTexture.isAttachedToWindow()) {
            stopPlayer();
            stopBreathing();
            return;
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            mCompleted = true;
            stopPlayer();
        }
        if (mCompleted) {
            showLastFrame();
        } else if (mPlayer == null && mTexture.isAvailable()) {
            startVideo();
        }
    }

    private void startVideo() {
        try (AssetFileDescriptor asset = getContext().getAssets().openFd("preview/light_anim.mp4")) {
            MediaPlayer player = new MediaPlayer();
            mPlayer = player;
            mSurface = new Surface(mTexture.getSurfaceTexture());
            player.setSurface(mSurface);
            player.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
            player.setVolume(0, 0);
            player.setOnPreparedListener(prepared -> {
                if (mPlayer != prepared) return;
                if (mResumed && mTexture != null && mTexture.isAttachedToWindow()) prepared.start();
            });
            player.setOnInfoListener((playing, what, extra) -> {
                if (mPlayer == playing && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
                        && mTexture != null) mTexture.setAlpha(1);
                return false;
            });
            player.setOnCompletionListener(completed -> {
                if (mPlayer != completed) return;
                mCompleted = true;
                stopPlayer();
                updatePlayback();
            });
            player.setOnErrorListener((failed, what, extra) -> {
                if (mPlayer == failed) failVideo();
                return true;
            });
            player.prepareAsync();
        } catch (IOException | RuntimeException error) {
            failVideo();
        }
    }

    private void failVideo() {
        mCompleted = true;
        stopPlayer();
        if (mError != null) mError.setVisibility(View.VISIBLE);
        updatePlayback();
    }

    private void showLastFrame() {
        mLastFrame.setVisibility(View.VISIBLE);
        if (!mHaloEnabled) {
            stopBreathing();
            mRing.setVisibility(View.INVISIBLE);
        } else if (!ValueAnimator.areAnimatorsEnabled()) {
            stopBreathing();
            mRing.setVisibility(View.VISIBLE);
            mRing.setAlpha(1);
        } else if (mBreathe == null) {
            mRing.setVisibility(View.VISIBLE);
            AnimatorSet animator = new AnimatorSet();
            mBreathe = animator;
            animator.playSequentially(fade(0, 1, 1650), fade(1, 1, 500),
                    fade(1, 0, 1650), fade(0, 0, 500));
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mBreathe == animation && mResumed && mHaloEnabled && mTexture != null
                            && mTexture.isAttachedToWindow() && ValueAnimator.areAnimatorsEnabled()) {
                        animator.start();
                    }
                }
            });
            animator.start();
        }
    }

    private ObjectAnimator fade(float from, float to, long duration) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(mRing, View.ALPHA, from, to);
        animator.setDuration(duration);
        animator.setInterpolator(new PathInterpolator(0.33f, 0, 0.67f, 1));
        return animator;
    }

    private void stopPlayer() {
        MediaPlayer player = mPlayer;
        mPlayer = null;
        if (player != null) player.release();
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        if (mTexture != null) mTexture.setAlpha(0);
    }

    private void stopBreathing() {
        AnimatorSet animator = mBreathe;
        mBreathe = null;
        if (animator != null) animator.cancel();
    }

    public void release() {
        stopPlayer();
        stopBreathing();
        if (mTexture != null) {
            mTexture.removeOnAttachStateChangeListener(mAttachment);
            mTexture.setSurfaceTextureListener(null);
        }
        mTexture = null;
        mLastFrame = null;
        mRing = null;
        mError = null;
    }
}
