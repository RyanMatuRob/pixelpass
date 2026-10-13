package com.pixelpass.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.pixelpass.network.TerminalCoordinate

object DeviceTelemetry {

    // Extracts the immutable 64-bit Android Hardware ID
    fun getDeviceFingerprint(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    // Fetches a fresh GPS lock rather than relying on a stale cached location
    @SuppressLint("MissingPermission")
    fun getLiveLocation(
        context: Context,
        onLocationReceived: (TerminalCoordinate) -> Unit,
        onLocationFailed: () -> Unit
    ) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val cancellationTokenSource = CancellationTokenSource()

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellationTokenSource.token
        ).addOnSuccessListener { location ->
            if (location != null) {
                onLocationReceived(TerminalCoordinate(location.latitude, location.longitude))
            } else {
                onLocationFailed()
            }
        }.addOnFailureListener {
            onLocationFailed()
        }
    }
}