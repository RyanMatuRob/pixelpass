package com.pixelpass.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDebouncerTest {

    private var now = 1_000L
    private val debouncer = ScanDebouncer(cooldownMillis = 4_000L, clock = { now })

    @Test
    fun firstReadOfAPayloadIsDelivered() {
        assertTrue(debouncer.tryAcquire("pass-a"))
    }

    @Test
    fun identicalFramesInsideTheCooldownAreDropped() {
        assertTrue(debouncer.tryAcquire("pass-a"))

        now += 1_500
        assertFalse(debouncer.tryAcquire("pass-a"))
        assertFalse(debouncer.tryAcquire("pass-a"))
    }

    @Test
    fun samePassIsDeliveredAgainAfterTheCooldown() {
        assertTrue(debouncer.tryAcquire("pass-a"))

        now += 4_000
        assertTrue(debouncer.tryAcquire("pass-a"))
    }

    @Test
    fun rotatedPassIsDeliveredImmediately() {
        assertTrue(debouncer.tryAcquire("pass-a"))

        now += 100
        assertTrue(debouncer.tryAcquire("pass-b"))
    }

    @Test
    fun resetAllowsTheSamePassRightAway() {
        assertTrue(debouncer.tryAcquire("pass-a"))
        assertFalse(debouncer.tryAcquire("pass-a"))

        debouncer.reset()

        assertTrue(debouncer.tryAcquire("pass-a"))
    }
}
