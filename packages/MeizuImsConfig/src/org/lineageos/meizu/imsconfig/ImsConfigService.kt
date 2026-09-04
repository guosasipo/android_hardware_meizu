/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.meizu.imsconfig

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.ImsStateCallback
import android.telephony.ims.ProvisioningManager
import android.telephony.ims.RegistrationManager
import android.telephony.ims.stub.ImsConfigImplBase
import android.util.Log
import java.util.concurrent.Executors

class ImsConfigService : JobService() {
    private val handler = Handler.createAsync(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val subscriptions = mutableMapOf<Int, SubscriptionState>()

    private val subscriptionManager by lazy { getSystemService(SubscriptionManager::class.java) }
    private val imsManager by lazy { getSystemService(ImsManager::class.java) }
    private var parameters: JobParameters? = null

    private val timeout = Runnable {
        Log.w(TAG, "Timed out waiting for IMS registration")
        finishJob()
    }

    override fun onStartJob(params: JobParameters): Boolean {
        cleanUp()
        parameters = params
        handler.postDelayed(timeout, SERVICE_TIMEOUT_MS)
        if (!reconcileSubscriptions()) {
            parameters = null
            cleanUp()
            return false
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (parameters === params) {
            parameters = null
            cleanUp()
        }
        return false
    }

    override fun onDestroy() {
        cleanUp()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun reconcileSubscriptions(): Boolean {
        val activeSubscriptions =
            subscriptionManager.activeSubscriptionInfoList
                ?.map { it.subscriptionId }
                ?.toSet()
                .orEmpty()

        subscriptions.keys.filterNot(activeSubscriptions::contains).forEach {
            subscriptions.remove(it)?.close()
        }

        activeSubscriptions.forEach { subId ->
            subscriptions.getOrPut(subId) { SubscriptionState(subId) }.start()
        }

        return subscriptions.isNotEmpty()
    }

    private fun finishIfComplete() {
        if (subscriptions.isNotEmpty() && subscriptions.values.all { it.configured }) {
            finishJob()
        }
    }

    private fun finishJob() {
        val params = parameters ?: return
        parameters = null
        cleanUp()
        jobFinished(params, false)
    }

    private fun cleanUp() {
        handler.removeCallbacksAndMessages(null)
        subscriptions.values.forEach { it.close() }
        subscriptions.clear()
    }

    private fun reapplyDtmfConfiguration(subId: Int): Boolean {
        return try {
            val manager = ProvisioningManager.createForSubscriptionId(subId)
            val values = DTMF_KEYS.map { manager.getProvisioningIntValue(it) }
            if (values.any { it == ProvisioningManager.PROVISIONING_RESULT_UNKNOWN }) {
                return false
            }

            var success = true
            DTMF_KEYS.zip(values).forEach { (key, value) ->
                success =
                    (manager.setProvisioningIntValue(key, value) ==
                        ImsConfigImplBase.CONFIG_RESULT_SUCCESS) && success
            }
            success
        } catch (e: Exception) {
            Log.w(TAG, "Unable to reapply IMS DTMF configuration for subId $subId", e)
            false
        }
    }

    private inner class SubscriptionState(private val subId: Int) {
        private val imsMmTelManager = imsManager.getImsMmTelManager(subId)
        private var stateCallbackRegistered = false
        private var registrationCallbackRegistered = false
        private var configuring = false
        private var closed = false

        var configured = false
            private set

        private val stateRetry = Runnable { registerStateCallback() }
        private val registrationRetry = Runnable { registerRegistrationCallback() }
        private val configurationRetry = Runnable { configure() }

        private val stateCallback =
            object : ImsStateCallback() {
                override fun onAvailable() {
                    registerRegistrationCallback()
                }

                override fun onUnavailable(reason: Int) {
                    unregisterRegistrationCallback()
                    configured = false
                }

                override fun onError() {
                    stateCallbackRegistered = false
                    unregisterRegistrationCallback()
                    scheduleStateRetry()
                }
            }

        private val registrationCallback =
            object : RegistrationManager.RegistrationCallback() {
                override fun onRegistered(attributes: ImsRegistrationAttributes) {
                    configured = false
                    configure()
                }
            }

        fun start() {
            if (!closed && !stateCallbackRegistered) {
                registerStateCallback()
            }
        }

        fun close() {
            if (closed) {
                return
            }
            closed = true
            handler.removeCallbacks(stateRetry)
            handler.removeCallbacks(registrationRetry)
            handler.removeCallbacks(configurationRetry)
            unregisterRegistrationCallback()
            if (stateCallbackRegistered) {
                try {
                    imsMmTelManager.unregisterImsStateCallback(stateCallback)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to unregister IMS state callback for subId $subId", e)
                }
                stateCallbackRegistered = false
            }
        }

        private fun registerStateCallback() {
            if (closed || stateCallbackRegistered) {
                return
            }
            try {
                imsMmTelManager.registerImsStateCallback(mainExecutor, stateCallback)
                stateCallbackRegistered = true
            } catch (e: Exception) {
                scheduleStateRetry()
            }
        }

        private fun scheduleStateRetry() {
            if (!closed) {
                handler.removeCallbacks(stateRetry)
                handler.postDelayed(stateRetry, RETRY_DELAY_MS)
            }
        }

        private fun registerRegistrationCallback() {
            if (closed || registrationCallbackRegistered) {
                return
            }
            try {
                imsMmTelManager.registerImsRegistrationCallback(mainExecutor, registrationCallback)
                registrationCallbackRegistered = true
            } catch (e: Exception) {
                scheduleRegistrationRetry()
            }
        }

        private fun scheduleRegistrationRetry() {
            if (!closed) {
                handler.removeCallbacks(registrationRetry)
                handler.postDelayed(registrationRetry, RETRY_DELAY_MS)
            }
        }

        private fun unregisterRegistrationCallback() {
            handler.removeCallbacks(registrationRetry)
            if (!registrationCallbackRegistered) {
                return
            }
            try {
                imsMmTelManager.unregisterImsRegistrationCallback(registrationCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to unregister IMS registration callback for subId $subId", e)
            }
            registrationCallbackRegistered = false
        }

        private fun configure() {
            if (closed || configured || configuring) {
                return
            }
            configuring = true
            worker.execute {
                val success = reapplyDtmfConfiguration(subId)
                handler.post {
                    configuring = false
                    if (closed) {
                        return@post
                    }
                    if (success) {
                        configured = true
                        Log.i(TAG, "Reapplied IMS DTMF configuration for subId $subId")
                        finishIfComplete()
                    } else {
                        handler.postDelayed(configurationRetry, RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MeizuImsConfig"
        private const val RETRY_DELAY_MS = 2_000L
        private const val SERVICE_TIMEOUT_MS = 120_000L

        private val DTMF_KEYS =
            intArrayOf(
                ProvisioningManager.KEY_DTMF_WB_PAYLOAD_TYPE,
                ProvisioningManager.KEY_DTMF_NB_PAYLOAD_TYPE,
            )
    }
}
