package com.pixelpass.scan

import com.pixelpass.data.local.ScannedNonceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanReplayGuardTest {

    private val now = 1_760_000_000_000L
    private val dao = FakeScannedNonceDao()

    private fun guard(retentionMillis: Long = NONCE_RETENTION_MILLIS) =
        ScanReplayGuard(dao = dao, retentionMillis = retentionMillis, clock = { now })

    @Test
    fun firstClaimIsFreshAndRemembered() = runBlocking {
        val claim = guard().claim(nonce = "nonce-1", subjectId = "user-1", now = now)

        assertSame(ReplayClaim.Fresh, claim)

        val row = dao.rows.getValue("nonce-1")
        assertEquals("user-1", row.subjectId)
        assertEquals(now, row.scannedAtEpochMillis)
        assertEquals(now + NONCE_RETENTION_MILLIS, row.expiresAtEpochMillis)
        assertEquals(ScanReplayGuard.NONCE_VERDICT_ACCEPTED, row.verdict)
    }

    @Test
    fun secondClaimOfTheSameNonceIsAReplay() = runBlocking {
        val guard = guard()
        guard.claim(nonce = "nonce-1", subjectId = "user-1", now = now)

        val claim = guard.claim(nonce = "nonce-1", subjectId = "user-1", now = now + 5_000)

        assertTrue(claim is ReplayClaim.Replay)
        assertEquals(now, (claim as ReplayClaim.Replay).scannedAtEpochMillis)
        assertEquals(1, dao.count())
    }

    @Test
    fun differentNoncesAreIndependent() = runBlocking {
        val guard = guard()

        assertSame(ReplayClaim.Fresh, guard.claim("nonce-1", "user-1", now))
        assertSame(ReplayClaim.Fresh, guard.claim("nonce-2", "user-2", now))
        assertEquals(2, dao.count())
    }

    @Test
    fun claimsWithoutNonceAreAlwaysFreshAndNeverStored() = runBlocking {
        val guard = guard()

        assertSame(ReplayClaim.Fresh, guard.claim(nonce = null, subjectId = "user-1", now = now))
        assertSame(ReplayClaim.Fresh, guard.claim(nonce = "   ", subjectId = "user-1", now = now))
        assertEquals(0, dao.count())
    }

    @Test
    fun expiredEntriesArePrunedBeforeTheLookup() = runBlocking {
        dao.upsert(
            ScannedNonceEntity(
                nonce = "nonce-1",
                subjectId = "user-1",
                scannedAtEpochMillis = now - 60_000,
                expiresAtEpochMillis = now - 1,
                verdict = ScanReplayGuard.NONCE_VERDICT_ACCEPTED
            )
        )

        val claim = guard().claim(nonce = "nonce-1", subjectId = "user-1", now = now)

        assertSame(ReplayClaim.Fresh, claim)
        assertEquals(1, dao.count())
        assertEquals(now, dao.rows.getValue("nonce-1").scannedAtEpochMillis)
    }

    @Test
    fun aUsedNonceStaysRefusedForTheWholeRetentionWindow() = runBlocking {
        val guard = guard(retentionMillis = 10_000)
        guard.claim(nonce = "nonce-1", subjectId = "user-1", now = now)

        val insideWindow = guard.claim(nonce = "nonce-1", subjectId = "user-1", now = now + 9_999)
        val afterWindow = guard.claim(nonce = "nonce-1", subjectId = "user-1", now = now + 10_001)

        assertTrue(insideWindow is ReplayClaim.Replay)
        assertSame(ReplayClaim.Fresh, afterWindow)
    }
}
