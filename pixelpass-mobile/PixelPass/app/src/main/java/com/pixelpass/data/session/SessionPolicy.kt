package com.pixelpass.data.session

import com.pixelpass.security.JwtClaimsDecoder

/**
 * "Keep me signed in" is only as safe as its time limit: a lost guard phone must
 * stop working within half a day even if the box was checked.
 */
object SessionPolicy {

    const val GUARD_ROLE = "SECURITY"
    const val GUARD_TTL_MILLIS: Long = 12L * 60L * 60L * 1000L
    const val STANDARD_TTL_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L

    fun isGuard(role: String): Boolean = role.equals(GUARD_ROLE, ignoreCase = true)

    fun ttlMillis(role: String): Long = if (isGuard(role)) GUARD_TTL_MILLIS else STANDARD_TTL_MILLIS

    /**
     * Local expiry is the earlier of the role TTL and the JWT's own expiry, so the
     * app never claims a session the server has already stopped accepting.
     */
    fun expireAt(now: Long, role: String, jwtExpiresAtEpochMillis: Long): Long {
        val policyExpiry = now + ttlMillis(role)
        return if (jwtExpiresAtEpochMillis > 0L) minOf(policyExpiry, jwtExpiresAtEpochMillis) else policyExpiry
    }

    fun isExpired(expiresAtEpochMillis: Long, now: Long): Boolean = now >= expiresAtEpochMillis
}

object SessionFactory {

    fun fromLogin(token: String, role: String, keepSignedIn: Boolean, now: Long): UserSession? {
        val claims = JwtClaimsDecoder.decode(token) ?: return null
        return UserSession(
            token = token,
            userId = claims.subjectId,
            role = role,
            keepSignedIn = keepSignedIn,
            issuedAtEpochMillis = now,
            expiresAtEpochMillis = SessionPolicy.expireAt(now, role, claims.expiresAtEpochMillis)
        )
    }
}
