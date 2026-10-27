package com.pixelpass.scan

/**
 * The four full-screen outcomes a guard can see after scanning a pass.
 *
 * [ScanGate] refuses a code before face capture, so only a pass that survived the
 * local checks can reach [GRANTED], [DENIED] or [OFFLINE_APPROVAL]. [REPLAY] comes
 * from [ScanReplayGuard], which refuses a rotated pass that was already used on
 * this device.
 */
enum class ScanVerdict(val headline: String) {
    /** The gateway approved the scan. */
    GRANTED("ACCESS GRANTED"),

    /** The gateway refused the scan, or an unsigned token could not be approved offline. */
    DENIED("ACCESS DENIED"),

    /** A signed pass was approved on the device because the gateway was unreachable. */
    OFFLINE_APPROVAL("OFFLINE APPROVAL"),

    /** The nonce of this pass was already accepted on this device. */
    REPLAY("PASS ALREADY USED")
}

/** Maps a gateway or offline decision onto the screen that displays it. */
fun ScanOutcome.toVerdict(): ScanVerdict = when {
    granted && queuedForSync -> ScanVerdict.OFFLINE_APPROVAL
    granted -> ScanVerdict.GRANTED
    else -> ScanVerdict.DENIED
}
