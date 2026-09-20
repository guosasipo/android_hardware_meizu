/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.manager;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserManager;
import android.provider.Settings;
import android.view.Display;

import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.app.IVoiceInteractionSessionListener;

import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

final class AssistantMonitor {
    private static final long[] RETRY_MS = {250, 1000, 3000, 10000};
    private static final int SESSION_SHOWN = 0;
    private static final int SESSION_HIDDEN = 1;
    private static final int WINDOW_SHOWN = 2;
    private static final int WINDOW_HIDDEN = 3;

    static final class State {
        final boolean available;
        final boolean visible;

        State(boolean available, boolean visible) {
            this.available = available;
            this.visible = visible;
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final UserManager mUsers;
    private final ContentResolver mResolver;
    private final Consumer<State> mCallback;
    private final ContentObserver mProviderObserver;
    private final int mUserId;
    private boolean mObserverRegistered;
    private boolean mEnabled;
    private boolean mOwner;
    private boolean mObservedVisible;
    private volatile long mEventEpoch;
    private ComponentName mProvider;
    private IBinder mBinder;
    private IBinder.DeathRecipient mDeath;
    private IVoiceInteractionManagerService mService;
    private IVoiceInteractionSessionListener mListener;
    private boolean mRegistered;
    private IBinder mTaskBinder;
    private IActivityTaskManager mTasks;
    private TaskStackListener mTaskListener;
    private boolean mArmed;
    private boolean mNativeWindowVisible;
    private boolean mTaskClosed;
    private boolean mBaselineReady;
    private volatile Map<Integer, ComponentName> mVisibleTaskSnapshot;
    private final Map<Integer, ComponentName> mBaseline = new LinkedHashMap<>();
    private int mTrackedTask = -1;
    private ComponentName mTrackedActivity;
    private boolean mTrackedVisible;
    private int mAttempts;
    private boolean mRetryScheduled;
    private State mState = new State(false, false);
    private final Runnable mRefreshTasks = () -> {
        if (!mEnabled || mTaskListener == null) return;
        if (!ownerAllowed() || (mArmed && !mTaskClosed)) refreshInternal();
        else visibleProviderTasks();
    };
    private final Runnable mRetry = () -> {
        mRetryScheduled = false;
        refreshInternal();
    };

    AssistantMonitor(Context context, Handler handler, Consumer<State> callback) {
        mContext = context;
        mHandler = handler;
        mUsers = context.getSystemService(UserManager.class);
        mResolver = context.getContentResolver();
        mUserId = context.getUserId();
        mCallback = callback;
        mProviderObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                refresh();
            }
        };
    }

    void setEnabled(boolean enabled) {
        if (mEnabled == enabled) return;
        mEnabled = enabled;
        invalidateVisibility();
        refresh();
    }

    void refresh() {
        cancelRetry();
        mAttempts = 0;
        refreshInternal();
    }

    private void refreshInternal() {
        try {
            boolean owner = ownerAllowed();
            if (owner != mOwner) invalidateVisibility();
            mOwner = owner;
            if (!owner || mContext.checkSelfPermission(
                    Manifest.permission.ACCESS_VOICE_INTERACTION_SERVICE)
                    != PackageManager.PERMISSION_GRANTED || mContext.checkSelfPermission(
                    Manifest.permission.MANAGE_ACTIVITY_TASKS) != PackageManager.PERMISSION_GRANTED) {
                invalidateVisibility();
                stopTaskListener();
                cancelRetry();
                publish(false);
                return;
            }
            if (!mObserverRegistered) {
                mResolver.registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.VOICE_INTERACTION_SERVICE),
                        false, mProviderObserver);
                mObserverRegistered = true;
            }
            String setting = Settings.Secure.getString(mResolver,
                    Settings.Secure.VOICE_INTERACTION_SERVICE);
            ComponentName provider = setting == null ? null : ComponentName.unflattenFromString(setting);
            if (!Objects.equals(provider, mProvider)) {
                mProvider = provider;
                invalidateVisibility();
            }
            if (provider == null) {
                stopTaskListener();
                cancelRetry();
                publish(false);
                return;
            }
            IBinder binder = ServiceManager.checkService(Context.VOICE_INTERACTION_MANAGER_SERVICE);
            if (binder == null) {
                unavailable();
                return;
            }
            if (binder != mBinder) attach(binder);
            if (!provider.equals(mService.getActiveServiceComponentName())) {
                unavailable();
                return;
            }
            if (mEnabled && !mRegistered) {
                mService.registerVoiceInteractionSessionListener(mListener);
                mRegistered = true;
            }
            if (mEnabled) {
                ensureTaskListener();
                refreshTaskVisibility();
            } else {
                stopTaskListener();
            }
            cancelRetry();
            mAttempts = 0;
            publish(true);
        } catch (RemoteException | RuntimeException e) {
            if (mBinder != null && !mBinder.isBinderAlive()) detach();
            unavailable();
        }
    }

    private boolean ownerAllowed() {
        try {
            return mUsers != null && mUsers.isUserForeground()
                    && mUsers.isUserUnlocked() && !mUsers.isManagedProfile();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void attach(IBinder binder) throws RemoteException {
        detach();
        mBinder = binder;
        mService = IVoiceInteractionManagerService.Stub.asInterface(binder);
        mListener = new IVoiceInteractionSessionListener.Stub() {
            @Override
            public void onVoiceSessionShown() { postVoiceEvent(binder, SESSION_SHOWN); }

            @Override
            public void onVoiceSessionHidden() { postVoiceEvent(binder, SESSION_HIDDEN); }

            @Override
            public void onVoiceSessionWindowVisibilityChanged(boolean visible) {
                postVoiceEvent(binder, visible ? WINDOW_SHOWN : WINDOW_HIDDEN);
            }

            @Override
            public void onSetUiHints(Bundle hints) {}

            @Override
            public void onSetInvocationEffectEnabled(boolean enabled) {}
        };
        mDeath = () -> mHandler.post(() -> {
            if (mBinder != binder) return;
            detach();
            unavailable();
        });
        try {
            binder.linkToDeath(mDeath, 0);
        } catch (RemoteException | RuntimeException e) {
            detach();
            throw e;
        }
    }

    private void postVoiceEvent(IBinder binder, int event) {
        long epoch = mEventEpoch;
        Map<Integer, ComponentName> baseline = event == SESSION_SHOWN ? mVisibleTaskSnapshot : null;
        mHandler.post(() -> {
            if (binder != mBinder || epoch != mEventEpoch || !mEnabled) return;
            refreshInternal();
            if (binder != mBinder || epoch != mEventEpoch || !mState.available || !mEnabled) return;
            switch (event) {
                case SESSION_SHOWN -> beginInvocation(baseline);
                case SESSION_HIDDEN -> clearInvocation();
                case WINDOW_SHOWN -> {
                    mArmed = true;
                    mNativeWindowVisible = true;
                }
                case WINDOW_HIDDEN -> {
                    boolean wasVisible = mNativeWindowVisible;
                    mNativeWindowVisible = false;
                    if (wasVisible && mTrackedTask < 0) clearInvocation();
                    else refreshTaskVisibility();
                }
            }
            mObservedVisible = mNativeWindowVisible || mTrackedVisible;
            publish(true);
        });
    }

    private void beginInvocation(Map<Integer, ComponentName> baseline) {
        mArmed = true;
        mTaskClosed = false;
        if (mNativeWindowVisible) return;
        mBaseline.clear();
        mBaselineReady = baseline != null;
        if (baseline != null) mBaseline.putAll(baseline);
        Map<Integer, ComponentName> tasks = visibleProviderTasks();
        mTrackedVisible = tasks != null && mTrackedTask >= 0
                && Objects.equals(tasks.get(mTrackedTask), mTrackedActivity);
        if (!mTrackedVisible) {
            mTrackedTask = -1;
            mTrackedActivity = null;
        }
        applyTaskVisibility(tasks);
    }

    private void ensureTaskListener() {
        try {
            IBinder binder = ServiceManager.checkService(Context.ACTIVITY_TASK_SERVICE);
            if (binder != null && binder == mTaskBinder && mTaskListener != null) return;
            stopTaskListener();
            if (binder == null) return;
            mTaskBinder = binder;
            mTasks = IActivityTaskManager.Stub.asInterface(binder);
            mTaskListener = new TaskStackListener() {
                @Override
                public void onTaskStackChanged() { postTaskEvent(this); }

                @Override
                public void onTaskMovedToFront(ActivityManager.RunningTaskInfo info) {
                    postTaskEvent(this);
                }

                @Override
                public void onTaskMovedToBack(ActivityManager.RunningTaskInfo info) {
                    postTaskEvent(this);
                }

                @Override
                public void onTaskRemoved(int taskId) { postTaskEvent(this); }

                @Override
                public void onTaskFocusChanged(int taskId, boolean focused) { postTaskEvent(this); }

                @Override
                public void onTaskDisplayChanged(int taskId, int displayId) { postTaskEvent(this); }
            };
            mTasks.registerTaskStackListener(mTaskListener);
            visibleProviderTasks();
        } catch (RemoteException | RuntimeException e) {
            stopTaskListener();
        }
    }

    private void stopTaskListener() {
        mHandler.removeCallbacks(mRefreshTasks);
        IActivityTaskManager tasks = mTasks;
        TaskStackListener listener = mTaskListener;
        mTaskBinder = null;
        mTasks = null;
        mTaskListener = null;
        mVisibleTaskSnapshot = null;
        if (tasks != null && listener != null) {
            try {
                tasks.unregisterTaskStackListener(listener);
            } catch (RemoteException | RuntimeException ignored) {
            }
        }
    }

    private void postTaskEvent(TaskStackListener listener) {
        long epoch = mEventEpoch;
        mHandler.post(() -> {
            if (listener != mTaskListener || epoch != mEventEpoch
                    || !mEnabled) return;
            mHandler.removeCallbacks(mRefreshTasks);
            mHandler.post(mRefreshTasks);
        });
    }

    private Map<Integer, ComponentName> visibleProviderTasks() {
        if (mTasks == null || mProvider == null) {
            mVisibleTaskSnapshot = null;
            return null;
        }
        try {
            Map<Integer, ComponentName> tasks = new LinkedHashMap<>();
            for (ActivityTaskManager.RootTaskInfo info :
                    mTasks.getAllRootTaskInfosOnDisplay(Display.DEFAULT_DISPLAY)) {
                if (info.userId == mUserId && info.visible && info.topActivity != null
                        && info.childTaskIds != null && info.childTaskIds.length == 1
                        && info.childTaskUserIds != null && info.childTaskUserIds.length == 1
                        && info.childTaskUserIds[0] == mUserId
                        && mProvider.getPackageName().equals(info.topActivity.getPackageName())) {
                    tasks.put(info.taskId, info.topActivity);
                }
            }
            mVisibleTaskSnapshot = Map.copyOf(tasks);
            return tasks;
        } catch (RemoteException | RuntimeException e) {
            mVisibleTaskSnapshot = null;
            return null;
        }
    }

    private void refreshTaskVisibility() {
        if (!mArmed || (mNativeWindowVisible && mTrackedTask < 0)) {
            mObservedVisible = mNativeWindowVisible;
            return;
        }
        if (mTaskClosed) {
            mObservedVisible = mNativeWindowVisible;
            return;
        }
        applyTaskVisibility(visibleProviderTasks());
    }

    private void applyTaskVisibility(Map<Integer, ComponentName> tasks) {
        if (tasks == null) {
            mTrackedVisible = false;
        } else if (mTrackedTask >= 0) {
            mTrackedVisible = Objects.equals(tasks.get(mTrackedTask), mTrackedActivity);
            if (!mTrackedVisible) {
                mTrackedTask = -1;
                mTrackedActivity = null;
                mTaskClosed = true;
                if (!mNativeWindowVisible) mArmed = false;
                mBaseline.clear();
            }
        } else if (!mBaselineReady) {
            mBaseline.putAll(tasks);
            mBaselineReady = true;
        } else {
            for (Map.Entry<Integer, ComponentName> task : tasks.entrySet()) {
                if (!Objects.equals(mBaseline.get(task.getKey()), task.getValue())) {
                    mTrackedTask = task.getKey();
                    mTrackedActivity = task.getValue();
                    mTrackedVisible = true;
                    break;
                }
            }
        }
        mObservedVisible = mNativeWindowVisible || mTrackedVisible;
    }

    private void clearInvocation() {
        mHandler.removeCallbacks(mRefreshTasks);
        mArmed = false;
        mNativeWindowVisible = false;
        mTaskClosed = true;
        mBaselineReady = false;
        mBaseline.clear();
        mTrackedTask = -1;
        mTrackedActivity = null;
        mTrackedVisible = false;
        mObservedVisible = false;
    }

    private void unavailable() {
        invalidateVisibility();
        stopTaskListener();
        publish(false);
        if (!mEnabled || !mOwner || mRetryScheduled || mAttempts >= RETRY_MS.length) return;
        mRetryScheduled = true;
        mHandler.postDelayed(mRetry, RETRY_MS[mAttempts++]);
    }

    private void cancelRetry() {
        mHandler.removeCallbacks(mRetry);
        mRetryScheduled = false;
    }

    private void invalidateVisibility() {
        mVisibleTaskSnapshot = null;
        ++mEventEpoch;
        clearInvocation();
    }

    private void detach() {
        stopTaskListener();
        if (mBinder != null && mDeath != null) {
            try {
                mBinder.unlinkToDeath(mDeath, 0);
            } catch (RuntimeException ignored) {
            }
        }
        mBinder = null;
        mService = null;
        mListener = null;
        mDeath = null;
        mRegistered = false;
        invalidateVisibility();
    }

    private void publish(boolean available) {
        boolean visible = available && mEnabled && mObservedVisible;
        if (mState.available == available && mState.visible == visible) return;
        mState = new State(available, visible);
        mCallback.accept(mState);
    }
}
