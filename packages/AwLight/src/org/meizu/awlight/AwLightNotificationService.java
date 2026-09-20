/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.meizu.awlight.manager.AwLightController;

public final class AwLightNotificationService extends NotificationListenerService {
    private static final AtomicLong NEXT_SESSION = new AtomicLong();
    private final long mSession = NEXT_SESSION.incrementAndGet();
    private boolean mConnected;

    private AwLightController controller() {
        return AwLightApplication.controller(this);
    }

    @Override
    public void onListenerConnected() {
        mConnected = true;
        try {
            RankingMap ranking = getCurrentRanking();
            StatusBarNotification[] active = getActiveNotifications();
            List<AwLightController.Notice> baseline = new ArrayList<>();
            if (active != null) {
                for (StatusBarNotification notification : active) {
                    baseline.add(AwLightController.Notice.from(notification, ranking));
                }
            }
            controller().listenerConnected(mSession, baseline, getCurrentListenerHints());
        } catch (RuntimeException error) {
            mConnected = false;
            controller().listenerDisconnected(mSession);
        }
    }

    @Override
    public void onListenerDisconnected() {
        mConnected = false;
        controller().listenerDisconnected(mSession);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification, RankingMap ranking) {
        if (mConnected && notification != null) {
            controller().notificationPosted(mSession,
                    AwLightController.Notice.from(notification, ranking));
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification notification, RankingMap ranking,
            int reason) {
        if (mConnected && notification != null) {
            controller().notificationRemoved(mSession, notification.getKey());
        }
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap ranking) {
        if (!mConnected || ranking == null) return;
        List<AwLightController.Rank> ranks = new ArrayList<>();
        for (String key : ranking.getOrderedKeys()) {
            ranks.add(AwLightController.Rank.from(key, ranking));
        }
        controller().rankingsChanged(mSession, ranks);
    }

    @Override
    public void onListenerHintsChanged(int hints) {
        if (mConnected) controller().listenerHintsChanged(mSession, hints);
    }

    @Override
    public void onInterruptionFilterChanged(int interruptionFilter) {
        if (!mConnected) return;
        try {
            onNotificationRankingUpdate(getCurrentRanking());
        } catch (RuntimeException error) {
            onListenerDisconnected();
        }
    }

    @Override
    public void onDestroy() {
        onListenerDisconnected();
        super.onDestroy();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.print(controller().dumpSnapshot());
    }
}
