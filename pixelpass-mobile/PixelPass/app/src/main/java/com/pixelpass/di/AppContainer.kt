package com.pixelpass.di

import android.content.Context
import com.pixelpass.core.PixelPassLog
import com.pixelpass.data.local.PixelPassDatabase
import com.pixelpass.data.session.SessionStore
import com.pixelpass.scan.ScanIntakeCoordinator
import com.pixelpass.scan.ScanReplayGuard
import com.pixelpass.scan.ScanRepository
import com.pixelpass.scan.ScanSyncScheduler
import com.pixelpass.security.DeviceKeyStore
import com.pixelpass.utils.LocationGeohashProvider
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

    /**
     * Guard-side scan pipeline. Anything that cannot reach the gateway is queued
     * in `pending_sync_scans`; the callback schedules the replay worker.
     */
    val scanRepository: ScanRepository by lazy {
        ScanRepository(
            pendingSyncScanDao = database.pendingSyncScanDao(),
            onQueued = { ScanSyncScheduler.enqueue(appContext) }
        )
    }

    /** Remembers accepted nonces so a rotated pass cannot be presented twice here. */
    val scanReplayGuard: ScanReplayGuard by lazy {
        ScanReplayGuard(dao = database.scannedNonceDao())
    }

    /** The single entry point the scanner uses: verify locally, then claim the nonce. */
    val scanIntake: ScanIntakeCoordinator by lazy {
        ScanIntakeCoordinator(replayGuard = scanReplayGuard)
    }

    private val locationGeohashProvider: LocationGeohashProvider by lazy {
        LocationGeohashProvider(appContext)
    }

    /**
     * Coarse geohash stamped into the pass. Empty when the user has not granted
     * location access or no provider has a fix yet, which must not block a pass.
     */
    fun locationGeohash(): String = locationGeohashProvider.currentGeohash()

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
