package com.pixelpass.di

import android.content.Context
import com.pixelpass.core.PixelPassLog
import com.pixelpass.data.local.PixelPassDatabase
import com.pixelpass.data.session.SessionStore
import com.pixelpass.security.DeviceKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application-scoped singletons. Kept explicit instead of pulling in a DI
 * framework so the delivery surface stays small and testable.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: PixelPassDatabase by lazy { PixelPassDatabase.build(appContext) }

    val sessionStore: SessionStore by lazy { SessionStore(appContext) }

    val deviceKeyStore: DeviceKeyStore by lazy { DeviceKeyStore() }

    /** The Keystore key pair is created once, off the main thread, before the first pass. */
    fun prepareDeviceIdentity() {
        appScope.launch {
            runCatching { deviceKeyStore.getOrCreateKeyPair() }
                .onSuccess { PixelPassLog.i(PixelPassLog.QR, "Device signing key ready") }
                .onFailure { PixelPassLog.e(PixelPassLog.QR, "Device signing key unavailable", it) }
        }
    }

    /**
     * Opens the database off the main thread on launch so Database Inspector can
     * attach immediately and the first scan does not pay the open cost.
     */
    fun warmUp() {
        appScope.launch {
            runCatching { database.openHelper.writableDatabase }
                .onSuccess { PixelPassLog.i(PixelPassLog.DB, "Room database opened") }
                .onFailure { PixelPassLog.e(PixelPassLog.DB, "Room database failed to open", it) }
        }
    }
}
