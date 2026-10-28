package com.pixelpass.scan

import com.pixelpass.qr.PassVerdict
import com.pixelpass.qr.PassVerifier
import com.pixelpass.qr.QrPayloadCodec
import com.pixelpass.security.JwtClaimsDecoder

/** Which kind of code the scanner just read. */
enum class ScanSource {
    /** A signed PixelPass payload: device signature, binding and rotation were checked. */
    SIGNED_PASS,

    /** A bare gateway JWT printed by a pre-signing build. Only its expiry is checkable offline. */
    LEGACY_TOKEN
}

/** Result of reading one QR code on the guard's device, before any network call. */
sealed interface ScanIntake {

    /** The code may continue to face capture. [nonce] is null for legacy tokens. */
    data class Accepted(
        val jwt: String,
        val source: ScanSource,
        val nonce: String?,
        val userId: String?,
        /** Fallback code from the signed payload, absent on legacy tokens. */
        val otp: String? = null
    ) : ScanIntake

    /** The code must not continue; the guard sees [reason] on the viewfinder. */
    data class Rejected(
        val reason: String,
        val verdict: PassVerdict? = null
    ) : ScanIntake
}

/**
 * First line of defence on the guard's phone.
 *
 * Signed passes go through [PassVerifier], which needs no network at all. A bare
 * JWT is still accepted for backwards compatibility with older app builds, but
 * only when its own `exp` claim is intact, and it is never treated as an offline
 * approval because nothing about it can be verified locally.
 */
object ScanGate {

    fun intake(raw: String, now: Long = System.currentTimeMillis()): ScanIntake {
        val candidate = raw.trim()
        if (candidate.isEmpty()) {
            return ScanIntake.Rejected("Nothing was scanned")
        }

        if (QrPayloadCodec.parse(candidate) != null) {
            val check = PassVerifier.inspect(candidate, now)
            val payload = check.payload
            if (check.isGranted && payload != null) {
                return ScanIntake.Accepted(
                    jwt = payload.jwt,
                    source = ScanSource.SIGNED_PASS,
                    nonce = payload.nonce,
                    userId = payload.userId,
                    otp = payload.otp
                )
            }
            return ScanIntake.Rejected(
                reason = check.reason.ifBlank { "Pass rejected (${check.verdict.name})" },
                verdict = check.verdict
            )
        }

        val claims = JwtClaimsDecoder.decode(candidate)
            ?: return ScanIntake.Rejected("Not a PixelPass code", PassVerdict.MALFORMED)

        if (claims.expiresAtEpochMillis in 1 until now) {
            return ScanIntake.Rejected("Pass token has expired", PassVerdict.EXPIRED)
        }

        return ScanIntake.Accepted(
            jwt = candidate,
            source = ScanSource.LEGACY_TOKEN,
            nonce = null,
            userId = claims.subjectId
        )
    }
}
