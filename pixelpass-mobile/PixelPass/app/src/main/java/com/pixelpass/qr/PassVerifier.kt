package com.pixelpass.qr

import com.pixelpass.security.JwtClaimsDecoder

/** Outcome of inspecting a scanned QR string on the guard's device. */
enum class PassVerdict {
    /** Signature, identity, token expiry and rotation window all check out. */
    VALID,
    /** The QR string is not a PixelPass payload at all. */
    MALFORMED,
    /** The device signature does not match the key embedded in the payload. */
    SIGNATURE_INVALID,
    /** The payload's user does not match the owner of the embedded token. */
    IDENTITY_MISMATCH,
    /** Rotation window, clock skew or the token's own `exp` was exceeded. */
    EXPIRED,
    /** The nonce of this pass was already accepted on this guard's device. */
    REPLAY
}

data class PassCheck(
    val verdict: PassVerdict,
    val payload: PassPayload? = null,
    val reason: String = ""
) {
    val isGranted: Boolean
        get() = verdict == PassVerdict.VALID
}

/**
 * Local-only verification used while the guard is offline.
 *
 * The JWT signature cannot be checked without the gateway's public key, so the
 * checks here are the ones that need no network: device signature, subject
 * binding, token expiry and the 30 s rotation window. The backend still has the
 * final say once the scan is synced.
 */
object PassVerifier {

    fun inspect(raw: String, now: Long = System.currentTimeMillis()): PassCheck {
        val parsed = QrPayloadCodec.parse(raw)
            ?: return PassCheck(PassVerdict.MALFORMED, reason = "Not a PixelPass payload")

        if (!QrPayloadCodec.verifySignature(parsed)) {
            return PassCheck(
                PassVerdict.SIGNATURE_INVALID,
                parsed.payload,
                "Device signature does not match the embedded key"
            )
        }

        val payload = parsed.payload
        val claims = JwtClaimsDecoder.decode(payload.jwt)
            ?: return PassCheck(PassVerdict.MALFORMED, payload, "Embedded token is unreadable")

        if (claims.subjectId != payload.userId) {
            return PassCheck(
                PassVerdict.IDENTITY_MISMATCH,
                payload,
                "Embedded token belongs to another user"
            )
        }

        if (claims.expiresAtEpochMillis in 1 until now) {
            return PassCheck(PassVerdict.EXPIRED, payload, "Embedded token has expired")
        }

        val oldestAccepted = now - (PASS_TTL_MILLIS + PASS_CLOCK_SKEW_MILLIS)
        if (payload.issuedAtEpochMillis < oldestAccepted) {
            return PassCheck(PassVerdict.EXPIRED, payload, "Pass is older than its rotation window")
        }

        if (payload.issuedAtEpochMillis > now + PASS_CLOCK_SKEW_MILLIS) {
            return PassCheck(PassVerdict.EXPIRED, payload, "Pass is stamped in the future")
        }

        return PassCheck(PassVerdict.VALID, payload)
    }
}
