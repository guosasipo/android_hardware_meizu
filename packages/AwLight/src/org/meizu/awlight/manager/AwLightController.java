/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.meizu.awlight.manager;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.system.OsConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

import org.meizu.awlight.AwLightTileService;
import org.meizu.awlight.utils.LightPreferences;

import vendor.meizu.hardware.aw_light.IAwLight;
import vendor.meizu.hardware.aw_light.IAwLightClient;
import vendor.meizu.hardware.aw_light.LightRequest;
import vendor.meizu.hardware.aw_light.LightState;

public final class AwLightController {
    private static final String LIGHT_SERVICE = "android.hardware.light.ILights/default";
    private static final int CHARGE_LEVEL = 1;
    private static final int CHARGE_CONNECT = 2;
    private static final int CHARGE_STATUS = 3;
    private static final int NOTIFICATION = 4;
    private static final int VOLUME = 5;
    private static final int CALL = 6;
    private static final int REVERSE_CHARGE = 7;
    private static final int MANUAL = 8;
    private static final int PREVIEW = 9;
    private static final int CAMERA = 10;
    private static final int MUSIC = 11;
    private static final int ASSISTANT = 12;
    private static final int COLOR = 0xffffff;
    private static final int SENSOR_STATIC_DETECT = 65540;
    private static final int SENSOR_POSTURE = 65543;
    private static final int MOTION_POSTURE = 1;
    private static final int MOTION_STATIC = 2;
    private static final long[] RETRY_MS = {0, 250, 1000, 3000, 10000};

    public interface Observer {
        void onStateChanged(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final LightPreferences.Values preferences;
        public final boolean preferencesValid;
        public final boolean connected;
        public final boolean canEdit;
        public final boolean canToggleManual;
        public final boolean motionSupported;
        public final boolean postureSupported;
        public final boolean exactAlarmsAllowed;
        public final boolean assistantAvailable;
        public final boolean powerSaveBlocked;
        public final float autoBrightness;
        public final boolean manualRequested;
        public final float manualBrightness;
        public final boolean manualActive;
        public final boolean safetyBlocked;
        final int activeRequestId;
        final int activeEffect;
        final int lastError;

        private Snapshot(AwLightController controller) {
            preferences = controller.mValues;
            preferencesValid = controller.mPreferencesValid;
            connected = controller.mRemote != null;
            canEdit = controller.canEdit();
            canToggleManual = controller.ownerAllowed()
                    && (controller.mManualRequested || controller.newManualAllowed());
            motionSupported = controller.mStaticSensor != null && controller.mPostureSensor != null;
            postureSupported = controller.mPostureSensor != null;
            exactAlarmsAllowed = controller.mSchedule != null
                    && controller.mSchedule.exactAlarmsAllowed();
            assistantAvailable = controller.mAssistantState.available;
            powerSaveBlocked = controller.powerSaveBlocked();
            autoBrightness = controller.autoBrightness();
            manualRequested = controller.mManualRequested;
            manualBrightness = controller.manualBrightness();
            boolean own = controller.mNativeActive && controller.mOwnOwner != 0
                    && controller.mOwnOwner == controller.mNativeOwner;
            activeRequestId = own ? controller.mNativeRequestId : -1;
            activeEffect = own ? controller.mNativeEffect : 0;
            manualActive = activeRequestId == MANUAL && manualBrightness > 0;
            safetyBlocked = controller.safetyBlocked();
            lastError = controller.mLastError;
        }

        private boolean sameAs(Snapshot other) {
            return preferences.equals(other.preferences)
                    && preferencesValid == other.preferencesValid && connected == other.connected
                    && canEdit == other.canEdit && canToggleManual == other.canToggleManual
                    && motionSupported == other.motionSupported
                    && postureSupported == other.postureSupported
                    && exactAlarmsAllowed == other.exactAlarmsAllowed
                    && assistantAvailable == other.assistantAvailable
                    && powerSaveBlocked == other.powerSaveBlocked
                    && Float.compare(autoBrightness, other.autoBrightness) == 0
                    && manualRequested == other.manualRequested && manualActive == other.manualActive
                    && Float.compare(manualBrightness, other.manualBrightness) == 0
                    && safetyBlocked == other.safetyBlocked
                    && activeRequestId == other.activeRequestId && activeEffect == other.activeEffect
                    && lastError == other.lastError;
        }
    }

    public static final class Rank {
        final String key;
        final boolean eligible;

        Rank(String key, boolean eligible) {
            this.key = key;
            this.eligible = eligible;
        }

        public static Rank from(String key, NotificationListenerService.RankingMap map) {
            NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
            boolean eligible = map != null && map.getRanking(key, ranking)
                    && ranking.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT
                    && ranking.matchesInterruptionFilter()
                    && (ranking.getSuppressedVisualEffects()
                            & NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS) == 0;
            return new Rank(key, eligible);
        }
    }

    public static final class Notice {
        final String key;
        final String packageName;
        final boolean acceptable;
        final boolean onlyOnce;
        final boolean incomingCall;
        boolean ranked;

        private Notice(String key, String packageName, boolean acceptable, boolean onlyOnce,
                boolean ranked, boolean incomingCall) {
            this.key = key;
            this.packageName = packageName;
            this.acceptable = acceptable;
            this.onlyOnce = onlyOnce;
            this.ranked = ranked;
            this.incomingCall = incomingCall;
        }

        public static Notice from(StatusBarNotification sbn,
                NotificationListenerService.RankingMap ranking) {
            Notification notification = sbn.getNotification();
            int user = sbn.getUser().getIdentifier();
            boolean acceptable = (user == UserHandle.myUserId() || user == UserHandle.USER_ALL)
                    && !sbn.isOngoing()
                    && (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) == 0
                    && !notification.isSilent()
                    && !notification.suppressAlertingDueToGrouping();
            return new Notice(sbn.getKey(), sbn.getPackageName(), acceptable,
                    (notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0,
                    Rank.from(sbn.getKey(), ranking).eligible,
                    (user == UserHandle.myUserId() || user == UserHandle.USER_ALL)
                            && Notification.CATEGORY_CALL.equals(notification.category)
                            && notification.extras != null
                            && notification.extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
                                    == Notification.CallStyle.CALL_TYPE_INCOMING);
        }

        boolean eligible() {
            return acceptable && ranked;
        }
    }

    private static final class Spec {
        final int id;
        final int effect;
        final int progress;
        final int priority;
        final int timeout;
        final boolean resume;
        final int color;
        final float strength;
        final int letter;
        final int amplitude;
        final long generation;
        final long expires;

        Spec(int id, int effect, int progress, int priority, int timeout,
                boolean resume, long generation, int color, float strength, int letter) {
            this(id, effect, progress, priority, timeout, resume, generation, color, strength,
                    letter, timeout == 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() + timeout);
        }

        private Spec(int id, int effect, int progress, int priority, int timeout,
                boolean resume, long generation, int color, float strength, int letter,
                long expires) {
            this(id, effect, progress, priority, timeout, resume, generation, color, strength,
                    letter, expires, 0);
        }

        private Spec(int id, int effect, int progress, int priority, int timeout,
                boolean resume, long generation, int color, float strength, int letter,
                long expires, int amplitude) {
            this.id = id;
            this.effect = effect;
            this.progress = progress;
            this.priority = priority;
            this.timeout = timeout;
            this.resume = resume;
            this.color = color;
            this.strength = strength;
            this.letter = letter;
            this.amplitude = amplitude;
            this.generation = generation;
            this.expires = expires;
        }

        Spec withStrength(float strength) {
            return Float.compare(this.strength, strength) == 0 ? this
                    : new Spec(id, effect, progress, priority, timeout, resume, generation,
                            color, strength, letter, expires, amplitude);
        }

        Spec withAmplitude(int amplitude) {
            return this.amplitude == amplitude ? this
                    : new Spec(id, effect, progress, priority, timeout, resume, generation,
                            color, strength, letter, expires, amplitude);
        }

        boolean sameOptions(Spec other) {
            return effect == other.effect && progress == other.progress
                    && priority == other.priority && timeout == other.timeout
                    && resume == other.resume && generation == other.generation
                    && color == other.color && Float.compare(strength, other.strength) == 0
                    && letter == other.letter && amplitude == other.amplitude
                    && expires == other.expires;
        }

        LightRequest request() {
            LightRequest request = new LightRequest();
            request.effect = effect;
            request.color = color;
            request.strength = strength;
            request.letter = letter;
            request.progress = progress;
            request.amplitude = amplitude;
            request.priority = priority;
            request.timeoutMs = timeout == 0 ? 0
                    : (int) Math.max(1, expires - SystemClock.elapsedRealtime());
            request.resume = resume;
            return request;
        }
    }

    private static final class Flight {
        Spec spec;
        boolean canceling;

        Flight(Spec spec) {
            this.spec = spec;
        }

        boolean isStaleVolumeTimeout(LightState state) {
            // Both clocks use BOOTTIME; a renewed lease cannot expire before this deadline.
            return !canceling && spec.id == VOLUME && spec.effect == 49 && !state.active
                    && state.requestId == VOLUME && state.effect == 49
                    && state.error == OsConstants.ETIMEDOUT
                    && spec.expires > SystemClock.elapsedRealtime();
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final PowerManager mPower;
    private final UserManager mUsers;
    private final KeyguardManager mKeyguard;
    private final CameraManager mCameras;
    private final AudioManager mAudio;
    private final SensorManager mSensors;
    private final NotificationManager mNotificationManager;
    private IncomingCallMonitor mCalls;
    private boolean mCallsAvailable;
    private boolean mRinging;
    private PlaybackVisualizer mRingtone;
    private PlaybackVisualizer.Sample mCallSample = new PlaybackVisualizer.Sample(false, false, 0);
    private PlaybackVisualizer mMusic;
    private boolean mMusicAvailable;
    private PlaybackVisualizer.Sample mMusicSample = new PlaybackVisualizer.Sample(false, false, 0);
    private final Runnable mReconcilePlayback = () -> reconcile(false);
    private AssistantMonitor mAssistant;
    private AssistantMonitor.State mAssistantState = new AssistantMonitor.State(false, false);
    private final Runnable mReconcileAssistant = () -> reconcile(false);
    private int mInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
    private CameraLightSession mCamera;
    private long mCameraEvents;
    private ReverseChargingMonitor mReverse;
    private ReverseChargingMonitor.State mReverseState = ReverseChargingMonitor.State.UNKNOWN;
    private boolean mReversePending;
    private long mReverseGeneration;
    private long mReverseDisplays;
    private AutomaticLightSchedule mSchedule;
    private Sensor mStaticSensor;
    private Sensor mPostureSensor;
    private SensorEventListener mMotionListener;
    private int mMotionSensors;
    private int mRegisteredMotionSensors;
    private Boolean mStatic;
    private int mPosture = -1;
    private long mMotionEpoch;
    private long mMotionEvents;
    private long mMotionDisplays;
    private long mMotionFailures;
    private long mVolumeEvents;
    private int mVolumeProgress = -1;
    private final Map<String, Boolean> mTorchStates = new HashMap<>();
    private boolean mTorchRegistered;
    private boolean mThermalRegistered;
    private boolean mSafetyReady;
    private int mThermalStatus = PowerManager.THERMAL_STATUS_SHUTDOWN;
    private LightPreferences mPreferences;
    private LightPreferences.Values mValues = LightPreferences.Values.defaults(false);
    private boolean mPreferencesValid;
    private boolean mManualRequested;
    private float mTemporaryManualBrightness = Float.NaN;
    private float mTemporaryAutoBrightness = Float.NaN;
    private Spec mPreview;
    private long mPreviewGeneration;
    private long mOwnOwner;
    private final Set<Observer> mObservers = new CopyOnWriteArraySet<>();
    private final Map<String, Notice> mNotices = new HashMap<>();
    private final Set<String> mPending = new HashSet<>();
    private final Map<Integer, Spec> mWanted = new HashMap<>();
    private final Map<Integer, Flight> mFlights = new HashMap<>();
    private final Set<Integer> mBlocked = new HashSet<>();
    private volatile String mDump = "AwLight: initializing\n";
    private boolean mReady;
    private boolean mForeground;
    private boolean mUnlocked;
    private boolean mManaged;
    private boolean mInteractive = true;
    private boolean mKeyguardLocked;
    private boolean mPowerSave;
    private boolean mBatteryKnown;
    private int mBatteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;
    private int mLevel;
    private long mChargeGeneration;
    private boolean mConnectArmed;
    private long mListenerSession;
    private boolean mListenerReady;
    private int mListenerHints;
    private IAwLight mRemote;
    private IBinder mRemoteBinder;
    private IBinder.DeathRecipient mDeath;
    private IAwLightClient mClient;
    private long mClientEpoch;
    private int mVersion;
    private int mAttempts;
    private boolean mReconnectScheduled;
    private final Runnable mConnect = this::connect;
    private int mLastError;
    private boolean mNativeActive;
    private int mNativeEffect;
    private long mNativeOwner;
    private int mNativeRequestId = -1;
    private long mPosts;
    private long mRemovals;
    private long mRankingChanges;
    private long mRequests;
    private long mCancels;
    private long mReleases;
    private long mCallbacks;
    private long mConnectFailures;
    private volatile Snapshot mSnapshot = new Snapshot(this);
    private final Runnable mNotifyObservers = this::notifyObservers;

    public AwLightController(Context context) {
        mContext = context;
        mPower = context.getSystemService(PowerManager.class);
        mUsers = context.getSystemService(UserManager.class);
        mKeyguard = context.getSystemService(KeyguardManager.class);
        mCameras = context.getSystemService(CameraManager.class);
        mAudio = context.getSystemService(AudioManager.class);
        mSensors = context.getSystemService(SensorManager.class);
        mNotificationManager = context.getSystemService(NotificationManager.class);
        HandlerThread thread = new HandlerThread("AwLightEvents");
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mHandler.post(this::initialize);
    }

    private void initialize() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_USER_FOREGROUND);
        filter.addAction(Intent.ACTION_USER_BACKGROUND);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED);
        filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
        filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        try {
            mSchedule = new AutomaticLightSchedule(mContext);
            mCalls = new IncomingCallMonitor(mContext, mHandler, ringing -> {
                mRinging = ringing;
                if (!ringing && mRingtone != null) mRingtone.setEnabled(false);
                mHandler.post(() -> reconcile(true));
            });
            mCallsAvailable = mCalls.isAvailable();
            mRingtone = new PlaybackVisualizer(mContext, mHandler,
                    AudioAttributes.USAGE_NOTIFICATION_RINGTONE, sample -> {
                mCallSample = sample;
                mHandler.removeCallbacks(mReconcilePlayback);
                mHandler.post(mReconcilePlayback);
            });
            mMusic = new PlaybackVisualizer(mContext, mHandler, AudioAttributes.USAGE_MEDIA, sample -> {
                mMusicSample = sample;
                mHandler.removeCallbacks(mReconcilePlayback);
                mHandler.post(mReconcilePlayback);
            });
            mMusicAvailable = mMusic.isAvailable();
            mAssistant = new AssistantMonitor(mContext, mHandler, state -> {
                mAssistantState = state;
                mHandler.removeCallbacks(mReconcileAssistant);
                mHandler.post(mReconcileAssistant);
            });
            mAssistant.refresh();
            mCamera = new CameraLightSession(mHandler,
                    () -> mHandler.post(() -> reconcile(true)));
            mReverse = new ReverseChargingMonitor(mContext, mHandler,
                    state -> mHandler.post(() -> reverseChanged(state)));
            if (mSensors != null) {
                try {
                    mPostureSensor = mSensors.getDefaultSensor(SENSOR_POSTURE, true);
                    mStaticSensor = mSensors.getDefaultSensor(SENSOR_STATIC_DETECT, true);
                } catch (RuntimeException error) {
                    ++mMotionFailures;
                }
            }
            mPreferences = new LightPreferences(mContext);
            LightPreferences.Loaded loaded = mPreferences.load();
            mValues = loaded.values;
            mPreferencesValid = loaded.valid;
            Intent battery = mContext.registerReceiver(mReceiver, filter, null, mHandler,
                    Context.RECEIVER_EXPORTED);
            mReady = mPower != null && mUsers != null && mKeyguard != null;
            refreshEnvironment();
            if (battery != null) batteryChanged(battery);
            reconcile(true);
        } catch (RuntimeException error) {
            mReady = false;
            mLastError = 5;
            updateDump();
        }
    }

    public void refresh() {
        mHandler.post(() -> {
            refreshEnvironment();
            if (mSchedule != null) mSchedule.refresh();
            if (mCalls != null) {
                mCallsAvailable = mCalls.isAvailable();
                mCalls.refresh();
            }
            if (mReverse != null) mReverse.refresh();
            if (mRingtone != null) mRingtone.refresh();
            if (mAssistant != null) mAssistant.refresh();
            if (mMusic != null) {
                mMusicAvailable = mMusic.isAvailable();
                mMusic.refresh();
            }
            mMotionSensors = mRegisteredMotionSensors;
            reconcile(false);
            readNativeState();
            updateDump();
        });
    }

    public void onScheduleAlarm(Runnable finished) {
        mHandler.post(() -> {
            try {
                refreshEnvironment();
                reconcile(true);
            } finally {
                finished.run();
            }
        });
    }

    public Snapshot getSnapshot() {
        return mSnapshot;
    }

    public void addObserver(Observer observer) {
        mObservers.add(observer);
        mMainHandler.post(() -> {
            if (mObservers.contains(observer)) observer.onStateChanged(mSnapshot);
        });
        refresh();
    }

    public void removeObserver(Observer observer) {
        mObservers.remove(observer);
    }

    private void notifyObservers() {
        Snapshot snapshot = mSnapshot;
        for (Observer observer : mObservers) observer.onStateChanged(snapshot);
        AwLightTileService.requestUpdate(mContext);
    }

    public void setAutoEnabled(boolean enabled) {
        editPreferences(v -> v.autoEnabled = enabled);
    }

    public void setDisableInPowerSave(boolean enabled) {
        editPreferences(v -> v.disableInPowerSave = enabled);
    }

    public void setDisableWhenFaceUp(boolean enabled) {
        editPreferences(v -> {
            v.disableWhenFaceUp = enabled;
            mMotionSensors = mRegisteredMotionSensors;
        });
    }

    public void setAutoBrightness(float brightness) {
        mHandler.post(() -> {
            mTemporaryAutoBrightness = Float.NaN;
            refreshEnvironment();
            boolean permitted = LightPreferences.isBrightness(brightness)
                    && canEdit() && mPreferences != null;
            if (permitted && (!mPreferencesValid
                    || Float.compare(mValues.autoBrightness, brightness) != 0)) {
                LightPreferences.Values.Builder builder = mValues.buildUpon();
                builder.autoBrightness = brightness;
                savePreferences(builder.build());
            }
            reconcile(permitted);
        });
    }

    public void setTemporaryAutoBrightness(float brightness) {
        if (!LightPreferences.isBrightness(brightness)) return;
        mHandler.post(() -> {
            refreshEnvironment();
            if (!canEdit() || !mPreferencesValid || safetyBlocked()) return;
            if (Float.compare(autoBrightness(), brightness) == 0) return;
            mTemporaryAutoBrightness = brightness;
            reconcile(false);
        });
    }

    public void clearTemporaryAutoBrightness() {
        mHandler.post(() -> {
            if (Float.isNaN(mTemporaryAutoBrightness)) return;
            mTemporaryAutoBrightness = Float.NaN;
            refreshEnvironment();
            reconcile(false);
        });
    }

    private float autoBrightness() {
        return Float.isNaN(mTemporaryAutoBrightness)
                ? mValues.autoBrightness : mTemporaryAutoBrightness;
    }

    public void setChargingEnabled(boolean enabled) {
        editPreferences(v -> v.chargingEnabled = enabled);
    }

    public void setReverseChargingEnabled(boolean enabled) {
        editPreferences(v -> {
            v.reverseChargingEnabled = enabled;
            mReversePending = enabled && mReverseState == ReverseChargingMonitor.State.ACTIVE;
        });
    }

    public void setCallsEnabled(boolean enabled) {
        editPreferences(v -> {
            if (mCalls != null) {
                mCallsAvailable = mCalls.isAvailable();
                mCalls.refresh();
            }
            v.callsEnabled = enabled;
        });
    }

    public void setCallColor(int color) {
        if (!LightPreferences.isNotificationColor(color)) return;
        editPreferences(v -> v.callColor = color);
    }

    public void setCallRhythm(boolean enabled) {
        editPreferences(v -> {
            if (mRingtone != null) mRingtone.refresh();
            v.callRhythm = enabled;
        });
    }

    public void onCameraEvent(String command, IBinder token, long startedAt, int durationMs,
            long eventAt, long sequence, Runnable finished) {
        mHandler.post(() -> {
            try {
                refreshEnvironment();
                if (mCamera == null) return;
                ++mCameraEvents;
                mCamera.accept(command, token, startedAt, durationMs, eventAt, sequence);
                if (!cameraAllowed()) mCamera.clear();
                reconcile(true);
            } finally {
                finished.run();
            }
        });
    }

    public void setNotificationsEnabled(boolean enabled) {
        editPreferences(v -> v.notificationsEnabled = enabled);
    }

    public void setNotificationColor(int color) {
        if (!LightPreferences.isNotificationColor(color)) return;
        editPreferences(v -> v.notificationColor = color);
    }

    public void setScheduleEnabled(boolean enabled) {
        editPreferences(v -> v.scheduleEnabled = enabled);
    }

    public void setScheduleStartMinute(int minute) {
        if (minute < 0 || minute >= 1440) return;
        editPreferences(v -> v.scheduleStartMinute = minute);
    }

    public void setScheduleEndMinute(int minute) {
        if (minute < 0 || minute >= 1440) return;
        editPreferences(v -> v.scheduleEndMinute = minute);
    }

    public void setChargingWhenMoved(boolean enabled) {
        editPreferences(v -> v.chargingWhenMoved = enabled);
    }

    public void setVolumeEnabled(boolean enabled) {
        editPreferences(v -> v.volumeEnabled = enabled);
    }

    public void setMusicEnabled(boolean enabled) {
        editPreferences(v -> {
            mMusicAvailable = mMusic != null && mMusic.isAvailable();
            v.musicEnabled = enabled && mMusicAvailable;
            if (mMusic != null) mMusic.refresh();
        });
    }

    public void setAssistantEnabled(boolean enabled) {
        editPreferences(v -> {
            if (mAssistant != null) mAssistant.refresh();
            v.assistantEnabled = enabled && mAssistantState.available;
        });
    }

    public void setNotificationPackageAllowed(String packageName, boolean allowed) {
        if (!LightPreferences.isPackageName(packageName)) return;
        editPreferences(v -> {
            if (allowed) v.notificationBlockedPackages.remove(packageName);
            else v.notificationBlockedPackages.add(packageName);
        });
    }

    public void setNotificationPackagesAllowed(Set<String> packageNames, boolean allowed) {
        if (packageNames == null || packageNames.isEmpty()) return;
        Set<String> names = new HashSet<>(packageNames);
        if (names.stream().anyMatch(name -> !LightPreferences.isPackageName(name))) return;
        editPreferences(v -> {
            if (allowed) v.notificationBlockedPackages.removeAll(names);
            else v.notificationBlockedPackages.addAll(names);
        });
    }

    public void setManualEffect(int effect) {
        if (!LightPreferences.isManualEffect(effect)) return;
        editPreferences(v -> v.manualEffect = effect);
    }

    public void setManualColor(int color) {
        if (!LightPreferences.isColor(color)) return;
        editPreferences(v -> v.manualColor = color);
    }

    public void setManualBrightness(float brightness) {
        mHandler.post(() -> {
            mTemporaryManualBrightness = Float.NaN;
            refreshEnvironment();
            boolean permitted = LightPreferences.isBrightness(brightness)
                    && canEdit() && mPreferences != null;
            if (permitted && (!mPreferencesValid
                    || Float.compare(mValues.manualBrightness, brightness) != 0)) {
                LightPreferences.Values.Builder builder = mValues.buildUpon();
                builder.manualBrightness = brightness;
                savePreferences(builder.build());
            }
            reconcile(permitted);
        });
    }

    public void setTemporaryManualBrightness(float brightness) {
        if (!LightPreferences.isBrightness(brightness)) return;
        mHandler.post(() -> {
            refreshEnvironment();
            if (!canEdit() || !mPreferencesValid || safetyBlocked()) return;
            if (Float.compare(manualBrightness(), brightness) == 0) return;
            mTemporaryManualBrightness = brightness;
            reconcile(false);
        });
    }

    public void clearTemporaryManualBrightness() {
        mHandler.post(() -> {
            if (Float.isNaN(mTemporaryManualBrightness)) return;
            mTemporaryManualBrightness = Float.NaN;
            refreshEnvironment();
            reconcile(false);
        });
    }

    private float manualBrightness() {
        return Float.isNaN(mTemporaryManualBrightness)
                ? mValues.manualBrightness : mTemporaryManualBrightness;
    }

    private void editPreferences(Consumer<LightPreferences.Values.Builder> edit) {
        mHandler.post(() -> {
            refreshEnvironment();
            if (!canEdit() || mPreferences == null) return;
            LightPreferences.Values.Builder builder = mValues.buildUpon();
            try {
                edit.accept(builder);
                savePreferences(builder.build());
            } catch (IllegalArgumentException error) {
                return;
            }
            reconcile(true);
        });
    }

    private void savePreferences(LightPreferences.Values updated) {
        if (mPreferences.save(updated)) {
            mValues = updated;
            mPreferencesValid = true;
            mLastError = 0;
        } else {
            mValues = LightPreferences.Values.defaults(false);
            mPreferencesValid = false;
            mManualRequested = false;
            mTemporaryManualBrightness = Float.NaN;
            mPreview = null;
            mLastError = 5;
            mTemporaryAutoBrightness = Float.NaN;
        }
        if (!mValues.autoEnabled || !mValues.notificationsEnabled) mPending.clear();
    }

    public void setManualEnabled(boolean enabled) {
        mHandler.post(() -> changeManualEnabled(enabled));
    }

    public void toggleManual() {
        mHandler.post(() -> changeManualEnabled(!mManualRequested));
    }

    private void changeManualEnabled(boolean enabled) {
        refreshEnvironment();
        if (enabled && !newManualAllowed()) return;
        mManualRequested = enabled;
        reconcile(true);
    }

    public void startPreview(int effect, int color, int letter, int progress) {
        if (!LightPreferences.isColor(color) || progress < 0 || progress > 100) return;
        boolean hardware = effect == 42 || effect == 66;
        boolean supported = LightPreferences.isManualEffect(effect) || hardware
                || (effect >= 34 && effect <= 37) || effect == 48
                || (effect >= 51 && effect <= 56) || effect == 62 || effect == 69;
        if (!supported || (hardware && !LightPreferences.isNotificationColor(color))) return;
        if (effect == 62 && !((letter >= 'A' && letter <= 'Z')
                || (letter >= 'a' && letter <= 'z'))) return;
        mHandler.post(() -> {
            refreshEnvironment();
            if (!canEdit() || !mPreferencesValid || powerSaveBlocked()
                    || safetyBlocked() || mRemote == null) return;
            mPreview = new Spec(PREVIEW, effect, progress, 70, 10000, false,
                    ++mPreviewGeneration, color, hardware ? 1.0f : autoBrightness(),
                    effect == 62 ? letter : 0);
            reconcile(true);
        });
    }

    public void cancelPreview() {
        mHandler.post(() -> {
            mPreview = null;
            mWanted.remove(PREVIEW);
            reconcile(true);
        });
    }

    private void refreshEnvironment() {
        if (!mReady) return;
        try {
            mForeground = mUsers.isUserForeground();
            mUnlocked = mUsers.isUserUnlocked();
            mManaged = mUsers.isManagedProfile();
            mInteractive = mPower.isInteractive();
            mKeyguardLocked = mKeyguard.isKeyguardLocked();
            mPowerSave = mPower.isPowerSaveMode();
            mInterruptionFilter = mNotificationManager == null
                    ? NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                    : mNotificationManager.getCurrentInterruptionFilter();
            initializeSafety();
        } catch (RuntimeException error) {
            mForeground = false;
            mUnlocked = false;
            mInteractive = true;
            mLastError = 5;
        }
    }

    private boolean allowed() {
        return ownerAllowed() && mPreferencesValid && mValues.autoEnabled
                && !powerSaveBlocked() && !safetyBlocked() && mSchedule != null && mSchedule.isAllowed();
    }

    private boolean powerSaveBlocked() {
        return mPowerSave && mValues.disableInPowerSave;
    }

    private boolean ownerAllowed() {
        return mReady && mForeground && mUnlocked && !mManaged;
    }

    private boolean canEdit() {
        return ownerAllowed() && !mKeyguardLocked;
    }

    private boolean newManualAllowed() {
        return ownerAllowed() && mPreferencesValid && !safetyBlocked() && mRemote != null;
    }

    private boolean chargingAllowed() {
        return allowed() && !mInteractive && mValues.chargingEnabled && autoBrightness() > 0
                && mReverseState != ReverseChargingMonitor.State.ACTIVE;
    }

    private boolean reverseAllowed() {
        return allowed() && !mInteractive && mBatteryKnown && mValues.reverseChargingEnabled
                && autoBrightness() > 0;
    }

    private boolean reverseDisplayAllowed() {
        return reverseAllowed() && mReverseState == ReverseChargingMonitor.State.ACTIVE
                && !directionBlocked();
    }

    private boolean directionBlocked() {
        return mValues.disableWhenFaceUp && mPosture == 1;
    }

    private static boolean directional(Spec request) {
        return request.id == CHARGE_LEVEL || request.id == CHARGE_CONNECT
                || request.id == CHARGE_STATUS || request.id == REVERSE_CHARGE
                || request.id == CALL || request.id == NOTIFICATION;
    }

    private void reverseChanged(ReverseChargingMonitor.State state) {
        if (state == mReverseState) return;
        mReverseState = state;
        mReversePending = state == ReverseChargingMonitor.State.ACTIVE;
        ++mReverseGeneration;
        if (mReversePending) invalidateCharge();
        else mWanted.remove(REVERSE_CHARGE);
        refreshEnvironment();
        reconcile(true);
    }

    private boolean showReverseCharge() {
        if (!reverseDisplayAllowed() || mRemote == null || mWanted.containsKey(REVERSE_CHARGE)) {
            return false;
        }
        mReversePending = false;
        ++mReverseDisplays;
        mWanted.put(REVERSE_CHARGE, new Spec(REVERSE_CHARGE, 70, mLevel, 35, 2100, false,
                ++mReverseGeneration, COLOR, autoBrightness(), 0));
        return true;
    }

    private boolean safetyBlocked() {
        return safetyBlocked(false);
    }

    private boolean safetyBlocked(boolean camera) {
        if (!mSafetyReady || mTorchStates.isEmpty()
                || mThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return true;
        for (Boolean enabled : mTorchStates.values()) {
            if (Boolean.TRUE.equals(enabled) || (!camera && enabled == null)) return true;
        }
        return false;
    }

    private void initializeSafety() {
        if (mSafetyReady || mPower == null || mCameras == null) return;
        try {
            if (!mThermalRegistered) {
                mThermalStatus = mPower.getCurrentThermalStatus();
                mPower.addThermalStatusListener(mHandler::post, status -> {
                    mThermalStatus = status;
                    refreshEnvironment();
                    reconcile(true);
                });
                mThermalRegistered = true;
            }
            if (!mTorchRegistered) {
                mTorchStates.clear();
                for (String id : mCameras.getCameraIdList()) {
                    if (Boolean.TRUE.equals(mCameras.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                        mTorchStates.put(id, null);
                    }
                }
                mCameras.registerTorchCallback(new CameraManager.TorchCallback() {
                    @Override
                    public void onTorchModeChanged(String cameraId, boolean enabled) {
                        mTorchStates.put(cameraId, enabled);
                        refreshEnvironment();
                        reconcile(true);
                    }

                    @Override
                    public void onTorchModeUnavailable(String cameraId) {
                        mTorchStates.put(cameraId, null);
                        refreshEnvironment();
                        reconcile(true);
                    }
                }, mHandler);
                mTorchRegistered = true;
            }
            mSafetyReady = true;
        } catch (CameraAccessException | RuntimeException error) {
            mSafetyReady = false;
        }
    }

    private boolean charging() {
        return mBatteryKnown && (mBatteryStatus == BatteryManager.BATTERY_STATUS_CHARGING
                || mBatteryStatus == BatteryManager.BATTERY_STATUS_FULL);
    }

    private boolean notificationAllowed() {
        int hints = NotificationListenerService.HINT_HOST_DISABLE_EFFECTS
                | NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS;
        return allowed() && !mInteractive && mValues.notificationsEnabled && mListenerReady
                && (mListenerHints & hints) == 0;
    }

    private boolean volumeAllowed() {
        return allowed() && mValues.volumeEnabled && autoBrightness() > 0;
    }

    private boolean musicAllowed() {
        return allowed() && mValues.musicEnabled && mMusicAvailable && autoBrightness() > 0;
    }

    private boolean assistantAllowed() {
        return assistantEligible() && mAssistantState.available;
    }

    private boolean assistantEligible() {
        return allowed() && mValues.assistantEnabled && autoBrightness() > 0;
    }

    private boolean callAllowed() {
        if (!allowed() || !mValues.callsEnabled || !mRinging || autoBrightness() <= 0
                || (mListenerReady && (mListenerHints
                        & (NotificationListenerService.HINT_HOST_DISABLE_EFFECTS
                                | NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS)) != 0)) {
            return false;
        }
        if (mInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) return true;
        if (mInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_PRIORITY
                || !mListenerReady) return false;
        for (Notice notice : mNotices.values()) {
            if (notice.incomingCall && notice.ranked) return true;
        }
        return false;
    }

    private boolean cameraAllowed() {
        return ownerAllowed() && mPreferencesValid && !safetyBlocked(true);
    }

    private void volumeChanged(Intent intent) {
        if (intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)
                != AudioManager.STREAM_MUSIC || mAudio == null
                || !volumeAllowed() || mRemote == null) return;
        int value = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, -1);
        try {
            int minimum = mAudio.getStreamMinVolume(AudioManager.STREAM_MUSIC);
            int maximum = mAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (maximum <= minimum || value < minimum || value > maximum) return;
            ++mVolumeEvents;
            mVolumeProgress = (int) Math.round(18.0 * (value - minimum) / (maximum - minimum));
            mWanted.put(VOLUME, new Spec(VOLUME, 49, mVolumeProgress, 50, 2000, false,
                    0, COLOR, autoBrightness(), 0));
        } catch (RuntimeException ignored) {
        }
    }

    private void updateMotionRegistration() {
        int sensors = 0;
        if (mRemote != null && mPostureSensor != null) {
            boolean movement = (chargingAllowed() && charging() && mValues.chargingWhenMoved)
                    || (reverseAllowed() && mReverseState == ReverseChargingMonitor.State.ACTIVE);
            boolean direction = mValues.disableWhenFaceUp && mWanted.values().stream()
                    .anyMatch(request -> directional(request) && !mBlocked.contains(request.id)
                            && request.expires > SystemClock.elapsedRealtime());
            if (movement || direction) sensors |= MOTION_POSTURE;
            if (movement && mStaticSensor != null) sensors |= MOTION_STATIC;
        }
        if (sensors == mMotionSensors) return;
        int previousSensors = mRegisteredMotionSensors;
        int previousPosture = mPosture;
        Boolean previousStatic = mStatic;
        unregisterMotion();
        mMotionSensors = sensors;
        if (sensors == 0) return;
        final long epoch = ++mMotionEpoch;
        mMotionListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                motionChanged(epoch, event);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        if (registerMotionSensor(mPostureSensor)) {
            mRegisteredMotionSensors |= MOTION_POSTURE;
            if ((previousSensors & MOTION_POSTURE) != 0) mPosture = previousPosture;
            if ((sensors & MOTION_STATIC) != 0 && registerMotionSensor(mStaticSensor)) {
                mRegisteredMotionSensors |= MOTION_STATIC;
                if ((previousSensors & MOTION_STATIC) != 0) mStatic = previousStatic;
            }
        }
        if (mRegisteredMotionSensors == 0) {
            mMotionListener = null;
            ++mMotionEpoch;
        }
    }

    private boolean registerMotionSensor(Sensor sensor) {
        try {
            if (mSensors.registerListener(mMotionListener, sensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler)) return true;
        } catch (RuntimeException ignored) {
        }
        try {
            mSensors.unregisterListener(mMotionListener, sensor);
        } catch (RuntimeException ignored) {
        }
        ++mMotionFailures;
        return false;
    }

    private void unregisterMotion() {
        if (mMotionListener != null) {
            try {
                mSensors.unregisterListener(mMotionListener);
            } catch (RuntimeException error) {
                ++mMotionFailures;
            }
        }
        mMotionListener = null;
        mMotionSensors = 0;
        mRegisteredMotionSensors = 0;
        ++mMotionEpoch;
        mStatic = null;
        mPosture = -1;
    }

    private void motionChanged(long epoch, SensorEvent event) {
        if (mMotionListener == null || epoch != mMotionEpoch || event.values.length == 0
                || !Float.isFinite(event.values[0])) return;
        boolean trigger = false;
        if (event.sensor.getType() == SENSOR_STATIC_DETECT) {
            if ((mRegisteredMotionSensors & MOTION_STATIC) == 0) return;
            boolean stationary = event.values[0] == 0;
            trigger = mStatic != null && mStatic && !stationary && mPosture == 2;
            mStatic = stationary;
        } else if (event.sensor.getType() == SENSOR_POSTURE) {
            if ((mRegisteredMotionSensors & MOTION_POSTURE) == 0) return;
            int posture = (int) event.values[0];
            if (posture < 0 || posture > 2 || posture != event.values[0]) return;
            trigger = mPosture >= 0 && mPosture != 2 && posture == 2;
            mPosture = posture;
        } else return;
        ++mMotionEvents;
        refreshEnvironment();
        boolean requested = trigger && chargingAllowed() && charging() && mValues.chargingWhenMoved
                && !mWanted.containsKey(CHARGE_CONNECT) && !mWanted.containsKey(CHARGE_STATUS);
        if (requested) {
            ++mMotionDisplays;
            mWanted.put(CHARGE_STATUS, transientSpec(CHARGE_STATUS,
                    mLevel < 100 ? 37 : 36, mLevel < 100 ? 5000 : 10000));
        }
        boolean reverse = (trigger || mReversePending) && showReverseCharge();
        reconcile(requested || reverse);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            refreshEnvironment();
            if (mSchedule != null) {
                if (Intent.ACTION_TIME_CHANGED.equals(action)
                        || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) mSchedule.timeChanged();
                if (Intent.ACTION_USER_UNLOCKED.equals(action)
                        || Intent.ACTION_USER_FOREGROUND.equals(action)
                        || AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
                                .equals(action)) mSchedule.refresh();
            }
            if (mCalls != null && (Intent.ACTION_USER_UNLOCKED.equals(action)
                    || Intent.ACTION_USER_FOREGROUND.equals(action))) {
                mCallsAvailable = mCalls.isAvailable();
                mCalls.refresh();
            }
            if (mAssistant != null && (Intent.ACTION_USER_UNLOCKED.equals(action)
                    || Intent.ACTION_USER_FOREGROUND.equals(action)
                    || Intent.ACTION_USER_BACKGROUND.equals(action))) mAssistant.refresh();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                batteryChanged(intent);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                mInteractive = true;
                mPending.clear();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                mInteractive = true;
                mPending.clear();
            } else if (Intent.ACTION_USER_BACKGROUND.equals(action)) {
                mForeground = false;
                mPending.clear();
                mManualRequested = false;
                mPreview = null;
            } else if (AudioManager.VOLUME_CHANGED_ACTION.equals(action)) {
                volumeChanged(intent);
            }
            if (mRingtone != null && (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)
                    || (AudioManager.VOLUME_CHANGED_ACTION.equals(action)
                            && intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)
                                    == AudioManager.STREAM_RING))) mRingtone.refresh();
            if (mMusic != null && (Intent.ACTION_USER_UNLOCKED.equals(action)
                    || Intent.ACTION_USER_FOREGROUND.equals(action)
                    || (AudioManager.VOLUME_CHANGED_ACTION.equals(action)
                            && intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)
                                    == AudioManager.STREAM_MUSIC))) {
                mMusicAvailable = mMusic.isAvailable();
                mMusic.refresh();
            }
            reconcile(true);
        }
    };

    private void batteryChanged(Intent intent) {
        boolean known = mBatteryKnown;
        boolean wasCharging = charging();
        int oldLevel = mLevel;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
        mBatteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        mBatteryKnown = level >= 0 && scale > 0 && level <= scale
                && mBatteryStatus >= BatteryManager.BATTERY_STATUS_CHARGING
                && mBatteryStatus <= BatteryManager.BATTERY_STATUS_FULL
                && intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
        if (mBatteryKnown) mLevel = (int) ((long) level * 100 / scale);
        if (!charging()) {
            if (wasCharging || !mBatteryKnown) {
                invalidateCharge();
                if (wasCharging && mBatteryKnown && chargingAllowed() && mRemote != null) {
                    mWanted.put(CHARGE_STATUS, transientSpec(CHARGE_STATUS, 48, 3000));
                }
            }
        } else if (known && !wasCharging && chargingAllowed() && mRemote != null) {
            invalidateCharge();
            mConnectArmed = true;
            mWanted.put(CHARGE_CONNECT, transientSpec(CHARGE_CONNECT, 35, 10000));
        } else if (known && wasCharging && oldLevel < 100 && mLevel >= 100
                && !mValues.chargingWhenMoved && !mConnectArmed
                && chargingAllowed() && mRemote != null) {
            mWanted.put(CHARGE_STATUS, transientSpec(CHARGE_STATUS, 36, 10000));
        }
    }

    private Spec transientSpec(int id, int effect, int timeout) {
        return new Spec(id, effect, mLevel, 30, timeout, false, mChargeGeneration,
                COLOR, autoBrightness(), 0);
    }

    private void updateStrength(int id, float strength) {
        Spec spec = mWanted.get(id);
        if (spec != null) mWanted.put(id, spec.withStrength(strength));
    }

    private void invalidateCharge() {
        ++mChargeGeneration;
        mConnectArmed = false;
        mWanted.remove(CHARGE_LEVEL);
        mWanted.remove(CHARGE_CONNECT);
        mWanted.remove(CHARGE_STATUS);
    }

    public void listenerConnected(long session, List<Notice> baseline, int hints) {
        mHandler.post(() -> {
            if (session < mListenerSession) return;
            mListenerSession = session;
            mListenerReady = true;
            mListenerHints = hints;
            mNotices.clear();
            mPending.clear();
            for (Notice notice : baseline) mNotices.put(notice.key, notice);
            refreshEnvironment();
            reconcile(true);
        });
    }

    public void listenerDisconnected(long session) {
        mHandler.post(() -> {
            if (session < mListenerSession) return;
            mListenerSession = session;
            mListenerReady = false;
            mNotices.clear();
            mPending.clear();
            reconcile(true);
        });
    }

    public void notificationPosted(long session, Notice notice) {
        mHandler.post(() -> {
            if (!mListenerReady || session != mListenerSession) return;
            ++mPosts;
            boolean update = mNotices.containsKey(notice.key);
            mNotices.put(notice.key, notice);
            refreshEnvironment();
            if (!notice.eligible() || !mValues.isNotificationPackageAllowed(notice.packageName)) {
                mPending.remove(notice.key);
            } else if (notificationAllowed() && (!update || !notice.onlyOnce)) {
                mPending.add(notice.key);
            }
            reconcile(true);
        });
    }

    public void notificationRemoved(long session, String key) {
        mHandler.post(() -> {
            if (!mListenerReady || session != mListenerSession) return;
            ++mRemovals;
            mNotices.remove(key);
            mPending.remove(key);
            reconcile(true);
        });
    }

    public void rankingsChanged(long session, List<Rank> ranks) {
        mHandler.post(() -> {
            if (!mListenerReady || session != mListenerSession) return;
            ++mRankingChanges;
            for (Notice notice : mNotices.values()) notice.ranked = false;
            for (Rank rank : ranks) {
                Notice notice = mNotices.get(rank.key);
                if (notice != null) notice.ranked = rank.eligible;
            }
            mPending.removeIf(key -> !mNotices.containsKey(key)
                    || !mNotices.get(key).eligible());
            refreshEnvironment();
            reconcile(true);
        });
    }

    public void listenerHintsChanged(long session, int hints) {
        mHandler.post(() -> {
            if (!mListenerReady || session != mListenerSession) return;
            mListenerHints = hints;
            reconcile(true);
        });
    }

    private void reconcile(boolean externalEvent) {
        if (externalEvent) mBlocked.clear();
        if (mSchedule != null) {
            mSchedule.update(ownerAllowed() && mPreferencesValid && mValues.autoEnabled
                    && !powerSaveBlocked(), mValues);
        }
        if (mReverse != null) {
            mReverse.setEnabled(ownerAllowed() && mPreferencesValid && mValues.autoEnabled
                    && !powerSaveBlocked()
                    && (mValues.chargingEnabled || mValues.reverseChargingEnabled)
                    && mSchedule != null && mSchedule.isAllowed()
                    && mRemote != null);
        }
        if (mCalls != null) {
            mCalls.setEnabled(allowed() && mValues.callsEnabled && mRemote != null);
        }
        if (mRingtone != null) {
            mRingtone.setEnabled(callAllowed() && !directionBlocked() && mValues.callRhythm
                    && mRemote != null && !mBlocked.contains(CALL));
        }
        if (mMusic != null) {
            mMusic.setEnabled(musicAllowed() && mRemote != null && !mBlocked.contains(MUSIC));
        }
        if (mAssistant != null) {
            mAssistant.setEnabled(assistantEligible() && mRemote != null
                    && !mBlocked.contains(ASSISTANT));
        }
        mPending.removeIf(key -> !mNotices.containsKey(key) || !mNotices.get(key).eligible()
                || !mValues.isNotificationPackageAllowed(mNotices.get(key).packageName));
        if (!ownerAllowed()) {
            mManualRequested = false;
            mTemporaryManualBrightness = Float.NaN;
            mTemporaryAutoBrightness = Float.NaN;
            mPreview = null;
            if (mCamera != null) mCamera.clear();
            invalidateCharge();
            mWanted.clear();
            releaseClient();
        } else {
            if (directionBlocked() && (mConnectArmed || mWanted.containsKey(CHARGE_CONNECT)
                    || mWanted.containsKey(CHARGE_STATUS))) invalidateCharge();
            if (!reverseDisplayAllowed()) mWanted.remove(REVERSE_CHARGE);
            else if (mReversePending) showReverseCharge();
            updateStrength(REVERSE_CHARGE, autoBrightness());
            if (!chargingAllowed()) invalidateCharge();
            else {
                if (charging() && !mValues.chargingWhenMoved) {
                    mWanted.put(CHARGE_LEVEL,
                            new Spec(CHARGE_LEVEL, 34, mLevel, 20, 0, true, mChargeGeneration,
                                    COLOR, autoBrightness(), 0));
                } else mWanted.remove(CHARGE_LEVEL);
                updateStrength(CHARGE_CONNECT, autoBrightness());
                updateStrength(CHARGE_STATUS, autoBrightness());
            }
            if (volumeAllowed()) updateStrength(VOLUME, autoBrightness());
            else mWanted.remove(VOLUME);
            if (musicAllowed() && mMusicSample.available && mMusicSample.active) {
                mWanted.put(MUSIC, new Spec(MUSIC, 38, 0, 45, 0, true, 0,
                        COLOR, autoBrightness(), 0).withAmplitude(mMusicSample.amplitude));
            } else mWanted.remove(MUSIC);
            if (assistantAllowed() && mAssistantState.visible) {
                mWanted.put(ASSISTANT, new Spec(ASSISTANT, 30, 0, 50, 0, true, 0,
                        COLOR, autoBrightness(), 0));
            } else mWanted.remove(ASSISTANT);
            if (callAllowed()) {
                if (mValues.callRhythm && mCallSample.available) {
                    if (mCallSample.active) {
                        mWanted.put(CALL, new Spec(CALL, 50, 0, 55, 0, true, 0,
                                mValues.callColor, autoBrightness(), 0)
                                .withAmplitude(mCallSample.amplitude));
                    } else mWanted.remove(CALL);
                } else {
                    mWanted.put(CALL, new Spec(CALL, 41, 0, 55, 0, true, 0,
                            mValues.callColor, autoBrightness(), 0));
                }
            } else mWanted.remove(CALL);
            if (mCamera != null && !cameraAllowed()) mCamera.clear();
            CameraLightSession.Request camera = mCamera == null ? null : mCamera.request();
            if (powerSaveBlocked() && camera != null && camera.effect() == 63) {
                mCamera.finishVisual(camera.generation());
                camera = null;
            }
            if (camera != null && camera.expires() > SystemClock.elapsedRealtime()
                    && (camera.effect() != 63 || (mValues.autoEnabled
                            && mSchedule != null && mSchedule.isAllowed()))) {
                mWanted.put(CAMERA, new Spec(CAMERA, camera.effect(), 0, 80,
                        camera.effect() == 39 ? 10000 : camera.effect() == 40 ? 3000 : 300,
                        false, camera.generation(), COLOR, autoBrightness(), 0,
                        camera.expires()));
            } else mWanted.remove(CAMERA);
            if (notificationAllowed() && !mPending.isEmpty()) {
                mWanted.put(NOTIFICATION, new Spec(NOTIFICATION, 42, 0, 40, 0, true, 0,
                        mValues.notificationColor, 1.0f, 0));
            } else mWanted.remove(NOTIFICATION);
            if (mManualRequested && !safetyBlocked()) {
                mWanted.put(MANUAL, new Spec(MANUAL, mValues.manualEffect, 0, 60, 0, true, 0,
                        mValues.manualColor, manualBrightness(), 0));
            } else mWanted.remove(MANUAL);
            if (!canEdit() || powerSaveBlocked() || safetyBlocked()
                    || (mPreview != null && mPreview.expires <= SystemClock.elapsedRealtime())) {
                mPreview = null;
            }
            if (mPreview != null) {
                mPreview = mPreview.withStrength(mPreview.effect == 42 || mPreview.effect == 66
                        ? 1.0f : autoBrightness());
                mWanted.put(PREVIEW, mPreview);
            } else mWanted.remove(PREVIEW);
            if (mWanted.isEmpty()) releaseClient();
        }
        updateMotionRegistration();
        if (mRemote == null) {
            if (externalEvent && mAttempts >= RETRY_MS.length) mAttempts = 0;
            scheduleConnect();
            updateDump();
            return;
        }
        int operationId = -1;
        try {
            for (Map.Entry<Integer, Flight> entry : new ArrayList<>(mFlights.entrySet())) {
                Spec wanted = mWanted.get(entry.getKey());
                if (wanted != null && directionBlocked() && directional(wanted)) wanted = null;
                Flight flight = entry.getValue();
                if ((wanted == null || wanted.effect != flight.spec.effect
                        || wanted.generation != flight.spec.generation
                        || flight.spec.expires <= SystemClock.elapsedRealtime())
                        && !flight.canceling) {
                    flight.canceling = true;
                    operationId = entry.getKey();
                    mRemote.cancel(mClient, entry.getKey());
                    ++mCancels;
                }
            }
            for (Spec wanted : new ArrayList<>(mWanted.values())) {
                if (wanted.expires <= SystemClock.elapsedRealtime()) {
                    mWanted.remove(wanted.id);
                    if (wanted.id == CHARGE_CONNECT) mConnectArmed = false;
                    if (wanted.id == PREVIEW) mPreview = null;
                    continue;
                }
                if (mBlocked.contains(wanted.id) || (directionBlocked() && directional(wanted))) {
                    continue;
                }
                Flight flight = mFlights.get(wanted.id);
                if (flight != null && flight.canceling) continue;
                if (flight == null) {
                    ensureClient();
                    mFlights.put(wanted.id, new Flight(wanted));
                    operationId = wanted.id;
                    mRemote.request(mClient, wanted.id, wanted.request());
                    mAttempts = 0;
                    ++mRequests;
                } else if (!flight.spec.sameOptions(wanted)) {
                    flight.spec = wanted;
                    operationId = wanted.id;
                    mRemote.request(mClient, wanted.id, wanted.request());
                    mAttempts = 0;
                    ++mRequests;
                }
            }
        } catch (ServiceSpecificException error) {
            mLastError = error.errorCode;
            if (operationId >= 0) {
                mBlocked.add(operationId);
                mFlights.remove(operationId);
                if (operationId == CHARGE_CONNECT || operationId == CHARGE_STATUS
                        || operationId == VOLUME || operationId == PREVIEW || operationId == CAMERA
                        || operationId == REVERSE_CHARGE) {
                    mWanted.remove(operationId);
                    if (operationId == CHARGE_CONNECT) mConnectArmed = false;
                    if (operationId == PREVIEW) mPreview = null;
                    if (operationId == CAMERA && mCamera != null) mCamera.clear();
                }
                mHandler.removeCallbacks(mReconcilePlayback);
                mHandler.post(mReconcilePlayback);
            }
        } catch (RemoteException | RuntimeException error) {
            connectionLost(5);
        }
        updateMotionRegistration();
        updateDump();
    }

    private void scheduleConnect() {
        if (!mReady || !mForeground || mManaged || mRemote != null || mReconnectScheduled
                || mAttempts >= RETRY_MS.length) return;
        mReconnectScheduled = true;
        mHandler.postDelayed(mConnect, RETRY_MS[mAttempts]);
    }

    private void connect() {
        mReconnectScheduled = false;
        if (mRemote != null || !mForeground || mManaged) return;
        ++mAttempts;
        try {
            IBinder service = ServiceManager.checkService(LIGHT_SERVICE);
            IBinder extension = service == null ? null : service.getExtension();
            if (extension == null) throw new IllegalStateException();
            IAwLight remote = IAwLight.Stub.asInterface(extension);
            int version = remote.getInterfaceVersion();
            if (version < 1) throw new IllegalStateException();
            LightState state = remote.getState();
            if (state == null) throw new IllegalStateException();
            mDeath = () -> mHandler.post(() -> {
                if (mRemoteBinder == extension) connectionLost(32);
            });
            extension.linkToDeath(mDeath, 0);
            mRemoteBinder = extension;
            mRemote = remote;
            mVersion = version;
            mLastError = 0;
            mNativeActive = state.active;
            mNativeEffect = state.effect;
            mNativeOwner = state.owner;
            mNativeRequestId = state.requestId;
            mBlocked.clear();
            refreshEnvironment();
            reconcile(false);
        } catch (RemoteException | RuntimeException error) {
            ++mConnectFailures;
            mLastError = 19;
            scheduleConnect();
            updateDump();
        }
    }

    private void ensureClient() {
        if (mClient != null) return;
        long epoch = ++mClientEpoch;
        mOwnOwner = 0;
        mClient = new IAwLightClient.Stub() {
            @Override
            public void onStateChanged(LightState state) {
                if (state != null) mHandler.post(() -> stateChanged(epoch, state));
            }

            @Override
            public int getInterfaceVersion() {
                return IAwLightClient.VERSION;
            }

            @Override
            public String getInterfaceHash() {
                return IAwLightClient.HASH;
            }
        };
    }

    private void stateChanged(long epoch, LightState state) {
        if (epoch != mClientEpoch || mClient == null) return;
        ++mCallbacks;
        Flight flight = mFlights.get(state.requestId);
        if (flight != null && flight.isStaleVolumeTimeout(state)) return;
        mOwnOwner = state.owner;
        if (state.active) {
            mLastError = 0;
            mNativeActive = true;
            mNativeEffect = state.effect;
            mNativeOwner = state.owner;
            mNativeRequestId = state.requestId;
            updateDump();
            return;
        }
        if (state.owner == mNativeOwner && state.requestId == mNativeRequestId) {
            mNativeActive = false;
            mNativeEffect = 0;
            mNativeRequestId = -1;
        }
        if (flight == null || flight.spec.effect != state.effect) {
            updateDump();
            return;
        }
        mFlights.remove(state.requestId);
        Spec wanted = mWanted.get(state.requestId);
        boolean sameRequest = wanted == flight.spec;
        boolean chain = state.requestId == CHARGE_CONNECT && state.effect == 35
                && state.error == 0 && !flight.canceling && sameRequest && mConnectArmed
                && flight.spec.generation == mChargeGeneration;
        if (sameRequest && !flight.spec.resume) {
            mWanted.remove(state.requestId);
            if (state.requestId == PREVIEW) mPreview = null;
        }
        if (state.requestId == CHARGE_CONNECT
                && flight.spec.generation == mChargeGeneration) mConnectArmed = false;
        if (flight.spec.resume && wanted != null && !flight.canceling) {
            mBlocked.add(state.requestId);
        }
        if (state.error != 0 && state.error != OsConstants.ECANCELED
                && state.error != OsConstants.ETIMEDOUT) {
            mLastError = state.error;
            if (state.requestId == CAMERA && mCamera != null && mCamera.request() != null
                    && mCamera.request().generation() == flight.spec.generation) mCamera.clear();
        }
        if (state.requestId == CAMERA && mCamera != null) {
            mCamera.finishVisual(flight.spec.generation);
        }
        refreshEnvironment();
        if (chain && chargingAllowed() && charging()) {
            mWanted.put(CHARGE_STATUS, transientSpec(CHARGE_STATUS,
                    mLevel < 100 ? 37 : 36, mLevel < 100 ? 5000 : 10000));
        }
        reconcile(false);
    }

    private void releaseClient() {
        IAwLightClient client = mClient;
        mClient = null;
        mOwnOwner = 0;
        ++mClientEpoch;
        mFlights.clear();
        mNativeActive = false;
        mNativeEffect = 0;
        mNativeOwner = 0;
        mNativeRequestId = -1;
        if (mRemote != null && client != null) {
            try {
                mRemote.release(client);
                ++mReleases;
            } catch (RemoteException | RuntimeException error) {
                mLastError = 5;
            }
        }
    }

    private void connectionLost(int error) {
        releaseClient();
        if (mRemoteBinder != null && mDeath != null) {
            try {
                mRemoteBinder.unlinkToDeath(mDeath, 0);
            } catch (RuntimeException ignored) {
            }
        }
        mRemote = null;
        mRemoteBinder = null;
        mDeath = null;
        mVersion = 0;
        mLastError = error;
        mPreview = null;
        mWanted.remove(PREVIEW);
        mWanted.remove(VOLUME);
        mWanted.remove(CALL);
        mWanted.remove(MUSIC);
        mWanted.remove(ASSISTANT);
        mWanted.remove(CAMERA);
        mWanted.remove(REVERSE_CHARGE);
        mReversePending = false;
        if (mReverse != null) mReverse.setEnabled(false);
        if (mRingtone != null) mRingtone.setEnabled(false);
        if (mMusic != null) mMusic.setEnabled(false);
        if (mAssistant != null) mAssistant.setEnabled(false);
        if (mCamera != null) mCamera.clear();
        invalidateCharge();
        updateMotionRegistration();
        mAttempts = Math.max(1, mAttempts);
        scheduleConnect();
        updateDump();
    }

    public String dumpSnapshot() {
        return mDump;
    }

    private void readNativeState() {
        if (mRemote == null) return;
        try {
            LightState state = mRemote.getState();
            mNativeActive = state.active;
            mNativeEffect = state.effect;
            mNativeOwner = state.owner;
            mNativeRequestId = state.requestId;
        } catch (RemoteException | RuntimeException error) {
            connectionLost(5);
        }
    }

    private void updateDump() {
        mDump = "AwLight automatic events\n"
                + "ready=" + mReady + " foreground=" + mForeground
                + " unlocked=" + mUnlocked + " managed=" + mManaged
                + " interactive=" + mInteractive + " keyguard=" + mKeyguardLocked + "\n"
                + "batteryKnown=" + mBatteryKnown + " status=" + mBatteryStatus
                + " percent=" + mLevel + " chargingWhenMoved=" + mValues.chargingWhenMoved + "\n"
                + "preferencesValid=" + mPreferencesValid + " auto=" + mValues.autoEnabled
                + " autoBrightness=" + autoBrightness()
                + " savedAutoBrightness=" + mValues.autoBrightness
                + " charging=" + mValues.chargingEnabled
                + " notifications=" + mValues.notificationsEnabled + "\n"
                + "calls=" + mValues.callsEnabled + " callsAvailable=" + mCallsAvailable
                + " ringing=" + mRinging + " cameraEvents=" + mCameraEvents + "\n"
                + "music=" + mValues.musicEnabled + " musicAvailable=" + mMusicAvailable
                + " musicCapturing=" + mMusicSample.available + " musicActive=" + mMusicSample.active
                + " musicAmplitude=" + mMusicSample.amplitude + "\n"
                + "powerSave=" + mPowerSave + " disableInPowerSave=" + mValues.disableInPowerSave
                + " powerSaveBlocked=" + powerSaveBlocked() + "\n"
                + "assistant=" + mValues.assistantEnabled
                + " assistantAvailable=" + mAssistantState.available
                + " assistantVisible=" + mAssistantState.visible + "\n"
                + "reverse=" + mReverseState + " reverseEnabled=" + mValues.reverseChargingEnabled
                + " reverseDisplays=" + mReverseDisplays + "\n"
                + "schedule=" + mValues.scheduleEnabled
                + " scheduleAllowed=" + (mSchedule != null && mSchedule.isAllowed())
                + " exactAlarmsAllowed=" + (mSchedule != null && mSchedule.exactAlarmsAllowed())
                + " nextBoundary=" + (mSchedule == null ? 0 : mSchedule.nextBoundary())
                + " scheduleFailed=" + (mSchedule != null && mSchedule.failed()) + "\n"
                + "motionSupported=" + (mStaticSensor != null && mPostureSensor != null)
                + " motionRegistered=" + (mMotionListener != null)
                + " motionSensors=" + mRegisteredMotionSensors
                + " posture=" + mPosture + " disableWhenFaceUp=" + mValues.disableWhenFaceUp
                + " motionEvents=" + mMotionEvents + " motionDisplays=" + mMotionDisplays
                + " motionFailures=" + mMotionFailures + "\n"
                + "volume=" + mValues.volumeEnabled + " volumeEvents=" + mVolumeEvents
                + " volumeProgress=" + mVolumeProgress
                + " blockedNotificationApps=" + mValues.notificationBlockedPackages.size() + "\n"
                + "manualRequested=" + mManualRequested + " manualEffect=" + mValues.manualEffect
                + " brightness=" + manualBrightness() + " savedBrightness=" + mValues.manualBrightness
                + " safetyBlocked=" + safetyBlocked()
                + " thermal=" + mThermalStatus + "\n"
                + "listenerConnected=" + mListenerReady + " tracked=" + mNotices.size()
                + " pending=" + mPending.size() + "\n"
                + "nativeConnected=" + (mRemote != null) + " interfaceVersion=" + mVersion
                + " active=" + mNativeActive + " effect=" + mNativeEffect
                + " lastError=" + mLastError + "\n"
                + "wanted=" + mWanted.size() + " inFlight=" + mFlights.size()
                + " connectArmed=" + mConnectArmed + "\n"
                + "posts=" + mPosts + " removals=" + mRemovals
                + " rankings=" + mRankingChanges + " requests=" + mRequests
                + " cancels=" + mCancels + " releases=" + mReleases
                + " callbacks=" + mCallbacks + " connectFailures=" + mConnectFailures + "\n";
        Snapshot snapshot = new Snapshot(this);
        if (!snapshot.sameAs(mSnapshot)) {
            mSnapshot = snapshot;
            mMainHandler.removeCallbacks(mNotifyObservers);
            mMainHandler.post(mNotifyObservers);
        }
    }
}
