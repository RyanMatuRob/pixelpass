package com.pixelpass.qr

/**
 * The canonical content of one QR pass.
 *
 * The payload is deliberately flat and fully self-contained: a guard can verify
 * it offline with nothing but the bytes in the QR code. Optional fields are
 * omitted from the wire format instead of being sent as nulls so the signature
 * is always computed over exactly what the scanner sees.
 */
data class PassPayload(
    /** Owner of the pass; must match the `userId` claim inside [jwt]. */
    val userId: String,
    val issuedAtEpochMillis: Long,
    /** Coarse location of issuance, or null when no fix was available. */
    val geohash: String? = null,
    /** Reserved for the ML face embedding; omitted until the model ships. */
    val faceEmbeddingBase64: String? = null,
    /** One-time value used by the replay cache. */
    val nonce: String,
    /**
     * Six-digit fallback code the holder reads out when the guard's face check
     * fails. Optional so passes issued by older builds still parse.
     */
    val otp: String? = null,
    /** Gateway-issued JWT, replayed to the backend when the guard syncs. */
    val jwt: String,
    /** X.509 encoded ECDSA P-256 public key that signs this payload. */
    val devicePublicKeyBase64: String
)

/** A payload plus the exact string that was printed into the QR code. */
data class IssuedPass(
    val payload: PassPayload,
    val encoded: String
) {
    val issuedAtEpochMillis: Long
        get() = payload.issuedAtEpochMillis

    val nonce: String
        get() = payload.nonce

    /** The fallback code printed under the QR code, or null for legacy passes. */
    val otp: String?
        get() = payload.otp
}
