/*
 * SPDX-FileCopyrightText: 2015 The CyanogenMod Project
 * SPDX-FileCopyrightText: 2017-2018 The LineageOS Project
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.meizu.doze

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Display

class MeizuDozeService : Service(), SensorEventListener {
    private val handler = Handler.createAsync(Looper.getMainLooper())
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }

    private val raiseSensor by lazy {
        sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull {
            it.stringType == RAISE_SENSOR_TYPE && it.isWakeUpSensor
        }
    }

    private var lastRaiseAction = 0
    private var hasRaiseBaseline = false
    private var raiseRegistered = false

    private val stateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                hasRaiseBaseline = false
                updateRaiseRegistration()
            }
        }

    private val settingObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                updateRaiseRegistration()
            }
        }

    override fun onCreate() {
        super.onCreate()
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DOZE_PICK_UP_GESTURE),
            false,
            settingObserver,
            UserHandle.USER_ALL,
        )
        registerReceiver(
            stateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_SWITCHED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        updateRaiseRegistration()
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(settingObserver)
        unregisterReceiver(stateReceiver)
        if (raiseRegistered) {
            sensorManager.unregisterListener(this)
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateRaiseRegistration()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        val action = event.values.firstOrNull()?.toInt() ?: return
        if (!hasRaiseBaseline) {
            lastRaiseAction = action
            hasRaiseBaseline = true
            return
        }

        val shouldWake =
            (action == RAISE_ACTION &&
                lastRaiseAction != LOWERED_ACTION &&
                lastRaiseAction != LOWERING_ACTION) || action == LOWERED_ACTION
        lastRaiseAction = action

        if (
            shouldWake &&
                !powerManager.isInteractive &&
                keyguardManager.isKeyguardLocked &&
                isLiftToWakeEnabled()
        ) {
            wakeUp()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun updateRaiseRegistration() {
        val shouldRegister = isLiftToWakeEnabled() && !powerManager.isInteractive
        if (shouldRegister == raiseRegistered) {
            return
        }

        val sensor = raiseSensor
        if (shouldRegister && sensor != null) {
            hasRaiseBaseline = false
            raiseRegistered =
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            if (!raiseRegistered) {
                Log.e(TAG, "Failed to register raise sensor")
            }
        } else if (raiseRegistered) {
            sensorManager.unregisterListener(this)
            raiseRegistered = false
            hasRaiseBaseline = false
        }
    }

    private fun isLiftToWakeEnabled(): Boolean =
        Settings.Secure.getIntForUser(
            contentResolver,
            Settings.Secure.DOZE_PICK_UP_GESTURE,
            1,
            UserHandle.USER_CURRENT,
        ) != 0

    private fun wakeUp() {
        if (
            !powerManager.isInteractive && keyguardManager.isKeyguardLocked && isLiftToWakeEnabled()
        ) {
            powerManager.wakeUpWithProximityCheck(
                SystemClock.uptimeMillis(),
                PowerManager.WAKE_REASON_GESTURE,
                TAG,
                Display.DEFAULT_DISPLAY,
            )
        }
    }

    companion object {
        private const val TAG = "MeizuDoze"
        private const val RAISE_SENSOR_TYPE = "com.meizu.sensor.raise"

        private const val RAISE_ACTION = 2
        private const val LOWERED_ACTION = 6
        private const val LOWERING_ACTION = 7
    }
}
