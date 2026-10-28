package com.pixelpass.qr

import com.pixelpass.data.session.UserSession
import com.pixelpass.security.DeviceKeyStore
import java.util.UUID

/** Signs a canonical payload for the QR code and reports the matching public key. */
interface PassSigner {
    fun publicKeyBase64(): String
    fun sign(payload: PassPayload): String
}

/** Keystore-backed signer: the private key never leaves the secure element. */
class DevicePassSigner(private val deviceKeyStore: DeviceKeyStore) : PassSigner {

    override fun publicKeyBase64(): String = deviceKeyStore.publicKeyBase64()

    override fun sign(payload: PassPayload): String =
        QrPayloadCodec.sign(payload, deviceKeyStore.getOrCreateKeyPair().private)
}

/**
 * Builds and signs the QR payload shown on the pass screen.
 *
 * Pure apart from [PassSigner], so the rotation, geohash and nonce policy can be
 * unit tested with a plain in-memory EC key.
 */
class PassPayloadIssuer(
    private val signer: PassSigner,
    private val geohashSource: () -> String = { "" },
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonceSource: () -> String = { UUID.randomUUID().toString() },
    private val otpSource: () -> String = { PassOtp.generate() }
) {

    /**
     * @throws java.security.GeneralSecurityException when the Keystore key cannot sign.
     */
    fun issue(session: UserSession): IssuedPass {
        val payload = PassPayload(
            userId = session.userId,
            issuedAtEpochMillis = clock(),
            geohash = geohashSource().takeIf { it.isNotBlank() },
            faceEmbeddingBase64 = null,
            nonce = nonceSource(),
            otp = otpSource().takeIf { it.isNotBlank() },
            jwt = session.token,
            devicePublicKeyBase64 = signer.publicKeyBase64()
        )
        return IssuedPass(payload = payload, encoded = signer.sign(payload))
    }
}

