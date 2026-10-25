package com.pixelpass.ui.screens

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.pixelpass.qr.PassVerdict
import com.pixelpass.scan.ScanGate
import com.pixelpass.scan.ScanIntake
import com.pixelpass.ui.components.CameraPreview
import com.pixelpass.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ScannerStandbyScreen(
    onQrScanned: (ScanIntake.Accepted) -> Unit,
    onBackClick: () -> Unit = {},
    onReplayDetected: (String) -> Unit = {},
    intake: suspend (String) -> ScanIntake = { ScanGate.intake(it) }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    // Hardware toggle states
    var isTorchOn by remember { mutableStateOf(false) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }

    // Reason shown over the viewfinder when a code is refused before face capture.
    var rejection by remember { mutableStateOf<String?>(null) }

    if (!permissionsState.allPermissionsGranted) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Qr Scanner", style = AppTypography.headlineMedium) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = NeutralDark
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceLight)
                )
            },
            containerColor = SurfaceLight
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Camera and Location access required for security audit.",
                    style = AppTypography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { permissionsState.launchMultiplePermissionRequest() },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text("Grant Permissions", color = SurfaceWhite)
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Qr Scanner", style = AppTypography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = NeutralDark
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceLight)
            )
        },
        containerColor = SurfaceLight
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
        ) {
            // Gate Status Indicators
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(color = Color(0xFFFEE2E2), shape = RoundedCornerShape(16.dp)) {
                    Text("● GATE 1 • MAIN PORTAL", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color(0xFF991B1B), style = AppTypography.labelLarge)
                }
                Surface(color = Color(0xFFE0E7FF), shape = RoundedCornerShape(16.dp)) {
                    Text("✔ Active Guard Duty", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = PrimaryBlue, style = AppTypography.labelLarge)
                }
            }

            // Camera Viewfinder Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black)
            ) {
                // Pass the hardware states down to the Camera component
                CameraPreview(
                    lensFacing = lensFacing,
                    isTorchOn = isTorchOn,
                    onQrScanned = { raw ->
                        // Local checks run first: nothing reaches face capture or the
                        // gateway unless the pass itself is acceptable. A replayed
                        // pass leaves the viewfinder and gets its own full screen.
                        scope.launch {
                            when (val result = intake(raw)) {
                                is ScanIntake.Accepted -> {
                                    rejection = null
                                    onQrScanned(result)
                                }

                                is ScanIntake.Rejected -> if (result.verdict == PassVerdict.REPLAY) {
                                    rejection = null
                                    onReplayDetected(result.reason)
                                } else {
                                    rejection = result.reason
                                }
                            }
                        }
                    }
                )

                // Dark overlay to simulate scanning depth
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))

                // UI Overlay for Target Brackets
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val bracketLength = 60f
                    val strokeWidth = 12f
                    val padding = 100f

                    // Top Left
                    drawLine(PrimaryBlue, Offset(padding, padding), Offset(padding + bracketLength, padding), strokeWidth)
                    drawLine(PrimaryBlue, Offset(padding, padding), Offset(padding, padding + bracketLength), strokeWidth)

                    // Top Right
                    drawLine(PrimaryBlue, Offset(size.width - padding, padding), Offset(size.width - padding - bracketLength, padding), strokeWidth)
                    drawLine(PrimaryBlue, Offset(size.width - padding, padding), Offset(size.width - padding, padding + bracketLength), strokeWidth)

                    // Bottom Left
                    drawLine(PrimaryBlue, Offset(padding, size.height - padding), Offset(padding + bracketLength, size.height - padding), strokeWidth)
                    drawLine(PrimaryBlue, Offset(padding, size.height - padding), Offset(padding, size.height - padding - bracketLength), strokeWidth)

                    // Bottom Right
                    drawLine(PrimaryBlue, Offset(size.width - padding, size.height - padding), Offset(size.width - padding - bracketLength, size.height - padding), strokeWidth)
                    drawLine(PrimaryBlue, Offset(size.width - padding, size.height - padding), Offset(size.width - padding, size.height - padding - bracketLength), strokeWidth)
                }

                // Inner UI Elements
                Text(
                    "⚯ Quick Scan",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                )

                Text(
                    text = rejection ?: "Align pass within corner brackets",
                    color = if (rejection == null) Color.White else Color(0xFFFCA5A5),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hardware Toggles
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { isTorchOn = !isTorchOn },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTorchOn) PrimaryBlue else SurfaceWhite,
                        contentColor = if (isTorchOn) SurfaceWhite else PrimaryBlue
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isTorchOn) "Torch Off" else "Torch Light", style = AppTypography.bodyLarge)
                }
                Button(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceWhite, contentColor = PrimaryBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Switch Lens", style = AppTypography.bodyLarge)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Fallback PIN Entry Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("● Scanner Standby", color = PrimaryBlue, style = AppTypography.labelLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Badge damaged or faded?", style = AppTypography.bodyLarge)
                            Text("Key in 8-digit access credential", color = Color.Gray, style = AppTypography.bodySmall)
                        }
                        Button(
                            onClick = { /* TODO: Open PIN Dialog */ },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Enter PIN", color = SurfaceWhite)
                        }
                    }
                }
            }
        }
    }
}
