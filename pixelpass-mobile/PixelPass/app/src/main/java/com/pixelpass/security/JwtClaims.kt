package com.pixelpass.security

import com.google.gson.JsonParser
import java.util.Base64

/**
 * Claims the mobile client reads out of the gateway-issued JWT.
 *
 * The signature is verified by the backend; locally the token is only decoded so
 * the app can enforce its own session TTL and know which user it is showing.
 */
data class JwtClaims(
    val subjectId: String,
    val role: String,
    val expiresAtEpochMillis: Long
)

object JwtClaimsDecoder {

    fun decode(token: String): JwtClaims? {
        val segments = token.split('.')
        if (segments.size < 2) return null
        return runCatching {
            val json = String(Base64.getUrlDecoder().decode(segments[1]), Charsets.UTF_8)
            val payload = JsonParser.parseString(json).asJsonObject
            val subject = payload.get("userId")?.asString
                ?: payload.get("sub")?.asString
                ?: return null
            val role = payload.get("role")?.asString.orEmpty()
            val expiresAt = payload.get("exp")?.asLong?.times(1000L) ?: 0L
            JwtClaims(subjectId = subject, role = role, expiresAtEpochMillis = expiresAt)
        }.getOrNull()
    }
}
