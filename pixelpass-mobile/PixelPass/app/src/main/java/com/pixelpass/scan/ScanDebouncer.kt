package com.pixelpass.scan

/** A QR code is allowed through at most once per [DEFAULT_COOLDOWN_MILLIS] while it stays identical. */
const val DEFAULT_COOLDOWN_MILLIS: Long = 4_000L

/**
 * Frame-level debounce for the camera analyzer.
 *
 * ML Kit reports the same barcode on every analysed frame, so without this the
 * guard would be sent to face capture dozens of times per second. A rotated pass
 * carries a new payload and therefore passes immediately.
 */
class ScanDebouncer(
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val lock = Any()
    private var lastPayload: String? = null
    private var lastAcceptedAt: Long = 0L

    /** True when [payload] may be delivered to the UI now. */
    fun tryAcquire(payload: String): Boolean = synchronized(lock) {
        val now = clock()
        if (payload == lastPayload && now - lastAcceptedAt < cooldownMillis) {
            false
        } else {
            lastPayload = payload
            lastAcceptedAt = now
            true
        }
    }

    /** Allows the code that was just handled to be scanned again right away. */
    fun reset() = synchronized(lock) {
        lastPayload = null
        lastAcceptedAt = 0L
    }
}
