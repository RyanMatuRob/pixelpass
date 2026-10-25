package com.pixelpass.scan

import com.pixelpass.qr.PassVerdict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanIntakeCoordinatorTest {

    private val now = 1_760_000_000_000L
    private val dao = FakeScannedNonceDao()

    private fun coordinator(gate: (String, Long) -> ScanIntake): ScanIntakeCoordinator =
        ScanIntakeCoordinator(
            replayGuard = ScanReplayGuard(dao = dao, clock = { now }),
            gate = gate,
            clock = { now }
        )

    private fun signedPass(nonce: String?) = ScanIntake.Accepted(
        jwt = "signed-jwt",
        source = ScanSource.SIGNED_PASS,
        nonce = nonce,
        userId = "user-1"
    )

    @Test
    fun acceptedPassContinuesAndConsumesItsNonce() = runBlocking {
        val accepted = signedPass("nonce-1")
        val result = coordinator { _, _ -> accepted }.intake("signed-qr")

        assertEquals(accepted, result)
        assertEquals(1, dao.count())
    }

    @Test
    fun theSamePassScannedTwiceIsRefusedAsReplay() = runBlocking {
        val accepted = signedPass("nonce-1")
        val coordinator = coordinator { _, _ -> accepted }

        val first = coordinator.intake("signed-qr")
        val second = coordinator.intake("signed-qr")

        assertTrue(first is ScanIntake.Accepted)
        assertTrue(second is ScanIntake.Rejected)
        second as ScanIntake.Rejected
        assertEquals(PassVerdict.REPLAY, second.verdict)
        assertEquals(REPLAY_REASON, second.reason)
        assertEquals(1, dao.count())
    }

    @Test
    fun aPassRefusedByTheGateNeverReachesTheCache() = runBlocking {
        val rejection = ScanIntake.Rejected("Signature does not match", PassVerdict.SIGNATURE_INVALID)

        val result = coordinator { _, _ -> rejection }.intake("tampered-qr")

        assertEquals(rejection, result)
        assertEquals(0, dao.count())
    }

    @Test
    fun aLegacyTokenWithoutNonceIsNeverReportedAsReplay() = runBlocking {
        val legacy = ScanIntake.Accepted(
            jwt = "legacy-jwt",
            source = ScanSource.LEGACY_TOKEN,
            nonce = null,
            userId = "user-2"
        )
        val coordinator = coordinator { _, _ -> legacy }

        val first = coordinator.intake("legacy-qr")
        val second = coordinator.intake("legacy-qr")

        assertEquals(legacy, first)
        assertEquals(legacy, second)
        assertEquals(0, dao.count())
    }

    @Test
    fun theGateSeesTheCoordinatorClock() = runBlocking {
        var gateNow = -1L
        val coordinator = coordinator { _, now -> gateNow = now; signedPass("nonce-9") }

        coordinator.intake("signed-qr")

        assertEquals(now, gateNow)
    }
}
