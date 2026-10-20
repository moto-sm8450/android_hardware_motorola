/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import com.motorola.hardware.biometric.face.bridge.IFaceBridge
import com.motorola.hardware.biometric.face.bridge.IFaceBridgeCallback
import com.motorola.hardware.biometric.face.bridge.IFaceBridgeHost
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.lineageos.settings.device.face.MotoAuthClient

/** Registers with the HAL daemon and relays its requests to Motorola's FaceAuthService. */
class MotoFaceAuthService : Service(), MotoAuthClient.ClientCallbacks {
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var authClient: MotoAuthClient

    @Volatile private var callback: IFaceBridgeCallback? = null

    /** True while an enroll/authenticate is in flight. */
    @Volatile private var opActive = false

    // The APK is not directBootAware: bind once the user is unlocked.
    private val unlockReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handler.post { authClient.ensureBound() }
            }
        }

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("authClient-face-bridge").apply { start() }
        handler = Handler(handlerThread.looper)
        authClient = MotoAuthClient(this, this)

        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        handler.post { authClient.ensureBound() }
        thread(name = "moto-face-register") { registerWithHost() }
    }

    override fun onDestroy() {
        unregisterReceiver(unlockReceiver)
        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Loops until interrupted. runCatching also captures [InterruptedException], so it is
     * explicitly rethrown to stop the loop and restore the interrupt flag.
     */
    private fun registerWithHost() {
        runCatching {
                while (true) {
                    runCatching { registerOnce() }
                        .onFailure { e ->
                            if (e is InterruptedException) throw e
                            Log.e(TAG, "register failed", e)
                            Thread.sleep(RETRY_DELAY_MS)
                        }
                }
            }
            .onFailure { e ->
                if (e is InterruptedException) Thread.currentThread().interrupt() else throw e
            }
    }

    /** Registers the bridge once and blocks until the HAL dies. */
    private fun registerOnce() {
        when (
            val binder =
                ServiceManager.waitForDeclaredService(IFaceBridgeHost.DESCRIPTOR + "/default")
        ) {
            null -> Thread.sleep(RETRY_DELAY_MS)
            else -> {
                IFaceBridgeHost.Stub.asInterface(binder).registerBridge(bridge)
                Log.i(TAG, "registered with HAL")

                CountDownLatch(1).apply { binder.linkToDeath({ countDown() }, 0) }.await()
                Log.w(TAG, "HAL died, re-registering")
            }
        }
    }

    private fun post(op: () -> Unit) {
        handler.post {
            runCatching(op).onFailure {
                Log.e(TAG, "Moto call failed", it)
                opActive = false
                send { onError(FACE_ERROR_HW_UNAVAILABLE, 0) }
            }
        }
    }

    private fun send(op: IFaceBridgeCallback.() -> Unit) {
        callback?.let { cb ->
            runCatching { cb.op() }.onFailure { Log.e(TAG, "HAL callback failed", it) }
        }
    }

    private val bridge =
        object : IFaceBridge.Stub() {
            // Required for versioned (stable) AIDL Java stubs.
            override fun getInterfaceVersion() = IFaceBridge.VERSION

            override fun getInterfaceHash(): String = IFaceBridge.HASH

            override fun setCallback(cb: IFaceBridgeCallback?) {
                callback = cb
            }

            override fun generateChallenge(timeoutSec: Int) = post {
                authClient.generateChallenge(timeoutSec).let { send { onChallengeGenerated(it) } }
            }

            override fun revokeChallenge() = post {
                authClient.revokeChallenge()
                send { onChallengeRevoked() }
            }

            override fun enroll(hat: ByteArray, timeoutSec: Int, disabled: IntArray) = post {
                opActive = true
                authClient.enroll(hat, timeoutSec, disabled)
            }

            override fun authenticate(operationId: Long) = post {
                opActive = true
                authClient.authenticate(operationId)
            }

            override fun cancel() {
                // Own thread: must not queue behind a Moto call that is still blocking.
                thread(name = "moto-face-cancel") {
                    runCatching { authClient.cancel() }
                        .onFailure { Log.e(TAG, "cancel failed", it) }

                    // The HAL contract requires onError(CANCELED); synthesize it if the APK stays
                    // silent.
                    handler.postDelayed(
                        {
                            if (opActive) {
                                opActive = false
                                send { onError(FACE_ERROR_CANCELED, 0) }
                            }
                        },
                        CANCEL_GRACE_MS,
                    )
                }
            }

            override fun enumerate() = post {
                authClient
                    .enumerate()
                    .takeIf { it != 0 }
                    ?.let { send { onError(FACE_ERROR_UNABLE_TO_PROCESS, 0) } }
            }

            override fun remove(faceId: Int) = post { authClient.remove(faceId) }

            override fun resetLockout(hat: ByteArray) = post {
                authClient.resetLockout(hat)
                send { onLockoutCleared() }
            }

            override fun getFeature(feature: Int, faceId: Int) = post {
                authClient.getFeature(feature, faceId).let { enabled ->
                    send { onFeature(feature, enabled) }
                }
            }

            override fun setFeature(feature: Int, enabled: Boolean, hat: ByteArray, faceId: Int) =
                post {
                    authClient.setFeature(feature, enabled, hat, faceId)
                    send { onFeature(feature, enabled) }
                }
        }

    override fun onEnrollResult(faceId: Int, userId: Int, remaining: Int) {
        if (remaining == 0) opActive = false
        send { onEnrollResult(faceId, userId, remaining) }
    }

    override fun onAuthenticated(faceId: Int, userId: Int, token: ByteArray) {
        opActive = false
        send { onAuthenticated(faceId, userId) }
    }

    override fun onError(error: Int, vendorCode: Int) {
        opActive = false
        send { onError(error, vendorCode) }
    }

    override fun onRemoved(faceIds: IntArray, userId: Int) = send { onRemoved(faceIds, userId) }

    override fun onEnumerate(faceIds: IntArray, userId: Int) = send { onEnumerate(faceIds, userId) }

    private companion object {
        const val TAG = "MotoFaceService"

        const val FACE_ERROR_HW_UNAVAILABLE = 1
        const val FACE_ERROR_UNABLE_TO_PROCESS = 2
        const val FACE_ERROR_CANCELED = 5

        const val CANCEL_GRACE_MS = 700L
        const val RETRY_DELAY_MS = 2000L
    }
}
