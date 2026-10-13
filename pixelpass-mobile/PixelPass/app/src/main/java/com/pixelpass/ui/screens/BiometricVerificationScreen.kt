package com.pixelpass.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pixelpass.network.ScanRequest
import com.pixelpass.network.TerminalCoordinate
import com.pixelpass.network.RetrofitClient
import com.pixelpass.ui.components.FaceCameraPreview
import com.pixelpass.ui.theme.AppTypography
import com.pixelpass.utils.DeviceTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiometricVerificationScreen(jwt: String, onVerificationComplete: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var statusText by remember { mutableStateOf("Align face in the oval...") }
    var isProcessing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Biometric Verification", style = AppTypography.headlineMedium) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Status Indicator
            Surface(color = if (isProcessing) Color(0xFFFEF3C7) else Color(0xFFE0E7FF), shape = RoundedCornerShape(16.dp)) {
                Text(statusText, modifier = Modifier.padding(16.dp, 8.dp), color = Color.Black)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Front Camera Viewfinder (Oval Shape)
            Box(
                modifier = Modifier
                    .size(300.dp, 400.dp)
                    .clip(CircleShape) // Creates an oval cutout
                    .background(Color.Black)
            ) {
                FaceCameraPreview { faceBase64 ->
                    if (!isProcessing) {
                        isProcessing = true
                        statusText = "Face Captured! Fetching Telemetry..."

                        // 1. Fetch live GPS coordinates
                        DeviceTelemetry.getLiveLocation(context,
                            onLocationReceived = { location ->
                                statusText = "Running ML Security Audit..."

                                // 2. Extract hardware fingerprint
                                val fingerprint = DeviceTelemetry.getDeviceFingerprint(context)

                                // 3. Execute API call on background thread
                                coroutineScope.launch {
                                    try {
                                        val request = ScanRequest(
                                            token = jwt,
                                            faceImageBase64 = faceBase64,
                                            terminalCoordinate = location,
                                            deviceFingerprint = fingerprint
                                        )

                                        // TODO: Ensure RetrofitClient is properly imported
                                        val response = RetrofitClient.instance.verifyScan(request)

                                        withContext(Dispatchers.Main) {
                                            if (response.isSuccessful && response.body()?.access == "GRANTED") {
                                                Toast.makeText(context, "✅ ACCESS GRANTED", Toast.LENGTH_LONG).show()
                                            } else {
                                                val reason = response.body()?.reason ?: "Anomaly Detected"
                                                Toast.makeText(context, "🚫 DENIED: $reason", Toast.LENGTH_LONG).show()
                                            }
                                            onVerificationComplete() // Route back to standby
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Network Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                            onVerificationComplete()
                                        }
                                    }
                                }
                            },
                            onLocationFailed = {
                                isProcessing = false
                                statusText = "Failed to get GPS. Try again."
                            }
                        )
                    }
                }
            }
        }
    }
}