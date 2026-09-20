/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.preference;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Checkable;
import android.widget.RadioButton;

import androidx.core.graphics.ColorUtils;

import java.util.function.IntConsumer;

import org.meizu.awlight.R;
import org.meizu.awlight.utils.LightPreferences;

public final class LightColorChip extends View implements Checkable {
    private static final int[] COLOR_NAMES = {
        R.string.color_white, R.string.color_red, R.string.color_green, R.string.color_blue,
        R.string.color_orange, R.string.color_yellow, R.string.color_pink, R.string.color_lime,
        R.string.color_cyan, R.string.color_purple
    };
    // Stock reminder_light_ui_colors; IPC continues to use calibrated COLOR_PRESETS.
    private static final int[] COLOR_SWATCHES = {
        0xffffffff, 0xffff5757, 0xff59e742, 0xff26cbff, 0xffff833e,
        0xfffff066, 0xfff051e0, 0xffabf137, 0xff50f0f2, 0xffc13dff
    };
    public static final int COUNT = COLOR_SWATCHES.length;
    private static final int[] DISPLAY_ORDER = {0, 1, 4, 5, 6, 7, 2, 8, 3, 9};

    private final int mColor;
    private final int mRequestColor;
    private final int mSurface;
    private final int mOnSurface;
    private final int mAccent;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mCheck = new Path();
    private boolean mChecked;

    public LightColorChip(Context context, int index, IntConsumer onSelected) {
        super(context);
        index = DISPLAY_ORDER[index];
        mColor = COLOR_SWATCHES[index];
        mRequestColor = LightPreferences.COLOR_PRESETS[index];
        mSurface = context.getColor(com.android.internal.R.color.materialColorSurfaceBright);
        mOnSurface = context.getColor(com.android.internal.R.color.materialColorOnSurface);
        mAccent = context.getColor(com.android.internal.R.color.materialColorPrimary);
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(context.getText(COLOR_NAMES[index]));
        setTooltipText(context.getText(COLOR_NAMES[index]));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        setBackground(new RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(mAccent, 48)), null, mask));
        setOnClickListener(view -> {
            if (mChecked) return;
            performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK);
            onSelected.accept(mRequestColor);
        });
    }

    public int getColor() {
        return mRequestColor;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float x = getWidth() / 2f;
        float y = getHeight() / 2f;
        float radius = Math.min(dp(17), Math.min(getWidth(), getHeight()) / 2f - dp(6));
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mColor);
        canvas.drawCircle(x, y, radius, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(dp(1));
        mPaint.setColor(ColorUtils.blendARGB(mSurface, mOnSurface, 0.3f));
        canvas.drawCircle(x, y, radius, mPaint);
        if (mChecked || isFocused()) {
            mPaint.setStrokeWidth(dp(2));
            mPaint.setColor(mAccent);
            canvas.drawCircle(x, y, radius + dp(4), mPaint);
        }
        if (mChecked) {
            mPaint.setColor(Color.luminance(mColor) > 0.45 ? Color.BLACK : Color.WHITE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mCheck.reset();
            mCheck.moveTo(x - dp(6), y);
            mCheck.lineTo(x - dp(1), y + dp(5));
            mCheck.lineTo(x + dp(7), y - dp(5));
            canvas.drawPath(mCheck, mPaint);
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    public void setChecked(boolean checked) {
        if (mChecked == checked) return;
        mChecked = checked;
        setSelected(checked);
        invalidate();
    }

    @Override
    public boolean isChecked() {
        return mChecked;
    }

    @Override
    public void toggle() {
        setChecked(!mChecked);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(RadioButton.class.getName());
        info.setCheckable(true);
        info.setChecked(mChecked);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
