package com.pixelpass.scan

import com.pixelpass.core.PixelPassLog
import com.pixelpass.data.local.ScannedNonceDao
import com.pixelpass.data.local.ScannedNonceEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How long a used nonce is remembered. A rotated pass only lives for
 * [com.pixelpass.qr.PASS_TTL_MILLIS], so half an hour covers every honest retry
 * and keeps the table small enough to prune on each claim.
 */
const val NONCE_RETENTION_MILLIS: Long = 30 * 60 * 1_000L

/** What the replay cache knows about a nonce that was just read. */
sealed interface ReplayClaim {

    /** The nonce had never been seen on this device. */
    object Fresh : ReplayClaim

    /** [scannedAtEpochMillis] is when this nonce was first accepted here. */
    data class Replay(val scannedAtEpochMillis: Long) : ReplayClaim
}

/**
 * Split-brain replay defence on the guard's device.
 *
 * The gateway can only refuse a replayed token once it sees it, which never
 * happens while the phone is offline. Remembering every accepted nonce locally
 * closes that gap: a rotated pass that already walked one person through the
 * gate cannot walk a second one through, even with no connectivity at all.
 */
class ScanReplayGuard(
    private val dao: ScannedNonceDao,
    private val retentionMillis: Long = NONCE_RETENTION_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val mutex = Mutex()

    /** Reserves [nonce] for this scan, or reports when it was first used. */
    suspend fun claim(
        nonce: String?,
        subjectId: String,
        now: Long = clock()
    ): ReplayClaim = mutex.withLock {
        // Legacy tokens carry no nonce; only the frame debounce can help there.
        if (nonce.isNullOrBlank()) return@withLock ReplayClaim.Fresh

        dao.prune(now)

        val seen = dao.find(nonce)
        if (seen != null) {
            PixelPassLog.w(
                PixelPassLog.SCAN,
                "Replayed nonce of ${seen.subjectId}, first accepted at ${seen.scannedAtEpochMillis}"
            )
            return@withLock ReplayClaim.Replay(seen.scannedAtEpochMillis)
        }

        dao.upsert(
            ScannedNonceEntity(
                nonce = nonce,
                subjectId = subjectId,
                scannedAtEpochMillis = now,
                expiresAtEpochMillis = now + retentionMillis,
                verdict = NONCE_VERDICT_ACCEPTED
            )
        )
        ReplayClaim.Fresh
    }

    companion object {
        /** Recorded for a nonce that was consumed by a scan the gate accepted. */
        const val NONCE_VERDICT_ACCEPTED = "ACCEPTED"
    }
}
