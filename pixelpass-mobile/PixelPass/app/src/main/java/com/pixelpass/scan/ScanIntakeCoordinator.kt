package com.pixelpass.scan

import com.pixelpass.core.PixelPassLog
import com.pixelpass.qr.PassVerdict

/** Shown when a pass was already accepted on this device. */
const val REPLAY_REASON: String = "This pass was already scanned on this device"

/**
 * Runs the local checks for one scanned code, in the only safe order:
 * [ScanGate] first (is the pass genuine, current and bound to its owner?) and
 * then [ScanReplayGuard] (has this exact pass already been used here?).
 *
 * A legacy token has no nonce, so the replay cache can neither burn it nor be
 * relied on for it; that is why a legacy token is never approved offline either.
 */
class ScanIntakeCoordinator(
    private val replayGuard: ScanReplayGuard,
    private val gate: (String, Long) -> ScanIntake = { raw, now -> ScanGate.intake(raw, now) },
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun intake(raw: String): ScanIntake {
        val now = clock()
        val result = gate(raw, now)
        if (result !is ScanIntake.Accepted) return result

        return when (val claim = replayGuard.claim(result.nonce, result.userId.orEmpty(), now)) {
            ReplayClaim.Fresh -> result
            is ReplayClaim.Replay -> {
                PixelPassLog.w(
                    PixelPassLog.SCAN,
                    "Pass refused: nonce already accepted at ${claim.scannedAtEpochMillis}"
                )
                ScanIntake.Rejected(reason = REPLAY_REASON, verdict = PassVerdict.REPLAY)
            }
        }
    }
}
