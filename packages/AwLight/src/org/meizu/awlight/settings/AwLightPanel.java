/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.settings;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.slider.Slider;

import java.text.NumberFormat;

import org.meizu.awlight.AwLightSettingsActivity;
import org.meizu.awlight.R;
import org.meizu.awlight.manager.AwLightController;
import org.meizu.awlight.preference.LightColorChip;
import org.meizu.awlight.utils.LightPreferences;

public final class AwLightPanel {
    private static final long SLIDER_HAPTIC_INTERVAL_MS = 40;
    private static final int[] MODE_NAMES = {
        R.string.mode_solid, R.string.mode_cycle, R.string.mode_rotate
    };
    private static final int[] MODE_ICONS = {
        R.drawable.ic_light_ring, R.drawable.ic_pattern_cycle, R.drawable.ic_pattern_rotate
    };
    private final Activity mActivity;
    private final AwLightController mController;
    private final BoundedScrollView mScroll;
    private final MaterialSwitch mRing;
    private final Slider mBrightness;
    private final MaterialButtonToggleGroup mPatterns;
    private final MaterialButton[] mPatternButtons = new MaterialButton[3];
    private final GridLayout mColors;
    private final LightColorChip[] mColorChips = new LightColorChip[LightColorChip.COUNT];
    private final int mSurface;
    private final int mOnSurface;
    private final NumberFormat mPercent;
    private final AwLightController.Observer mObserver = this::render;
    private AwLightController.Snapshot mState;
    private boolean mStarted;
    private boolean mRendering;
    private boolean mDraggingBrightness;
    private long mLastSliderHapticTime;
    private final Runnable mUpdateBrightness = this::updateTemporaryBrightness;

    public AwLightPanel(Activity activity, AwLightController controller) {
        mActivity = activity;
        mController = controller;
        mSurface = activity.getColor(com.android.internal.R.color.materialColorSurfaceBright);
        mOnSurface = themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
        mPercent = NumberFormat.getPercentInstance(
                activity.getResources().getConfiguration().getLocales().get(0));
        mPercent.setMaximumFractionDigits(0);

        FrameLayout surface = new FrameLayout(activity);
        MaterialShapeDrawable background = new MaterialShapeDrawable(
                ShapeAppearanceModel.builder().setAllCornerSizes(dp(28)).build());
        background.setFillColor(ColorStateList.valueOf(mSurface));
        surface.setBackground(background);
        surface.setClipToOutline(true);
        mScroll = new BoundedScrollView(activity);
        surface.addView(mScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(18));
        mScroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(activity);
        title.setText(R.string.app_name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(mOnSurface);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setAccessibilityHeading(true);
        content.addView(title, row(0, 14));

        mRing = new MaterialSwitch(activity);
        mRing.setText(R.string.app_name);
        mRing.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mRing.setMinHeight(dp(56));
        mRing.setOnCheckedChangeListener((button, checked) -> {
            if (!mRendering) mController.setManualEnabled(checked);
        });
        content.addView(mRing, row(0, 12));

        mBrightness = new Slider(activity);
        mBrightness.setId(View.generateViewId());
        mBrightness.setContentDescription(activity.getText(R.string.auto_brightness));
        mBrightness.setValueTo(100);
        mBrightness.setStepSize(1);
        mBrightness.setTickVisible(false);
        mBrightness.setLabelFormatter(value -> mPercent.format(value / 100.0));
        mBrightness.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser || mRendering) return;
            performSliderHaptic();
            if (mDraggingBrightness) {
                mBrightness.removeCallbacks(mUpdateBrightness);
                mBrightness.postOnAnimation(mUpdateBrightness);
            } else {
                commitBrightness();
            }
        });
        mBrightness.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) {
                mDraggingBrightness = true;
            }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                mDraggingBrightness = false;
                mBrightness.removeCallbacks(mUpdateBrightness);
                commitBrightness();
            }
        });
        content.addView(mBrightness, row(0, 12));

        mPatterns = new MaterialButtonToggleGroup(activity);
        mPatterns.setBaselineAligned(false);
        mPatterns.setGravity(Gravity.CENTER_VERTICAL);
        mPatterns.setSingleSelection(true);
        mPatterns.setSelectionRequired(true);
        for (int i = 0; i < mPatternButtons.length; ++i) {
            MaterialButton button = new MaterialButton(activity, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setId(View.generateViewId());
            button.setContentDescription(activity.getText(MODE_NAMES[i]));
            button.setTooltipText(activity.getText(MODE_NAMES[i]));
            button.setGravity(Gravity.CENTER);
            button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            button.setPaddingRelative(dp(8), dp(8), dp(8), dp(8));
            button.setIconSize(dp(24));
            button.setIconPadding(0);
            button.setInsetTop(0);
            button.setInsetBottom(0);
            button.setCornerRadius(dp(18));
            mPatternButtons[i] = button;
            mPatterns.addView(button);
        }
        mPatterns.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (mRendering || !checked) return;
            for (int i = 0; i < mPatternButtons.length; ++i) {
                if (mPatternButtons[i].getId() == checkedId) {
                    mPatternButtons[i].performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK);
                    mController.setManualEffect(LightPreferences.MANUAL_EFFECTS[i]);
                    break;
                }
            }
        });
        content.addView(mPatterns, row(0, 12));

        mColors = new GridLayout(activity);
        for (int i = 0; i < mColorChips.length; ++i) {
            LightColorChip chip = new LightColorChip(activity, i, mController::setManualColor);
            mColorChips[i] = chip;
            mColors.addView(chip);
        }
        content.addView(mColors, row(0, 8));

        MaterialButton close = new MaterialButton(activity);
        close.setText(com.android.internal.R.string.close_button_text);
        close.setAllCaps(false);
        close.setMinHeight(dp(48));
        close.setCornerRadius(dp(24));
        close.setOnClickListener(view -> activity.finish());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.gravity = Gravity.END;
        content.addView(close, closeParams);
        activity.setContentView(surface);
    }

    public void start() {
        if (mStarted) return;
        configureWindow();
        mStarted = true;
        render(mController.getSnapshot());
        if (!mActivity.isFinishing()) {
            mController.addObserver(mObserver);
        }
    }

    public void stop() {
        mBrightness.removeCallbacks(mUpdateBrightness);
        if (mDraggingBrightness) commitBrightness();
        mController.clearTemporaryManualBrightness();
        mStarted = false;
        mDraggingBrightness = false;
        mController.removeObserver(mObserver);
    }

    private void configureWindow() {
        WindowManager manager = mActivity.getSystemService(WindowManager.class);
        WindowMetrics metrics = manager.getCurrentWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        int availableWidth = Math.max(1, bounds.width() - insets.left - insets.right);
        int availableHeight = Math.max(1, bounds.height() - insets.top - insets.bottom);
        int width = Math.max(1, Math.min(dp(560), Math.round(availableWidth * 0.92f)));
        mScroll.setMaximumHeight(Math.max(1, Math.round(availableHeight * 0.8f)));
        boolean vertical = width - dp(48) < dp(144);
        mPatterns.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int i = 0; i < mPatternButtons.length; ++i) {
            MaterialButton button = mPatternButtons[i];
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    vertical ? ViewGroup.LayoutParams.MATCH_PARENT : 0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, vertical ? 0 : 1);
            button.setLayoutParams(params);
            button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            button.setIconResource(MODE_ICONS[i]);
            button.setMinHeight(dp(56));
        }
        int columns = Math.max(1, Math.min(5, (width - dp(48)) / dp(52)));
        mColors.setColumnCount(GridLayout.UNDEFINED);
        for (int i = 0; i < mColorChips.length; ++i) {
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(i / columns, GridLayout.CENTER),
                    GridLayout.spec(i % columns, GridLayout.CENTER, 1f));
            params.width = dp(48);
            params.height = dp(48);
            params.setMargins(dp(2), dp(3), dp(2), dp(3));
            mColorChips[i].setLayoutParams(params);
        }
        mColors.setColumnCount(columns);
        Window window = mActivity.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.CENTER);
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void performSliderHaptic() {
        long now = SystemClock.uptimeMillis();
        if (now - mLastSliderHapticTime < SLIDER_HAPTIC_INTERVAL_MS) return;
        mBrightness.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK);
        mLastSliderHapticTime = now;
    }

    private void updateTemporaryBrightness() {
        if (mStarted && mDraggingBrightness && mState != null && mState.canEdit) {
            mController.setTemporaryManualBrightness(mBrightness.getValue() / 100f);
        }
    }

    private void commitBrightness() {
        if (mStarted && mState != null && mState.canEdit) {
            mController.setManualBrightness(mBrightness.getValue() / 100f);
        }
    }

    private void render(AwLightController.Snapshot state) {
        if (!mStarted) return;
        mState = state;
        if (!state.canEdit && !AwLightSettingsActivity.canOpen(mActivity)) {
            mActivity.finish();
            return;
        }
        mRendering = true;
        try {
            mRing.setChecked(state.manualRequested);
            mRing.setEnabled(state.canToggleManual);
            mRing.setStateDescription(state.manualRequested && state.safetyBlocked
                    ? mActivity.getString(R.string.safety_blocked) : null);
            if (!mDraggingBrightness) {
                int percent = Math.round(state.manualBrightness * 100);
                mBrightness.setValue(percent);
            }
            mBrightness.setEnabled(state.canEdit);
            for (int i = 0; i < mPatternButtons.length; ++i) {
                MaterialButton button = mPatternButtons[i];
                button.setEnabled(state.canEdit);
                if (LightPreferences.MANUAL_EFFECTS[i] == state.preferences.manualEffect) {
                    mPatterns.check(button.getId());
                }
            }
            mColors.setVisibility(state.preferences.manualEffect == 47 ? View.VISIBLE : View.GONE);
            for (int i = 0; i < mColorChips.length; ++i) {
                mColorChips[i].setEnabled(state.canEdit && state.preferences.manualEffect == 47);
                mColorChips[i].setChecked(
                        mColorChips[i].getColor() == state.preferences.manualColor);
            }
        } finally {
            mRendering = false;
        }
    }

    private int themeColor(int attribute, int fallback) {
        TypedArray values = mActivity.obtainStyledAttributes(new int[] {attribute});
        try {
            return values.getColor(0, fallback);
        } finally {
            values.recycle();
        }
    }

    private LinearLayout.LayoutParams row(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        params.bottomMargin = dp(bottom);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    private static final class BoundedScrollView extends ScrollView {
        private int mMaximumHeight = Integer.MAX_VALUE;

        BoundedScrollView(Context context) {
            super(context);
        }

        void setMaximumHeight(int height) {
            mMaximumHeight = height;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int size = View.MeasureSpec.getSize(heightMeasureSpec);
            int limit = View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED
                    ? mMaximumHeight : Math.min(mMaximumHeight, size);
            super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
        }
    }
}
