package com.pixelpass.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.pixelpass.ui.theme.*
import com.pixelpass.utils.generateQrCode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrGeneratorScreen(token: String) {
    // 15 minute expiration countdown for the time-bound token[cite: 6]
    var timeLeft by remember { mutableIntStateOf(15 * 60) }
    val qrBitmap = remember(token) { generateQrCode(token) }

    LaunchedEffect(key1 = timeLeft) {
        if (timeLeft > 0) {
            delay(1000L)
            timeLeft--
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Qr Generator", style = AppTypography.headlineMedium) }, //[cite: 2]
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
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Main QR Card[cite: 2]
            Card(
                modifier = Modifier.fillMaxWidth().aspectRatio(0.85f),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("● LIVE QR PREVIEW", color = Color(0xFF10B981), style = AppTypography.labelLarge) //[cite: 2]

                    Spacer(modifier = Modifier.height(24.dp))

                    // Render the generated Matrix
                    qrBitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = "Access QR Code",
                            modifier = Modifier.size(200.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Expiry Timer[cite: 2]
                    Surface(
                        color = SurfaceLight,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        val minutes = timeLeft / 60
                        val seconds = timeLeft % 60
                        Text(
                            text = String.format("Expires in %02d:%02d", minutes, seconds), //[cite: 2]
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = PrimaryBlue
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Refresh Button[cite: 2]
            Button(
                onClick = { /* TODO: Call refresh token API */ },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Generate QR Code", color = SurfaceWhite, style = AppTypography.bodyLarge) //[cite: 2]
            }

            Text(
                "Generates a fresh encrypted authentication session", //[cite: 2]
                style = AppTypography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}