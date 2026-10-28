package com.pixelpass.qr

import java.security.SecureRandom

/**
 * The six-digit fallback code that travels inside the signed pass.
 *
 * The QR code is unreadable when a screen is cracked or the guard's camera
 * refuses to focus, and the face check can fail on a bad angle, so the holder
 * can read this code out instead. Because it sits inside the payload that the
 * device signs, the guard can check it offline and it cannot be swapped for
 * another code without breaking the signature.
 *
 * It is a door convenience, not a secret: anyone who scans the QR code can read
 * the same digits.
 */
object PassOtp {

    /** Digits in one code, matching what the pass screen prints. */
    const val LENGTH = 6

    private val random = SecureRandom()

    /** A fresh code with leading zeroes kept, for example `"004821"`. */
    fun generate(): String =
        random.nextInt(1_000_000).toString().padStart(LENGTH, '0')

    /** True when [entered] is the code carried by [expected]; blanks never match. */
    fun matches(expected: String?, entered: String): Boolean {
        val candidate = entered.trim()
        return candidate.isNotEmpty() && candidate == expected?.trim()
    }
}
