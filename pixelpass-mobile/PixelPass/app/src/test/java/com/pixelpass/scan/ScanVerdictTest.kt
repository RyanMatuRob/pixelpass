package com.pixelpass.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanVerdictTest {

    @Test
    fun aGatewayGrantShowsTheApprovedScreen() {
        val outcome = ScanOutcome(granted = true, headline = "ignored", detail = "Clear for entry")

        assertEquals(ScanVerdict.GRANTED, outcome.toVerdict())
    }

    @Test
    fun anOfflineGrantShowsTheOfflineApprovalScreen() {
        val outcome = ScanOutcome(
            granted = true,
            headline = "ignored",
            detail = "Approved on this device",
            queuedForSync = true
        )

        assertEquals(ScanVerdict.OFFLINE_APPROVAL, outcome.toVerdict())
    }

    @Test
    fun aRefusalShowsTheDeniedScreenEvenWhenQueued() {
        val refused = ScanOutcome(granted = false, headline = "ignored", detail = "No")
        val queuedRefusal = refused.copy(queuedForSync = true)

        assertEquals(ScanVerdict.DENIED, refused.toVerdict())
        assertEquals(ScanVerdict.DENIED, queuedRefusal.toVerdict())
    }

    @Test
    fun everyVerdictHasItsOwnHeadline() {
        val headlines = ScanVerdict.entries.map { it.headline }

        assertEquals(ScanVerdict.entries.size, headlines.toSet().size)
        assertEquals("PASS ALREADY USED", ScanVerdict.REPLAY.headline)
    }
}
