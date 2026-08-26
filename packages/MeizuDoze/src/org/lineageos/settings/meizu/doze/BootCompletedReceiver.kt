/*
 * SPDX-FileCopyrightText: 2015 The CyanogenMod Project
 * SPDX-FileCopyrightText: 2017 The LineageOS Project
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.meizu.doze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startServiceAsUser(
            Intent(context, MeizuDozeService::class.java),
            UserHandle.CURRENT,
        )
    }
}
