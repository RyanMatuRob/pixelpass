package com.pixelpass.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeohashTest {

    @Test
    fun encodesKnownCoordinates() {
        assertEquals("u4pruydqqvj", Geohash.encode(57.64911, 10.40744, 11))
        assertEquals("ezs42", Geohash.encode(42.6, -5.6, 5))
        assertEquals("s0000", Geohash.encode(0.0, 0.0, 5))
    }

    @Test
    fun respectsPrecision() {
        val hash = Geohash.encode(-1.2864, 36.8172)
        assertEquals(Geohash.DEFAULT_PRECISION, hash.length)
        assertEquals(4, Geohash.encode(-1.2864, 36.8172, 4).length)
    }

    @Test
    fun rejectsOutOfRangeCoordinates() {
        assertThrows(IllegalArgumentException::class.java) { Geohash.encode(91.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Geohash.encode(0.0, 181.0) }
    }
}
