package com.pixelpass.utils

/**
 * Minimal geohash encoder (base32, no dependencies) used to stamp the pass with
 * the coarse area it was issued in.
 */
object Geohash {

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /** 8 characters is roughly a 38 m x 19 m cell, enough for a campus gate. */
    const val DEFAULT_PRECISION = 8

    fun encode(latitude: Double, longitude: Double, precision: Int = DEFAULT_PRECISION): String {
        require(precision in 1..12) { "Geohash precision must be between 1 and 12" }
        require(latitude in -90.0..90.0) { "Latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "Longitude out of range: $longitude" }

        var minLat = -90.0
        var maxLat = 90.0
        var minLon = -180.0
        var maxLon = 180.0
        var isLongitudeTurn = true
        var bit = 0
        var accumulator = 0
        val hash = StringBuilder(precision)

        while (hash.length < precision) {
            if (isLongitudeTurn) {
                val mid = (minLon + maxLon) / 2
                if (longitude >= mid) {
                    accumulator = (accumulator shl 1) or 1
                    minLon = mid
                } else {
                    accumulator = accumulator shl 1
                    maxLon = mid
                }
            } else {
                val mid = (minLat + maxLat) / 2
                if (latitude >= mid) {
                    accumulator = (accumulator shl 1) or 1
                    minLat = mid
                } else {
                    accumulator = accumulator shl 1
                    maxLat = mid
                }
            }

            isLongitudeTurn = !isLongitudeTurn
            if (++bit == 5) {
                hash.append(BASE32[accumulator])
                bit = 0
                accumulator = 0
            }
        }

        return hash.toString()
    }
}
