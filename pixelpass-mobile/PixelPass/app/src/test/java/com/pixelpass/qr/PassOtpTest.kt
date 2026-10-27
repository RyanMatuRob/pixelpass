package com.pixelpass.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassOtpTest {

    @Test
    fun generatedCodeIsAlwaysSixDigits() {
        repeat(200) {
            val code = PassOtp.generate()

            assertEquals(PassOtp.LENGTH, code.length)
            assertTrue("Not a digit in '$code'", code.all { it.isDigit() })
        }
    }

    @Test
    fun generatedCodesAreNotAllTheSame() {
        val codes = (1..20).map { PassOtp.generate() }.toSet()

        assertTrue(codes.size > 1)
    }

    @Test
    fun matchingCodeIsApproved() {
        assertTrue(PassOtp.matches("004821", "004821"))
    }

    @Test
    fun surroundingSpacesAreIgnored() {
        assertTrue(PassOtp.matches("004821", "  004821 "))
        assertTrue(PassOtp.matches(" 004821 ", "004821"))
    }

    @Test
    fun wrongOrBlankEntryIsRefused() {
        assertFalse(PassOtp.matches("004821", "004822"))
        assertFalse(PassOtp.matches("004821", "4821"))
        assertFalse(PassOtp.matches("004821", ""))
        assertFalse(PassOtp.matches("004821", "   "))
    }

    @Test
    fun missingOrBlankExpectedCodeNeverMatches() {
        assertFalse(PassOtp.matches(null, "004821"))
        assertFalse(PassOtp.matches("", ""))
        assertFalse(PassOtp.matches("   ", "   "))
    }
}
