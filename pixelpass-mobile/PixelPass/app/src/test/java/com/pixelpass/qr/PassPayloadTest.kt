package com.pixelpass.qr

import com.pixelpass.data.session.UserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PassPayloadTest {

    private val now = 1_760_000_000_000L
    private val signer = TestPassSigner()

    @Test
    fun issuedPassVerifiesWhileFresh() {
        val issuer = PassPayloadIssuer(signer, geohashSource = { "kzn6v7x" }, clock = { now })

        val pass = issuer.issue(session())

        assertNotNull(QrPayloadCodec.parse(pass.encoded))
        assertEquals("kzn6v7x", pass.payload.geohash)
        assertEquals(signer.publicKeyBase64(), pass.payload.devicePublicKeyBase64)

        val check = PassVerifier.inspect(pass.encoded, now + 5_000)
        assertTrue(check.reason, check.isGranted)
        assertEquals("user-1", check.payload?.userId)
    }

    @Test
    fun missingLocationIsOmittedInsteadOfSentBlank() {
        val issuer = PassPayloadIssuer(signer, geohashSource = { "" }, clock = { now })

        val pass = issuer.issue(session())

        assertNull(pass.payload.geohash)
        assertFalse(QrPayloadCodec.canonicalJson(pass.payload).contains("\"l\""))
        assertTrue(PassVerifier.inspect(pass.encoded, now + 1_000).isGranted)
    }

    @Test
    fun eachRotationUsesAFreshNonce() {
        val issuer = PassPayloadIssuer(signer, clock = { now })

        val first = issuer.issue(session())
        val second = issuer.issue(session())

        assertNotEquals(first.nonce, second.nonce)
    }

    @Test
    fun rotatingPassKeepsTheSameSigningKey() {
        val issuer = PassPayloadIssuer(signer, clock = { now })

        val first = issuer.issue(session())
        val second = issuer.issue(session())

        assertEquals(first.payload.devicePublicKeyBase64, second.payload.devicePublicKeyBase64)
    }

    @Test
    fun tamperedPayloadFailsTheDeviceSignature() {
        val issuer = PassPayloadIssuer(signer, clock = { now })
        val parsed = QrPayloadCodec.parse(issuer.issue(session()).encoded)!!

        // Same signature, different user: the canonical bytes no longer match.
        val forged = QrPayloadCodec.withSignature(
            parsed.payload.copy(userId = "intruder"),
            parsed.signatureBase64
        )

        assertEquals(PassVerdict.SIGNATURE_INVALID, PassVerifier.inspect(forged, now).verdict)
    }

    @Test
    fun stalePassIsRejected() {
        val issuer = PassPayloadIssuer(signer, clock = { now })

        val check = PassVerifier.inspect(
            issuer.issue(session()).encoded,
            now + PASS_TTL_MILLIS + PASS_CLOCK_SKEW_MILLIS + 1
        )

        assertEquals(PassVerdict.EXPIRED, check.verdict)
    }

    @Test
    fun passStampedTooFarInTheFutureIsRejected() {
        val issuer = PassPayloadIssuer(signer, clock = { now + PASS_CLOCK_SKEW_MILLIS + 1 })

        assertEquals(PassVerdict.EXPIRED, PassVerifier.inspect(issuer.issue(session()).encoded, now).verdict)
    }

    @Test
    fun tokenOwnedByAnotherUserIsRejected() {
        val payload = PassPayload(
            userId = "someone-else",
            issuedAtEpochMillis = now,
            nonce = "nonce-1",
            jwt = jwt(subject = "user-1", expiresAtMillis = now + 60_000),
            devicePublicKeyBase64 = signer.publicKeyBase64()
        )

        val check = PassVerifier.inspect(QrPayloadCodec.sign(payload, signer.keyPair.private), now)

        assertEquals(PassVerdict.IDENTITY_MISMATCH, check.verdict)
    }

    @Test
    fun expiredTokenIsRejected() {
        val issuer = PassPayloadIssuer(signer, clock = { now })
        val signed = issuer.issue(session(token = jwt("user-1", now - 1_000)))

        assertEquals(PassVerdict.EXPIRED, PassVerifier.inspect(signed.encoded, now).verdict)
    }

    @Test
    fun garbageIsRejectedAsMalformed() {
        assertEquals(PassVerdict.MALFORMED, PassVerifier.inspect("not-a-pass", now).verdict)
        assertEquals(PassVerdict.MALFORMED, PassVerifier.inspect("""{"u":"a"}""", now).verdict)
    }

    @Test
    fun canonicalJsonKeepsFieldOrder() {
        val payload = PassPayload(
            userId = "user-1",
            issuedAtEpochMillis = now,
            geohash = "kzn6v7x",
            faceEmbeddingBase64 = "embedding",
            nonce = "nonce-1",
            jwt = "token",
            devicePublicKeyBase64 = "key"
        )

        assertEquals(
            """{"u":"user-1","t":1760000000000,"l":"kzn6v7x","e":"embedding","n":"nonce-1","j":"token","k":"key"}""",
            QrPayloadCodec.canonicalJson(payload)
        )
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
            """{"userId":"$subject","role":"STUDENT","exp":${expiresAtMillis / 1000}}""".toByteArray()
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
