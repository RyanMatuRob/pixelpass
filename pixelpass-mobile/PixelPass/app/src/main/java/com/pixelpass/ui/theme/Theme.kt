package com.pixelpass.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pixelpass.R

val PlusJakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold)
) //[cite: 1]

val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        color = NeutralDark
    ), //[cite: 1]
    bodyLarge = TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        color = NeutralDark
    ) //[cite: 1]
)

private val PixelPassColors = lightColorScheme(
    primary = PrimaryBlue,
    secondary = SecondaryOrange,
    tertiary = TertiaryBlue,
    background = SurfaceLight,
    surface = SurfaceWhite,
    onPrimary = SurfaceWhite,
    onBackground = NeutralDark,
    onSurface = NeutralDark
)

/** Design-system entry point; every screen is drawn inside this theme. */
@Composable
fun PixelPassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PixelPassColors,
        typography = AppTypography,
        content = content
    )
}