package com.pixelpass.data.session

/**
 * Everything the app needs to act on behalf of a signed-in user without a round
 * trip. Treat the token as a secret: it is only persisted encrypted.
 */
data class UserSession(
    val token: String,
    val userId: String,
    val role: String,
    val keepSignedIn: Boolean,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
) {

    val isGuard: Boolean
        get() = SessionPolicy.isGuard(role)

    fun remainingMillis(now: Long = System.currentTimeMillis()): Long =
        (expiresAtEpochMillis - now).coerceAtLeast(0L)

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        SessionPolicy.isExpired(expiresAtEpochMillis, now)
}
