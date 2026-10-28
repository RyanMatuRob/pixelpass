package com.pixelpass.scan

import com.pixelpass.data.session.UserSession
import com.pixelpass.qr.PASS_CLOCK_SKEW_MILLIS
import com.pixelpass.qr.PASS_TTL_MILLIS
import com.pixelpass.qr.PassPayload
import com.pixelpass.qr.PassPayloadIssuer
import com.pixelpass.qr.PassSigner
import com.pixelpass.qr.PassVerdict
import com.pixelpass.qr.QrPayloadCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ScanGateTest {

    private val now = 1_760_000_000_000L
    private val signer = TestPassSigner()

    @Test
    fun signedPassIsAcceptedWithItsNonce() {
        val pass = PassPayloadIssuer(signer, clock = { now }).issue(session())

        val result = ScanGate.intake(pass.encoded, now + 2_000)

        assertTrue(result is ScanIntake.Accepted)
        val accepted = result as ScanIntake.Accepted
        assertEquals(ScanSource.SIGNED_PASS, accepted.source)
        assertEquals(pass.nonce, accepted.nonce)
        assertEquals("user-1", accepted.userId)
    }

    @Test
    fun tamperedPassNeverReachesFaceCapture() {
        val parsed = QrPayloadCodec.parse(PassPayloadIssuer(signer, clock = { now }).issue(session()).encoded)!!
        val forged = QrPayloadCodec.withSignature(
            parsed.payload.copy(nonce = "replayed-nonce"),
            parsed.signatureBase64
        )

        val result = ScanGate.intake(forged, now)

        assertTrue(result is ScanIntake.Rejected)
        assertEquals(PassVerdict.SIGNATURE_INVALID, (result as ScanIntake.Rejected).verdict)
    }

    @Test
    fun acceptedPassCarriesItsFallbackCode() {
        val pass = PassPayloadIssuer(signer, clock = { now }, otpSource = { "731904" })
            .issue(session())

        val accepted = ScanGate.intake(pass.encoded, now + 2_000) as ScanIntake.Accepted

        assertEquals("731904", accepted.otp)
    }

    @Test
    fun stalePassIsRejectedBeforeFaceCapture() {
        val pass = PassPayloadIssuer(signer, clock = { now }).issue(session())

        val result = ScanGate.intake(pass.encoded, now + PASS_TTL_MILLIS + PASS_CLOCK_SKEW_MILLIS + 1)

        assertTrue(result is ScanIntake.Rejected)
        assertEquals(PassVerdict.EXPIRED, (result as ScanIntake.Rejected).verdict)
    }

    @Test
    fun passIsStillGoodNearTheEndOfItsWindow() {
        val pass = PassPayloadIssuer(signer, clock = { now }).issue(session())

        val result = ScanGate.intake(pass.encoded, now + PASS_TTL_MILLIS - 1_000)

        assertTrue(result is ScanIntake.Accepted)
    }

    @Test
    fun bareTokenIsAcceptedForBackwardsCompatibility() {
        val result = ScanGate.intake(jwt("user-9", now + 60_000), now)

        assertTrue(result is ScanIntake.Accepted)
        val accepted = result as ScanIntake.Accepted
        assertEquals(ScanSource.LEGACY_TOKEN, accepted.source)
        assertNull(accepted.nonce)
        assertNull(accepted.otp)
        assertEquals("user-9", accepted.userId)
    }

    @Test
    fun expiredBareTokenIsRejected() {
        val result = ScanGate.intake(jwt("user-9", now - 1_000), now)

        assertTrue(result is ScanIntake.Rejected)
        assertEquals(PassVerdict.EXPIRED, (result as ScanIntake.Rejected).verdict)
    }

    @Test
    fun garbageIsRejected() {
        assertTrue(ScanGate.intake("   ", now) is ScanIntake.Rejected)
        assertTrue(ScanGate.intake("not-a-pass", now) is ScanIntake.Rejected)
        assertTrue(ScanGate.intake("""{"u":"a"}""", now) is ScanIntake.Rejected)
    }

    private fun session(token: String = jwt("user-1", now + 3_600_000)) = UserSession(
        token = token,
        userId = "user-1",
        role = "STUDENT",
        keepSignedIn = true,
        issuedAtEpochMillis = now,
        expiresAtEpochMillis = now + 3_600_000
    )

    private fun jwt(subject: String, expiresAtMillis: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString(
            """{"userId":"$subject","role":"GUARD","exp":${expiresAtMillis / 1000}}""".toByteArray()
        )
        return "$header.$payload.signature"
    }

    /** In-memory ECDSA P-256 signer standing in for the Android Keystore. */
    private class TestPassSigner : PassSigner {
        val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        override fun publicKeyBase64(): String =
            Base64.getEncoder().encodeToString(keyPair.public.encoded)

        override fun sign(payload: PassPayload): String =
            QrPayloadCodec.sign(payload, keyPair.private)
    }
}
