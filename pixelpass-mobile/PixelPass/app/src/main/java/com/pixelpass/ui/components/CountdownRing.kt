package com.pixelpass.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pixelpass.ui.theme.PrimaryBlue

/**
 * Ring that drains clockwise as the current pass ages, showing the holder when
 * the QR code is about to rotate.
 */
@Composable
fun CountdownRing(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = PrimaryBlue,
    trackColor: Color = Color(0xFFE2E8F0),
    strokeWidth: Dp = 6.dp
) {
    val sweep = progress.coerceIn(0f, 1f) * 360f

    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val ringSize = Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f))
        val topLeft = Offset(stroke / 2f, stroke / 2f)

        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = ringSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = ringSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}
