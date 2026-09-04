/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.meizu.imsconfig

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class ImsConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action == TelephonyManager.ACTION_SIM_APPLICATION_STATE_CHANGED &&
                intent.getIntExtra(
                    TelephonyManager.EXTRA_SIM_STATE,
                    TelephonyManager.SIM_STATE_UNKNOWN,
                ) != TelephonyManager.SIM_STATE_LOADED
        ) {
            return
        }

        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job =
            JobInfo.Builder(JOB_ID, ComponentName(context, ImsConfigService::class.java))
                .setOverrideDeadline(0)
                .build()
        if (scheduler.schedule(job) == JobScheduler.RESULT_FAILURE) {
            Log.e(TAG, "Unable to schedule IMS configuration")
        }
    }

    companion object {
        private const val TAG = "MeizuImsConfig"
        private const val JOB_ID = 0x4d5a494d
    }
}
