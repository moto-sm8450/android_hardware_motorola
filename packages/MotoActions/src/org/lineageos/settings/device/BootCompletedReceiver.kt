/*
 * SPDX-FileCopyrightText: 2015 The CyanogenMod Project
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserHandle
import android.util.Log
import org.lineageos.settings.device.face.MotoAuthClient

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Booting")
        context.startServiceAsUser(
            Intent(context, MotoActionsService::class.java),
            UserHandle.CURRENT,
        )

        val pm = context.packageManager
        val supported = pm.hasSystemFeature(PackageManager.FEATURE_FACE) &&
            pm.hasSystemFeature(MotoAuthClient.CLIENT_PACKAGE)

        pm.setComponentEnabledSetting(
            ComponentName(context, MotoFaceAuthService::class.java),
            if (supported) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )

        if (supported) {
            context.startService(Intent(context, MotoFaceAuthService::class.java))
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
