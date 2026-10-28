package com.pixelpass.scan

import com.pixelpass.core.PixelPassLog
import com.pixelpass.data.local.PendingSyncScanDao
import com.pixelpass.data.local.PendingSyncScanEntity
import com.pixelpass.network.RetrofitClient
import com.pixelpass.network.ScanRequest
import com.pixelpass.network.TerminalCoordinate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How many queued scans one replay pass handles. */
const val SYNC_BATCH_SIZE: Int = 25

private const val DECISION_GRANTED = "GRANTED"
private const val DECISION_PENDING = "DENIED"

private val HEADLINE_GRANTED = ScanVerdict.GRANTED.headline
private val HEADLINE_DENIED = ScanVerdict.DENIED.headline
private val HEADLINE_OFFLINE_GRANTED = ScanVerdict.OFFLINE_APPROVAL.headline

/**
 * Bridges the scanner, the gateway and the offline queue.
 *
 * A scan only reaches the gateway when [ScanGate] already accepted it. If the
 * gateway cannot be reached the row is written to `pending_sync_scans` so the
 * Worker replays it later: a signed pass may be approved provisionally offline,
 * while a legacy token is refused because nothing about it is verifiable.
 */
class ScanRepository(
    private val pendingSyncScanDao: PendingSyncScanDao,
    private val remote: suspend (ScanRequest) -> ScanRemoteResult = {
        toRemoteResult(RetrofitClient.instance.verifyScan(it))
    },
    private val onQueued: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun submit(
        jwt: String,
        nonce: String?,
        source: ScanSource,
        faceImageBase64: String,
        coordinate: TerminalCoordinate,
        deviceFingerprint: String
    ): ScanOutcome {
        val request = ScanRequest(
            token = jwt,
            nonce = nonce,
            faceImageBase64 = faceImageBase64,
            terminalCoordinate = coordinate,
            deviceFingerprint = deviceFingerprint
        )

        val result = attempt(request)
        if (!result.isTransient) {
            PixelPassLog.i(PixelPassLog.SCAN, "Gateway answered ${result.statusCode} (${result.access})")
            return if (result.granted) {
                ScanOutcome(true, HEADLINE_GRANTED, result.reason ?: "Clear for entry")
            } else {
                ScanOutcome(false, HEADLINE_DENIED, result.reason ?: "Gateway refused the pass")
            }
        }

        val provisionalGrant = source == ScanSource.SIGNED_PASS
        queue(
            jwt = jwt,
            nonce = nonce,
            faceImageBase64 = faceImageBase64,
            coordinate = coordinate,
            deviceFingerprint = deviceFingerprint,
            decision = if (provisionalGrant) DECISION_GRANTED else DECISION_PENDING,
            error = result.reason ?: "Gateway unreachable"
        )

        return if (provisionalGrant) {
            ScanOutcome(
                granted = true,
                headline = HEADLINE_OFFLINE_GRANTED,
                detail = "Signature, identity and rotation checked on this device. Queued for gateway review.",
                queuedForSync = true
            )
        } else {
            ScanOutcome(
                granted = false,
                headline = HEADLINE_DENIED,
                detail = "Without the gateway an unsigned token cannot be approved. The scan is queued for review.",
                queuedForSync = true
            )
        }
    }

    /**
     * Replays queued scans oldest first. A 4xx refusal is final (the gateway has
     * logged the attempt), while transport errors and 5xx leave the row pending.
     */
    suspend fun syncPending(limit: Int = SYNC_BATCH_SIZE): SyncReport = withContext(Dispatchers.IO) {
        val rows = pendingSyncScanDao.loadUnsynced(limit)
        if (rows.isEmpty()) return@withContext SyncReport(0, 0, 0, retryable = false)

        var synced = 0
        var failed = 0
        var retryable = false

        for (row in rows) {
            val request = ScanRequest(
                token = row.token,
                nonce = row.nonce,
                faceImageBase64 = row.faceImageBase64.orEmpty(),
                terminalCoordinate = TerminalCoordinate(
                    latitude = row.latitude ?: 0.0,
                    longitude = row.longitude ?: 0.0
                ),
                deviceFingerprint = row.deviceFingerprint
            )

            val result = attempt(request)
            if (result.isTransient) {
                pendingSyncScanDao.markAttemptFailed(row.id, clock(), result.reason)
                failed++
                retryable = true
            } else {
                pendingSyncScanDao.markSynced(row.id, clock())
                synced++
            }
        }

        PixelPassLog.i(PixelPassLog.SYNC, "Replayed $synced of ${rows.size} queued scans ($failed pending)")
        SyncReport(attempted = rows.size, synced = synced, failed = failed, retryable = retryable)
    }

    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) { pendingSyncScanDao.countUnsynced() }

    private suspend fun queue(
        jwt: String,
        nonce: String?,
        faceImageBase64: String,
        coordinate: TerminalCoordinate,
        deviceFingerprint: String,
        decision: String,
        error: String
    ) {
        val id = withContext(Dispatchers.IO) {
            pendingSyncScanDao.insert(
                PendingSyncScanEntity(
                    token = jwt,
                    nonce = nonce,
                    scannedAtEpochMillis = clock(),
                    latitude = coordinate.latitude,
                    longitude = coordinate.longitude,
                    deviceFingerprint = deviceFingerprint,
                    faceImageBase64 = faceImageBase64,
                    decision = decision,
                    lastError = error
                )
            )
        }
        PixelPassLog.w(PixelPassLog.SYNC, "Scan $id queued for sync ($decision): $error")
        onQueued()
    }

    private suspend fun attempt(request: ScanRequest): ScanRemoteResult = try {
        remote(request)
    } catch (error: Exception) {
        PixelPassLog.w(PixelPassLog.SCAN, "Gateway unreachable: ${error.message}")
        ScanRemoteResult.TRANSPORT_ERROR.copy(reason = error.message ?: "Gateway unreachable")
    }
}
