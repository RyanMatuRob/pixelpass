package com.pixelpass.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelpass.PixelPassApp
import com.pixelpass.core.PixelPassLog
import com.pixelpass.data.session.UserSession
import com.pixelpass.qr.DevicePassSigner
import com.pixelpass.qr.IssuedPass
import com.pixelpass.qr.PASS_TTL_MILLIS
import com.pixelpass.qr.PassPayloadIssuer
import com.pixelpass.ui.components.CountdownRing
import com.pixelpass.ui.components.KeepScreenBright
import com.pixelpass.ui.theme.*
import com.pixelpass.utils.generateQrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * The holder's pass.
 *
 * A fresh ECDSA-signed payload is issued every [PASS_TTL_MILLIS], so a
 * screenshot stops working within half a minute, the countdown ring shows how
 * long the visible code lives, and the screen is held at full brightness so a
 * guard's camera can read it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrGeneratorScreen(
    session: UserSession,
    onBackClick: () -> Unit = {},
    onProfileClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val issuer = remember(context) {
        val container = (context.applicationContext as PixelPassApp).container
        PassPayloadIssuer(
            signer = DevicePassSigner(container.deviceKeyStore),
            geohashSource = { container.locationGeohash() }
        )
    }

    var issuedPass by remember(session.token) { mutableStateOf<IssuedPass?>(null) }
    var signingFailure by remember(session.token) { mutableStateOf<String?>(null) }
    var refreshTicket by remember { mutableIntStateOf(0) }

    // One signed pass per rotation slot; the loop restarts on an explicit refresh
    // or when the signed-in session changes.
    LaunchedEffect(session.token, refreshTicket) {
        while (true) {
            val result = withContext(Dispatchers.Default) { runCatching { issuer.issue(session) } }
            result.onSuccess {
                issuedPass = it
                signingFailure = null
            }.onFailure {
                PixelPassLog.e(PixelPassLog.QR, "Failed to sign pass payload", it)
                signingFailure = "Could not sign a pass on this device."
            }
            delay(PASS_TTL_MILLIS)
        }
    }

    // Countdown driven by the payload's own timestamp, so it stays honest even
    // if the issuing loop stalls.
    var millisLeft by remember(issuedPass) {
        mutableLongStateOf(if (issuedPass != null) PASS_TTL_MILLIS else 0L)
    }
    LaunchedEffect(issuedPass) {
        val issuedAt = issuedPass?.issuedAtEpochMillis ?: return@LaunchedEffect
        while (true) {
            millisLeft = (issuedAt + PASS_TTL_MILLIS - System.currentTimeMillis())
                .coerceIn(0L, PASS_TTL_MILLIS)
            if (millisLeft == 0L) break
            delay(200L)
        }
    }

    // Keeps the QR readable: no dimming, no screen sleep while the pass is open.
    KeepScreenBright()

    val qrBitmap = remember(issuedPass) { issuedPass?.let { generateQrCode(it.encoded) } }
    val isLive = issuedPass != null && qrBitmap != null
    val secondsLeft = ceil(millisLeft / 1000.0).toInt()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(28.dp)
                                .background(PrimaryBlue, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "Security Shield",
                                tint = SurfaceWhite,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "Qr Generator",
                            style = AppTypography.headlineMedium.copy(fontSize = 22.sp)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = NeutralDark
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onProfileClick,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(PrimaryBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profile",
                                tint = SurfaceWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        }
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
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Main QR Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.85f),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "● LIVE QR PREVIEW",
                        color = if (isLive) Color(0xFF10B981) else Color.LightGray,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // The ring wraps the code and drains as the pass ages.
                    Box(contentAlignment = Alignment.Center) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "Access QR Code",
                                modifier = Modifier
                                    .size(196.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = "QR Placeholder",
                                tint = Color(0xFFE2E8F0),
                                modifier = Modifier.size(196.dp)
                            )
                        }

                        CountdownRing(
                            progress = millisLeft / PASS_TTL_MILLIS.toFloat(),
                            modifier = Modifier.size(230.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Rotation countdown for the visible code
                    Surface(
                        color = if (isLive) Color(0xFFEEF2FF) else SurfaceLight,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Schedule,
                                contentDescription = "Timer",
                                tint = if (isLive) PrimaryBlue else Color.Gray,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(end = 4.dp)
                            )
                            Text(
                                text = if (isLive) {
                                    String.format("Refreshes in\n00:%02d", secondsLeft)
                                } else {
                                    "Preparing secure pass"
                                },
                                color = if (isLive) PrimaryBlue else Color.Gray,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    signingFailure?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Manual rotation: the pass also refreshes itself every 30 seconds.
            Button(
                onClick = { refreshTicket++ },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = "Refresh QR Code",
                    style = AppTypography.bodyLarge.copy(color = SurfaceWhite)
                )
            }

            Text(
                text = "Signed with this device's key and rotated every 30 seconds",
                fontSize = 13.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Secured with 256-bit encryption. Privacy Policy · Terms",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 16.dp)
            )

        }
    }
}

