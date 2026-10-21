package com.pixelpass.ui.screens

import android.Manifest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.pixelpass.ui.components.CameraPreview
import com.pixelpass.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ScannerStandbyScreen() {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    if (!cameraPermissionState.status.isGranted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Camera access is required for scanning.", style = AppTypography.bodyLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                Text("Grant Permission")
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Qr Scanner", style = AppTypography.headlineMedium) }, //[cite: 4]
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
            // Gate Status Indicators[cite: 4]
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(color = Color(0xFFFEE2E2), shape = RoundedCornerShape(16.dp)) {
                    Text("● GATE 1 • MAIN PORTAL", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color(0xFF991B1B), style = AppTypography.labelLarge) //[cite: 4]
                }
                Surface(color = Color(0xFFE0E7FF), shape = RoundedCornerShape(16.dp)) {
                    Text("✔ Active Guard Duty", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = PrimaryBlue, style = AppTypography.labelLarge) //[cite: 4]
                }
            }

            // Camera Viewfinder Box[cite: 4]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black)
            ) {
                CameraPreview()

                // Dark overlay to simulate scanning depth
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))

                // UI Overlay for Target Brackets[cite: 4]
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

                // Inner UI Elements[cite: 4]
                Text(
                    "⚯ Ultra-Sense AI",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                ) //[cite: 4]

                Text(
                    "Align pass within corner brackets",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                ) //[cite: 4]
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hardware Toggles[cite: 4]
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { /* TODO: Toggle Torch */ },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceWhite, contentColor = PrimaryBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Torch Light", style = AppTypography.bodyLarge) //[cite: 4]
                }
                Button(
                    onClick = { /* TODO: Toggle Lens */ },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceWhite, contentColor = PrimaryBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Switch Lens", style = AppTypography.bodyLarge) //[cite: 4]
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Fallback PIN Entry Card[cite: 4]
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("● Scanner Standby", color = PrimaryBlue, style = AppTypography.labelLarge) //[cite: 4]
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Badge damaged or faded?", style = AppTypography.bodyLarge) //[cite: 4]
                            Text("Key in 8-digit access credential", color = Color.Gray, style = AppTypography.bodySmall) //[cite: 4]
                        }
                        Button(
                            onClick = { /* TODO: Open PIN Dialog */ },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Enter PIN", color = SurfaceWhite) //[cite: 4]
                        }
                    }
                }
            }
        }
    }
}