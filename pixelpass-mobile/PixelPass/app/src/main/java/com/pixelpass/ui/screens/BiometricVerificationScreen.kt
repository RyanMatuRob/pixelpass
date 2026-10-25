package com.pixelpass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pixelpass.PixelPassApp
import com.pixelpass.scan.ScanOutcome
import com.pixelpass.scan.ScanRepository
import com.pixelpass.scan.ScanSource
import com.pixelpass.ui.components.FaceCameraPreview
import com.pixelpass.ui.theme.AppTypography
import com.pixelpass.utils.DeviceTelemetry
import com.pixelpass.utils.FaceDetectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Face capture for one pass that [com.pixelpass.scan.ScanGate] already accepted.
 *
 * The gateway has the final say. When it cannot be reached the repository queues
 * the attempt and only a signed pass may be approved provisionally offline. The
 * decision is handed to [ScanVerdictScreen], which owns the whole screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiometricVerificationScreen(
    jwt: String,
    nonce: String?,
    signed: Boolean,
    onOutcome: (ScanOutcome) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scanRepository: ScanRepository = remember(context) {
        (context.applicationContext as PixelPassApp).container.scanRepository
    }

    var statusText by remember { mutableStateOf("Align face in the oval...") }
    var isProcessing by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ScanOutcome?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Biometric Verification", style = AppTypography.headlineMedium) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = if (isProcessing) Color(0xFFFEF3C7) else Color(0xFFE0E7FF),
                shape = RoundedCornerShape(16.dp)
            ) {
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
                FaceCameraPreview(
                    onStatusUpdate = { status ->
                        if (!isProcessing && outcome == null) {
                            statusText = when (status) {
                                FaceDetectionStatus.NO_FACE -> "Align face in the oval..."
                                FaceDetectionStatus.TOO_FAR -> "Move closer to the camera"
                                FaceDetectionStatus.OFF_CENTER -> "Center your face in the oval"
                                FaceDetectionStatus.ALIGNED -> "Face detected! Hold still..."
                            }
                        }
                    },
                    onFaceDetected = { faceBase64 ->
                        if (!isProcessing && outcome == null) {
                            isProcessing = true
                            statusText = "Face captured. Reading terminal telemetry..."

                            // 1. Fetch live GPS coordinates
                            DeviceTelemetry.getLiveLocation(
                                context,
                                onLocationReceived = { location ->
                                    statusText = "Checking the pass..."

                                    // 2. Extract hardware fingerprint
                                    val fingerprint = DeviceTelemetry.getDeviceFingerprint(context)

                                    // 3. Verify with the gateway, or queue the scan for later
                                    coroutineScope.launch {
                                        val result = scanRepository.submit(
                                            jwt = jwt,
                                            nonce = nonce,
                                            source = if (signed) {
                                                ScanSource.SIGNED_PASS
                                            } else {
                                                ScanSource.LEGACY_TOKEN
                                            },
                                            faceImageBase64 = faceBase64,
                                            coordinate = location,
                                            deviceFingerprint = fingerprint
                                        )

                                        withContext(Dispatchers.Main) {
                                            outcome = result
                                            statusText = result.headline
                                            isProcessing = false
                                            onOutcome(result)
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
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
