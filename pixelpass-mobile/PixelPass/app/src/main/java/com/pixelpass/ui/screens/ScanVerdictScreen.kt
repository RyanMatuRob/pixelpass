package com.pixelpass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelpass.qr.PassOtp
import com.pixelpass.scan.ScanVerdict
import com.pixelpass.ui.theme.AppTypography

/** Palette and phrasing for one verdict, so it reads from across the gate. */
private data class VerdictStyle(
    val background: Color,
    val accent: Color,
    val icon: ImageVector,
    val cue: String
)

private fun styleFor(verdict: ScanVerdict): VerdictStyle = when (verdict) {
    ScanVerdict.GRANTED -> VerdictStyle(
        background = Color(0xFFDCFCE7),
        accent = Color(0xFF166534),
        icon = Icons.Default.CheckCircle,
        cue = "Let the holder through"
    )

    ScanVerdict.DENIED -> VerdictStyle(
        background = Color(0xFFFEE2E2),
        accent = Color(0xFF991B1B),
        icon = Icons.Default.Close,
        cue = "Hold the holder at the gate"
    )

    ScanVerdict.OFFLINE_APPROVAL -> VerdictStyle(
        background = Color(0xFFFEF3C7),
        accent = Color(0xFF92400E),
        icon = Icons.Default.Warning,
        cue = "Approved on this device only"
    )

    ScanVerdict.REPLAY -> VerdictStyle(
        background = Color(0xFFE0E7FF),
        accent = Color(0xFF3730A3),
        icon = Icons.Default.Refresh,
        cue = "This pass has already been used"
    )
}

/**
 * Full-screen access result. The guard reads the colour, the icon and the
 * headline in one glance, and only then reads the sentence underneath.
 *
 * [otp] is the fallback code carried by the scanned pass. When the gateway
 * refuses the scan the guard can ask the holder for it and approve this one
 * person locally; that is the only path from red to green on this screen.
 */
@Composable
fun ScanVerdictScreen(
    verdict: ScanVerdict,
    detail: String,
    queuedForSync: Boolean,
    onScanNext: () -> Unit,
    otp: String? = null
) {
    var overrideGranted by remember(verdict, detail) { mutableStateOf(false) }
    val shownVerdict = if (overrideGranted) ScanVerdict.GRANTED else verdict
    val style = styleFor(shownVerdict)
    val fallbackOtp = otp?.takeIf { !overrideGranted && verdict == ScanVerdict.DENIED }
    val shownDetail = if (overrideGranted) {
        "Approved locally with the holder's fallback code. The gateway's refusal stays on the scan queue."
    } else {
        detail
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(style.background)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The card scrolls so the fallback panel still fits on a small phone.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Surface(color = style.accent, shape = CircleShape) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = shownVerdict.headline,
                    tint = Color.White,
                    modifier = Modifier
                        .padding(28.dp)
                        .size(88.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = shownVerdict.headline,
                style = AppTypography.headlineMedium.copy(fontSize = 34.sp),
                color = style.accent,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = style.cue,
                style = AppTypography.bodyLarge,
                color = style.accent,
                textAlign = TextAlign.Center
            )

            if (shownDetail.isNotBlank()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = shownDetail,
                    style = AppTypography.bodyLarge,
                    color = Color(0xFF111827),
                    textAlign = TextAlign.Center
                )
            }

            if (queuedForSync) {
                Spacer(modifier = Modifier.height(20.dp))
                Surface(
                    color = Color.White.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "Queued for gateway sync",
                        style = AppTypography.bodyLarge,
                        color = Color(0xFF92400E),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            if (fallbackOtp != null) {
                Spacer(modifier = Modifier.height(24.dp))
                OtpOverridePanel(
                    expectedOtp = fallbackOtp,
                    onGranted = { overrideGranted = true }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onScanNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111827))
        ) {
            Text(text = "Scan next pass", style = AppTypography.bodyLarge, color = Color.White)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Asks the guard for the six-digit code printed on the holder's own pass.
 *
 * The code sits inside the signed payload, so a match proves the guard is
 * looking at the same pass the scanner read. It only flips this screen: the
 * gateway's refusal remains on the scan queue exactly as recorded.
 */
@Composable
private fun OtpOverridePanel(
    expectedOtp: String,
    onGranted: () -> Unit
) {
    var entered by remember(expectedOtp) { mutableStateOf("") }
    var error by remember(expectedOtp) { mutableStateOf<String?>(null) }

    Surface(
        color = Color.White.copy(alpha = 0.85f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Ask the holder for the fallback code on their pass.",
                style = AppTypography.bodyLarge,
                color = Color(0xFF111827),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = entered,
                onValueChange = { typed ->
                    entered = typed.filter { it.isDigit() }.take(PassOtp.LENGTH)
                    error = null
                },
                label = { Text("Six-digit code") },
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )

            error?.let { message ->
                Text(
                    text = message,
                    style = AppTypography.bodyLarge,
                    color = Color(0xFF991B1B),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (PassOtp.matches(expectedOtp, entered)) {
                        onGranted()
                    } else {
                        error = "That code does not match this pass"
                    }
                },
                enabled = entered.length == PassOtp.LENGTH,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF166534))
            ) {
                Text(text = "Approve with code", style = AppTypography.bodyLarge, color = Color.White)
            }
        }
    }
}
