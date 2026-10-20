/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device.face

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.UserHandle
import android.util.Log
import com.motorola.internal.app.IMotoFaceService
import com.motorola.internal.app.IMotoFaceServiceReceiver

/**
 * Binds com.motorola.faceunlock's FaceAuthService (motorola.core_services.face.MotoFaceService) and
 * speaks IMotoFaceService to it. Mirrors its 13 methods; results arrive through [ClientCallbacks].
 */
class MotoAuthClient(private val context: Context, private val callbacks: ClientCallbacks) {
    interface ClientCallbacks {
        fun onEnrollResult(faceId: Int, userId: Int, remaining: Int)

        fun onAuthenticated(faceId: Int, userId: Int, token: ByteArray)

        fun onError(error: Int, vendorCode: Int)

        fun onRemoved(faceIds: IntArray, userId: Int)

        fun onEnumerate(faceIds: IntArray, userId: Int)
    }

    private val lock = Any()
    private var remote: IMotoFaceService? = null
    private var binding = false

    /** Safe to call repeatedly; (re)binds if needed. */
    fun ensureBound() {
        synchronized(lock) {
            if (remote != null || binding) return

            val intent =
                Intent(ACTION_BIND_CLIENT).apply {
                    component = ComponentName(CLIENT_PACKAGE, CLIENT_SERVICE)
                }
            binding =
                runCatching {
                        context.bindServiceAsUser(
                            intent,
                            connection,
                            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
                            UserHandle.SYSTEM,
                        )
                    }
                    .onSuccess { bound ->
                        if (!bound)
                            Log.e(TAG, "bindService returned false (APK missing / user locked?)")
                    }
                    .onFailure { Log.e(TAG, "bindService failed", it) }
                    .getOrDefault(false)
        }
    }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runCatching {
                        IMotoFaceService.Stub.asInterface(binder).also { service ->
                            service.setCallback(receiver)
                            synchronized(lock) { remote = service }
                        }
                    }
                    .onSuccess { Log.i(TAG, "connected to ${name.flattenToShortString()}") }
                    .onFailure { Log.e(TAG, "setCallback failed", it) }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Log.w(TAG, "Moto service disconnected")
                synchronized(lock) { remote = null }
            }

            override fun onBindingDied(name: ComponentName) {
                synchronized(lock) {
                    remote = null
                    binding = false
                }
                ensureBound()
            }
        }

    private val receiver =
        object : IMotoFaceServiceReceiver.Stub() {
            override fun onEnrollResult(faceId: Int, userId: Int, remaining: Int) =
                callbacks.onEnrollResult(faceId, userId, remaining)

            override fun onAuthenticated(faceId: Int, userId: Int, token: ByteArray) =
                callbacks.onAuthenticated(faceId, userId, token)

            override fun onAcquired(a: Int, b: Int, c: Int) = Unit // not forwarded

            override fun onError(error: Int, vendorCode: Int) = callbacks.onError(error, vendorCode)

            override fun onRemoved(faceIds: IntArray, userId: Int) =
                callbacks.onRemoved(faceIds, userId)

            override fun onEnumerate(faceIds: IntArray, userId: Int) =
                callbacks.onEnumerate(faceIds, userId)

            override fun onLockoutChanged(durationMs: Long) =
                Unit // APK also sends onError(LOCKOUT)
        }

    private fun service(): IMotoFaceService =
        ensureBound().let {
            synchronized(lock) { checkNotNull(remote) { "Moto service not connected" } }
        }

    fun generateChallenge(timeoutSec: Int) = service().generateChallenge(timeoutSec)

    fun revokeChallenge() = service().revokeChallenge()

    fun enroll(hat: ByteArray, timeoutSec: Int, disabledFeatures: IntArray) =
        service().enroll(hat, timeoutSec, disabledFeatures)

    fun authenticate(operationId: Long) = service().authenticate(operationId)

    fun cancel() = service().cancel()

    fun enumerate() = service().enumerate()

    fun remove(faceId: Int) = service().remove(faceId)

    fun resetLockout(hat: ByteArray) = service().resetLockout(hat)

    fun getFeature(feature: Int, faceId: Int) = service().getFeature(feature, faceId)

    fun setFeature(feature: Int, enabled: Boolean, hat: ByteArray, faceId: Int) =
        service().setFeature(feature, enabled, hat, faceId)

    companion object {
        private const val TAG = "MotoAuthClient"
        const val CLIENT_PACKAGE = "com.motorola.faceunlock"
        private const val CLIENT_SERVICE = "com.motorola.faceunlock.service.FaceAuthService"
        private const val ACTION_BIND_CLIENT = "com.motorola.faceunlock.BIND"
    }
}
