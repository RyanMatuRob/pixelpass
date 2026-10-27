package com.pixelpass.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import java.util.Locale
import kotlin.math.ceil

/**
 * The holder's pass.
 *
 * A signed payload is minted only when the holder asks for one, and the
 * countdown ring shows how long the visible code stays valid. The screen is
 * held at full brightness so a guard's camera can read it.
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
    var isSigning by remember(session.token) { mutableStateOf(false) }
    var generateTicket by remember(session.token) { mutableIntStateOf(0) }

    // Nothing is minted until the holder taps the button: one pass is valid for
    // the whole walk from the queue to the turnstile, so the code never changes
    // underneath the guard's camera.
    LaunchedEffect(session.token, generateTicket) {
        if (generateTicket == 0) return@LaunchedEffect
        isSigning = true
        val result = withContext(Dispatchers.Default) { runCatching { issuer.issue(session) } }
        result.onSuccess {
            issuedPass = it
            signingFailure = null
        }.onFailure {
            PixelPassLog.e(PixelPassLog.QR, "Failed to sign pass payload", it)
            issuedPass = null
            signingFailure = "Could not sign a pass on this device."
        }
        isSigning = false
    }

    // Countdown driven by the payload's own timestamp, so it stays honest even
    // after the app has been in the background.
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
    val isLive = issuedPass != null && qrBitmap != null && millisLeft > 0L
    val isExpired = issuedPass != null && qrBitmap != null && millisLeft == 0L
    val secondsLeft = ceil(millisLeft / 1000.0).toInt()
    val ttlMinutes = PASS_TTL_MILLIS / 60_000

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
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Only the card scrolls: on a short screen the button below must stay
            // reachable without hunting for it.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 24.dp)
            ) {
                // Main QR Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = when {
                                isLive -> "● LIVE QR PREVIEW"
                                isExpired -> "● PASS EXPIRED"
                                else -> "● NO ACTIVE PASS"
                            },
                            color = when {
                                isLive -> Color(0xFF10B981)
                                isExpired -> Color(0xFFEF4444)
                                else -> Color.LightGray
                            },
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
                                        .size(176.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.QrCode,
                                    contentDescription = "QR Placeholder",
                                    tint = Color(0xFFE2E8F0),
                                    modifier = Modifier.size(176.dp)
                                )
                            }

                            CountdownRing(
                                progress = millisLeft / PASS_TTL_MILLIS.toFloat(),
                                modifier = Modifier.size(210.dp)
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
                                    text = when {
                                        isLive -> String.format(
                                            Locale.ROOT,
                                            "Valid for\n%02d:%02d",
                                            secondsLeft / 60,
                                            secondsLeft % 60
                                        )

                                        isExpired -> "Pass expired\nGenerate a new one"
                                        else -> "No pass yet\nGenerate one below"
                                    },
                                    color = if (isLive) PrimaryBlue else Color.Gray,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        // The code the holder reads out when the guard's face check fails.
                        issuedPass?.otp?.let { otp ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .background(SurfaceLight, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Fallback code",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(end = 10.dp)
                                )
                                Text(
                                    text = otp.chunked(3).joinToString(" "),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp,
                                    color = NeutralDark
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
            }

            // The pass is minted on demand so the code never changes while the
            // holder is walking to the gate. It stays pinned under the scrolling
            // card so it cannot fall off a short screen.
            Button(
                onClick = { generateTicket++ },
                enabled = !isSigning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isSigning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = SurfaceWhite,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = if (issuedPass == null) Icons.Default.QrCode else Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text(
                    text = when {
                        isSigning -> "Generating..."
                        issuedPass == null -> "Generate Pass"
                        else -> "Generate New Pass"
                    },
                    style = AppTypography.bodyLarge.copy(color = SurfaceWhite)
                )
            }

            Text(
                text = "Signed with this device's key and valid for $ttlMinutes minutes",
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

