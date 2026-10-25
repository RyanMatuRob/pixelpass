package com.pixelpass.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Best-effort geohash of the device position.
 *
 * Returns an empty string when location permission is missing or no provider has
 * a fix yet: a pass must still be issuable inside a building, and the guard's
 * proximity check is only advisory in the offline path.
 */
class LocationGeohashProvider(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun currentGeohash(precision: Int = Geohash.DEFAULT_PRECISION): String {
        if (!hasLocationPermission()) return ""

        val manager = context.getSystemService(LocationManager::class.java) ?: return ""
        val location = runCatching {
            manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull() ?: return ""

        return runCatching { Geohash.encode(location.latitude, location.longitude, precision) }
            .getOrDefault("")
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
