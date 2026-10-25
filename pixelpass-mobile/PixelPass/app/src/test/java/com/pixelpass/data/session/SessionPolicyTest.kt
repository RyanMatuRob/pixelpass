package com.pixelpass.data.session

import com.pixelpass.security.JwtClaimsDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SessionPolicyTest {

    private val now = 1_760_000_000_000L

    @Test
    fun guardTtlIsTwelveHours() {
        assertEquals(12L * 60L * 60L * 1000L, SessionPolicy.ttlMillis("SECURITY"))
    }

    @Test
    fun guardRoleIsCaseInsensitive() {
        assertTrue(SessionPolicy.isGuard("security"))
        assertTrue(SessionPolicy.isGuard("SECURITY"))
        assertFalse(SessionPolicy.isGuard("STUDENT"))
    }

    @Test
    fun studentAndStaffTtlIsSevenDays() {
        assertEquals(7L * 24L * 60L * 60L * 1000L, SessionPolicy.ttlMillis("STUDENT"))
        assertEquals(7L * 24L * 60L * 60L * 1000L, SessionPolicy.ttlMillis("STAFF"))
    }

    @Test
    fun expiryIsCappedByJwtExpiry() {
        val jwtExpiry = now + 60_000L
        assertEquals(jwtExpiry, SessionPolicy.expireAt(now, "STUDENT", jwtExpiry))
    }

    @Test
    fun expiryUsesPolicyTtlWhenJwtIsLonger() {
        val jwtExpiry = now + 30L * 24L * 60L * 60L * 1000L
        assertEquals(now + SessionPolicy.GUARD_TTL_MILLIS, SessionPolicy.expireAt(now, "SECURITY", jwtExpiry))
    }

    @Test
    fun missingJwtExpiryFallsBackToPolicyTtl() {
        assertEquals(now + SessionPolicy.STANDARD_TTL_MILLIS, SessionPolicy.expireAt(now, "STAFF", 0L))
    }

    @Test
    fun sessionExpiresExactlyAtItsDeadline() {
        assertFalse(SessionPolicy.isExpired(now + 1, now))
        assertTrue(SessionPolicy.isExpired(now, now))
        assertTrue(SessionPolicy.isExpired(now - 1, now))
    }

    @Test
    fun sessionFactoryUsesJwtSubjectAndRequestedRole() {
        val token = jwt(subject = "user-42", role = "SECURITY", expiresAtSeconds = now / 1000 + 3_600)

        val session = SessionFactory.fromLogin(token, "SECURITY", keepSignedIn = true, now = now)

        assertNotNull(session)
        assertEquals("user-42", session!!.userId)
        assertEquals("SECURITY", session.role)
        assertTrue(session.keepSignedIn)
        assertEquals(now + 3_600_000L, session.expiresAtEpochMillis)
        assertEquals(3_600_000L, session.remainingMillis(now))
    }

    @Test
    fun sessionFactoryRejectsUnreadableToken() {
        assertEquals(null, SessionFactory.fromLogin("not-a-jwt", "STAFF", true, now))
    }

    private fun jwt(subject: String, role: String, expiresAtSeconds: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString(
            """{"userId":"$subject","role":"$role","exp":$expiresAtSeconds}""".toByteArray()
        )
        return "$header.$payload.signature"
    }
}

class JwtClaimsDecoderTest {

    @Test
    fun decodesSubjectRoleAndExpiry() {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256"}""".toByteArray())
        val payload = encoder.encodeToString("""{"userId":"abc","role":"STAFF","exp":1700000000}""".toByteArray())

        val claims = JwtClaimsDecoder.decode("$header.$payload.sig")

        assertNotNull(claims)
        assertEquals("abc", claims!!.subjectId)
        assertEquals("STAFF", claims.role)
        assertEquals(1_700_000_000_000L, claims.expiresAtEpochMillis)
    }

    @Test
    fun fallsBackToSubClaim() {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val payload = encoder.encodeToString("""{"sub":"xyz","exp":1700000000}""".toByteArray())

        val claims = JwtClaimsDecoder.decode("head.$payload.sig")

        assertEquals("xyz", claims?.subjectId)
        assertEquals("", claims?.role)
    }

    @Test
    fun rejectsMalformedTokens() {
        assertEquals(null, JwtClaimsDecoder.decode("nonsense"))
        assertEquals(null, JwtClaimsDecoder.decode("a.%%%.c"))
    }
}
