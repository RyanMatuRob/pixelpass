package com.pixelpass.network

data class TerminalCoordinate(
    val latitude: Double,
    val longitude: Double
)

data class ScanRequest(
    val token: String,
    val nonce: String? = null,
    val faceImageBase64: String, // Replaced biometricConfidence and biometricOutcome
    val terminalCoordinate: TerminalCoordinate,
    val deviceFingerprint: String
)

data class ScanResponse(
    val access: String,
    val message: String?,
    val reason: String?,
    val score: Double?
)