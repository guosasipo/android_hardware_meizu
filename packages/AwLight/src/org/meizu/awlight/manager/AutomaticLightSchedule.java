/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.manager;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.service.notification.ScheduleCalendar;
import android.service.notification.ZenModeConfig.ScheduleInfo;

import java.util.TimeZone;

import org.meizu.awlight.ScheduleReceiver;
import org.meizu.awlight.utils.LightPreferences;

final class AutomaticLightSchedule {
    private final AlarmManager mAlarms;
    private final PendingIntent mBoundary;
    private final ScheduleCalendar mCalendar = new ScheduleCalendar();
    private int mStart = -1;
    private int mEnd = -1;
    private boolean mPermissionKnown;
    private boolean mDirty = true;
    private boolean mEnabled;
    private boolean mExactAlarmsAllowed;
    private boolean mFailed;
    private long mNext;

    AutomaticLightSchedule(Context context) {
        mAlarms = context.getSystemService(AlarmManager.class);
        mBoundary = PendingIntent.getBroadcast(context, 0,
                new Intent(context, ScheduleReceiver.class).setAction(ScheduleReceiver.ACTION),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    void refresh() {
        boolean allowed = false;
        try {
            allowed = mAlarms != null && mAlarms.canScheduleExactAlarms();
        } catch (RuntimeException ignored) {
        }
        mDirty |= !mPermissionKnown || mExactAlarmsAllowed != allowed || mFailed;
        mExactAlarmsAllowed = allowed;
        mPermissionKnown = true;
    }

    void timeChanged() {
        mDirty = true;
    }

    void update(boolean automaticOwner, LightPreferences.Values values) {
        boolean enabled = automaticOwner && values.scheduleEnabled;
        if (!mPermissionKnown || (enabled && !mEnabled)) refresh();
        boolean changed = mDirty || mEnabled != enabled || mStart != values.scheduleStartMinute
                || mEnd != values.scheduleEndMinute;
        long now = System.currentTimeMillis();
        if (!changed && (mFailed || mNext == 0 || mNext > now)) return;
        if (changed) {
            ScheduleInfo info = new ScheduleInfo();
            info.days = new int[]{1, 2, 3, 4, 5, 6, 7};
            info.startHour = values.scheduleStartMinute / 60;
            info.startMinute = values.scheduleStartMinute % 60;
            info.endHour = values.scheduleEndMinute / 60;
            info.endMinute = values.scheduleEndMinute % 60;
            mCalendar.setTimeZone(TimeZone.getDefault());
            mCalendar.setSchedule(info);
        }
        mEnabled = enabled;
        mStart = values.scheduleStartMinute;
        mEnd = values.scheduleEndMinute;
        mDirty = false;
        long next = enabled && mExactAlarmsAllowed
                && values.scheduleStartMinute != values.scheduleEndMinute
                ? mCalendar.getNextChangeTime(now) : 0;
        if (!changed && next == mNext) return;
        mNext = next;
        try {
            if (next == 0) {
                if (mAlarms != null) mAlarms.cancel(mBoundary);
            } else {
                mAlarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, mBoundary);
            }
            mFailed = false;
        } catch (RuntimeException error) {
            mFailed = true;
        }
    }

    boolean isAllowed() {
        return !mEnabled || (mExactAlarmsAllowed && !mFailed
                && (mStart == mEnd || mCalendar.isInSchedule(System.currentTimeMillis())));
    }

    boolean exactAlarmsAllowed() {
        return mExactAlarmsAllowed;
    }

    long nextBoundary() {
        return mNext;
    }

    boolean failed() {
        return mFailed;
    }
}
