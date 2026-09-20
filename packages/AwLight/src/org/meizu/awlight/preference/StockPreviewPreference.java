/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.preference;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.airbnb.lottie.LottieAnimationView;
import com.android.settingslib.widget.GroupSectionDividerMixin;

import java.io.IOException;
import java.io.InputStream;

import org.meizu.awlight.R;

public final class StockPreviewPreference extends Preference implements GroupSectionDividerMixin {
    private String mAsset = "notification/message_white.json";
    private boolean mResumed;
    private LottieAnimationView mAnimation;
    private View mError;
    private Drawable mBackground;
    private final View.OnAttachStateChangeListener mAttachment =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    updatePlayback();
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    ((LottieAnimationView) view).pauseAnimation();
                }
            };

    public StockPreviewPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.preference_stock_preview);
        setSelectable(false);
        setPersistent(false);
    }

    public void setAsset(String asset) {
        if (!mAsset.equals(asset)) {
            mAsset = asset;
            bindAnimation();
        }
    }

    public void setResumed(boolean resumed) {
        mResumed = resumed;
        updatePlayback();
    }

    public void release() {
        if (mAnimation != null) {
            mAnimation.cancelAnimation();
            mAnimation.removeAllLottieOnCompositionLoadedListener();
            mAnimation.removeOnAttachStateChangeListener(mAttachment);
            mAnimation.setImageDrawable(null);
            mAnimation.setFailureListener(error -> {});
        }
        mAnimation = null;
        mError = null;
        mBackground = null;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        if (mAnimation != null) {
            mAnimation.pauseAnimation();
            mAnimation.removeAllLottieOnCompositionLoadedListener();
            mAnimation.removeOnAttachStateChangeListener(mAttachment);
            mAnimation.setImageDrawable(null);
            mAnimation.setFailureListener(error -> {});
        }
        mAnimation = (LottieAnimationView) holder.findViewById(R.id.preview_animation);
        mAnimation.addOnAttachStateChangeListener(mAttachment);
        mError = holder.findViewById(R.id.preview_error);
        ImageView image = (ImageView) holder.findViewById(R.id.preview_background);
        if (mBackground == null) {
            try (InputStream stream = getContext().getAssets()
                    .open("preview/breathe_light_anim_bg.jpg")) {
                mBackground = Drawable.createFromStream(stream, null);
            } catch (IOException error) {
                mBackground = null;
            }
        }
        image.setImageDrawable(mBackground);
        mAnimation.setRepeatCount(ValueAnimator.INFINITE);
        LottieAnimationView bound = mAnimation;
        mAnimation.addLottieOnCompositionLoadedListener(composition -> {
            if (mAnimation == bound) updatePlayback();
        });
        mAnimation.setFailureListener(error -> {
            if (mAnimation == bound && mError != null) mError.setVisibility(View.VISIBLE);
        });
        bindAnimation();
    }

    private void bindAnimation() {
        if (mAnimation == null) return;
        mAnimation.pauseAnimation();
        mError.setVisibility(mBackground == null ? View.VISIBLE : View.GONE);
        mAnimation.setAnimation(mAsset);
        updatePlayback();
    }

    private void updatePlayback() {
        if (mAnimation == null) return;
        if (mResumed && mAnimation.isAttachedToWindow() && ValueAnimator.areAnimatorsEnabled()) {
            mAnimation.playAnimation();
        } else {
            mAnimation.pauseAnimation();
            if (!ValueAnimator.areAnimatorsEnabled()) mAnimation.setProgress(0.25f);
        }
    }
}
