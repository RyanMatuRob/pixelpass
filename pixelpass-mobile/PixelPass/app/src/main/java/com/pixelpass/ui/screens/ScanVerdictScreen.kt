package com.pixelpass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 */
@Composable
fun ScanVerdictScreen(
    verdict: ScanVerdict,
    detail: String,
    queuedForSync: Boolean,
    onScanNext: () -> Unit
) {
    val style = styleFor(verdict)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(style.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        Surface(color = style.accent, shape = CircleShape) {
            Icon(
                imageVector = style.icon,
                contentDescription = verdict.headline,
                tint = Color.White,
                modifier = Modifier
                    .padding(28.dp)
                    .size(88.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = verdict.headline,
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

        if (detail.isNotBlank()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = detail,
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

        Spacer(modifier = Modifier.weight(1f))

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
