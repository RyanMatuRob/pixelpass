package com.pixelpass.scan

import com.pixelpass.data.local.PendingSyncScanDao
import com.pixelpass.data.local.PendingSyncScanEntity
import com.pixelpass.network.ScanRequest
import com.pixelpass.network.TerminalCoordinate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ScanRepositoryTest {

    private val now = 1_760_000_000_000L
    private val coordinate = TerminalCoordinate(latitude = -29.8579, longitude = 31.0292)
    private val dao = FakePendingSyncScanDao()
    private var queuedNotifications = 0

    private fun repository(remote: suspend (ScanRequest) -> ScanRemoteResult): ScanRepository =
        ScanRepository(
            pendingSyncScanDao = dao,
            remote = remote,
            onQueued = { queuedNotifications++ },
            clock = { now }
        )

    @Test
    fun gatewayGrantIsReportedWithoutQueueing() = runBlocking {
        val repository = repository { ScanRemoteResult(200, "GRANTED", "Clear for entry") }

        val outcome = repository.submit(
            jwt = "token-1",
            nonce = "nonce-1",
            source = ScanSource.SIGNED_PASS,
            faceImageBase64 = "face",
            coordinate = coordinate,
            deviceFingerprint = "device-1"
        )

        assertTrue(outcome.granted)
        assertEquals("ACCESS GRANTED", outcome.headline)
        assertFalse(outcome.queuedForSync)
        assertTrue(dao.rows.isEmpty())
        assertEquals(0, queuedNotifications)
    }

    @Test
    fun gatewayRefusalIsFinalAndNotQueued() = runBlocking {
        val repository = repository { ScanRemoteResult(403, "DENIED", "Facial Recognition Failed") }

        val outcome = repository.submit(
            jwt = "token-1",
            nonce = "nonce-1",
            source = ScanSource.SIGNED_PASS,
            faceImageBase64 = "face",
            coordinate = coordinate,
            deviceFingerprint = "device-1"
        )

        assertFalse(outcome.granted)
        assertEquals("ACCESS DENIED", outcome.headline)
        assertEquals("Facial Recognition Failed", outcome.detail)
        assertTrue(dao.rows.isEmpty())
        assertEquals(0, queuedNotifications)
    }

    @Test
    fun signedPassIsApprovedOfflineAndQueued() = runBlocking {
        val repository = repository { throw IOException("unreachable") }

        val outcome = repository.submit(
            jwt = "token-1",
            nonce = "nonce-1",
            source = ScanSource.SIGNED_PASS,
            faceImageBase64 = "face",
            coordinate = coordinate,
            deviceFingerprint = "device-1"
        )

        assertTrue(outcome.granted)
        assertTrue(outcome.queuedForSync)
        assertEquals(1, queuedNotifications)

        val row = dao.rows.single()
        assertEquals("token-1", row.token)
        assertEquals("nonce-1", row.nonce)
        assertEquals("GRANTED", row.decision)
        assertEquals(now, row.scannedAtEpochMillis)
        assertEquals(coordinate.latitude, row.latitude ?: 0.0, 0.0)
        assertEquals("face", row.faceImageBase64)
        assertEquals("unreachable", row.lastError)
    }

    @Test
    fun legacyTokenIsNeverApprovedOffline() = runBlocking {
        val repository = repository { ScanRemoteResult(500, null, "ML Microservice unreachable") }

        val outcome = repository.submit(
            jwt = "legacy-token",
            nonce = null,
            source = ScanSource.LEGACY_TOKEN,
            faceImageBase64 = "face",
            coordinate = coordinate,
            deviceFingerprint = "device-1"
        )

        assertFalse(outcome.granted)
        assertTrue(outcome.queuedForSync)
        assertEquals("DENIED", dao.rows.single().decision)
        assertNull(dao.rows.single().nonce)
        assertEquals("ML Microservice unreachable", dao.rows.single().lastError)
    }

    @Test
    fun queuedScanIsReplayedAndMarkedSynced() = runBlocking {
        dao.insert(queuedRow())
        var submitted: ScanRequest? = null
        val repository = repository {
            submitted = it
            ScanRemoteResult(200, "GRANTED", "Clear for entry")
        }

        val report = repository.syncPending()

        assertEquals(1, report.attempted)
        assertEquals(1, report.synced)
        assertEquals(0, report.failed)
        assertFalse(report.retryable)
        assertNotNull(submitted)
        assertEquals("token-1", submitted?.token)
        assertEquals("face", submitted?.faceImageBase64)
        assertEquals("device-1", submitted?.deviceFingerprint)
        assertNotNull(dao.rows.single().syncedAtEpochMillis)
        assertEquals(0, dao.countUnsynced())
    }

    @Test
    fun transientFailureKeepsTheRowPending() = runBlocking {
        dao.insert(queuedRow())
        val repository = repository { throw IOException("timeout") }

        val report = repository.syncPending()

        assertEquals(1, report.attempted)
        assertEquals(0, report.synced)
        assertEquals(1, report.failed)
        assertTrue(report.retryable)
        assertNull(dao.rows.single().syncedAtEpochMillis)
        assertEquals(1, dao.rows.single().syncAttempts)
        assertEquals(1, dao.countUnsynced())
    }

    @Test
    fun emptyQueueDoesNothing() = runBlocking {
        val repository = repository { error("the gateway must not be called") }

        val report = repository.syncPending()

        assertEquals(0, report.attempted)
        assertEquals(0, repository.pendingCount())
    }

    private fun queuedRow() = PendingSyncScanEntity(
        token = "token-1",
        nonce = null,
        scannedAtEpochMillis = now - 1_000,
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        deviceFingerprint = "device-1",
        faceImageBase64 = "face",
        decision = "GRANTED"
    )

    /** In-memory stand-in for the Room DAO so the queue logic can run on the JVM. */
    private class FakePendingSyncScanDao : PendingSyncScanDao {

        val rows = mutableListOf<PendingSyncScanEntity>()
        private var nextId = 1L

        override suspend fun insert(scan: PendingSyncScanEntity): Long {
            val id = nextId++
            rows += scan.copy(id = id)
            return id
        }

        override suspend fun loadUnsynced(limit: Int): List<PendingSyncScanEntity> =
            rows.filter { it.syncedAtEpochMillis == null }
                .sortedBy { it.scannedAtEpochMillis }
                .take(limit)

        override suspend fun countUnsynced(): Int = rows.count { it.syncedAtEpochMillis == null }

        override suspend fun markSynced(id: Long, syncedAt: Long) {
            update(id) { it.copy(syncedAtEpochMillis = syncedAt, lastError = null) }
        }

        override suspend fun markAttemptFailed(id: Long, attemptedAt: Long, error: String?) {
            update(id) {
                it.copy(
                    syncAttempts = it.syncAttempts + 1,
                    lastAttemptAtEpochMillis = attemptedAt,
                    lastError = error
                )
            }
        }

        override fun observeRecent(limit: Int): Flow<List<PendingSyncScanEntity>> =
            flowOf(rows.take(limit))

        private fun update(id: Long, change: (PendingSyncScanEntity) -> PendingSyncScanEntity) {
            val index = rows.indexOfFirst { it.id == id }
            rows[index] = change(rows[index])
        }
    }
}
