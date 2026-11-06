package com.smaznet.teleplay.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.darkColorScheme

/** Radii follow the tvOS rounded-rectangle steps; controls are capsules. */
val HeroRadiusSm = 8.dp
val HeroRadiusMd = 14.dp
val HeroRadiusLg = 20.dp
val HeroRadiusXl = 28.dp

/**
 * tvOS-style look on Android TV. Dark only: the app is watched on a TV in a dim room, and many TVs report the system
 * as light, which would flip the whole app to a bright scheme.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TelePlayTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = HeroPrimary,
        onPrimary = HeroOnPrimary,
        primaryContainer = HeroContent2,
        onPrimaryContainer = HeroForeground,
        inverseSurface = HeroForeground,
        inverseOnSurface = HeroOnPrimary,
        secondary = HeroPrimarySoft,
        onSecondary = HeroOnPrimary,
        background = HeroBackground,
        onBackground = HeroForeground,
        surface = HeroContent1,
        onSurface = HeroForeground,
        surfaceVariant = HeroContent2,
        onSurfaceVariant = HeroForegroundMuted,
        error = HeroDanger,
        onError = HeroOnPrimary,
        errorContainer = HeroContent2,
        onErrorContainer = HeroDangerSoft,
        border = HeroForeground,
        borderVariant = HeroDivider,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(HeroRadiusSm),
            small = RoundedCornerShape(HeroRadiusMd),
            medium = RoundedCornerShape(HeroRadiusLg),
            large = RoundedCornerShape(HeroRadiusXl),
            extraLarge = RoundedCornerShape(HeroRadiusXl),
        ),
        typography = Typography,
        content = content,
    )
}
