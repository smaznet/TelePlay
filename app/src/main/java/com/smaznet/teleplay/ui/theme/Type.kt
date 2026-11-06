package com.smaznet.teleplay.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Typography

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

/**
 * tvOS type character: heavy tight display titles, medium-weight card titles, regular body, sized for couch
 * distance (nothing is below 14sp).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
val Typography = Typography(
    displayLarge = style(64, 70, FontWeight.Bold, -1.2),
    displayMedium = style(52, 60, FontWeight.Bold, -0.8),
    displaySmall = style(44, 50, FontWeight.Bold, -0.8),
    headlineLarge = style(40, 48, FontWeight.Bold, -0.5),
    headlineMedium = style(32, 40, FontWeight.SemiBold, -0.3),
    headlineSmall = style(28, 36, FontWeight.SemiBold, -0.2),
    titleLarge = style(26, 34, FontWeight.SemiBold, -0.1),
    titleMedium = style(20, 28, FontWeight.SemiBold),
    titleSmall = style(18, 24, FontWeight.SemiBold),
    bodyLarge = style(20, 30, FontWeight.Normal),
    bodyMedium = style(18, 26, FontWeight.Normal),
    bodySmall = style(16, 22, FontWeight.Normal),
    labelLarge = style(18, 24, FontWeight.Medium),
    labelMedium = style(16, 22, FontWeight.Medium),
    labelSmall = style(14, 20, FontWeight.Medium, 0.2),
)
