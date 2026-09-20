/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.preference;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.GridLayout;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import org.meizu.awlight.R;
import org.meizu.awlight.utils.LightPreferences;

public final class LightColorPreference extends Preference {
    private int mValue = LightPreferences.COLOR_PRESETS[0];
    private GridLayout mGrid;

    public LightColorPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.preference_light_colors);
        setSelectable(false);
        setPersistent(false);
    }

    public void setValue(int value) {
        if (mValue == value) return;
        mValue = value;
        updateChecked();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        mGrid = (GridLayout) holder.findViewById(R.id.light_color_grid);
        mGrid.removeAllViews();
        for (int i = 0; i < LightColorChip.COUNT; ++i) {
            LightColorChip chip = new LightColorChip(getContext(), i, value -> {
                if (callChangeListener(value)) setValue(value);
            });
            chip.setEnabled(isEnabled());
            mGrid.addView(chip);
        }
        updateChecked();
    }

    @Override
    public void onDetached() {
        mGrid = null;
        super.onDetached();
    }

    private void updateChecked() {
        if (mGrid == null) return;
        for (int i = 0; i < mGrid.getChildCount(); ++i) {
            LightColorChip chip = (LightColorChip) mGrid.getChildAt(i);
            chip.setChecked(chip.getColor() == mValue);
        }
    }

    public static final class ColorGrid extends GridLayout {
        private int mColumns;

        public ColorGrid(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        public void onViewAdded(View child) {
            super.onViewAdded(child);
            mColumns = 0;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int available = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
            int columns = Math.max(1, Math.min(5, available / dp(52)));
            if (mColumns != columns) {
                mColumns = columns;
                setColumnCount(GridLayout.UNDEFINED);
                for (int i = 0; i < getChildCount(); ++i) {
                    GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                            GridLayout.spec(i / columns, GridLayout.CENTER),
                            GridLayout.spec(i % columns, GridLayout.CENTER, 1f));
                    params.width = dp(48);
                    params.height = dp(48);
                    params.setMargins(dp(2), dp(3), dp(2), dp(3));
                    getChildAt(i).setLayoutParams(params);
                }
                setColumnCount(columns);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        private int dp(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }
    }
}
