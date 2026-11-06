package com.smaznet.teleplay.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.smaznet.teleplay.ui.theme.HeroContent2
import com.smaznet.teleplay.ui.theme.HeroContent3
import com.smaznet.teleplay.ui.theme.HeroDanger
import com.smaznet.teleplay.ui.theme.HeroDangerSoft
import com.smaznet.teleplay.ui.theme.HeroDivider
import com.smaznet.teleplay.ui.theme.HeroForeground
import com.smaznet.teleplay.ui.theme.HeroGlass
import com.smaznet.teleplay.ui.theme.HeroForegroundMuted
import com.smaznet.teleplay.ui.theme.HeroOnPrimary
import com.smaznet.teleplay.ui.theme.HeroPrimary
import com.smaznet.teleplay.ui.theme.HeroRadiusMd
import com.smaznet.teleplay.ui.theme.HeroRadiusXl
import com.smaznet.teleplay.ui.theme.HeroWarning

/** Colour intents. */
enum class HeroTone { Default, Primary, Danger, Warning }

/**
 * tvOS capsule button. Resting it is translucent glass; focused it turns solid white with black text and lifts slightly,
 * which is the whole focus language. [solid] is the one main action on a screen: white at rest, so it reads first.
 * Danger tones stay tinted and turn solid red when focused.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HeroButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: HeroTone = HeroTone.Default,
    solid: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val (rest, restText, focused, focusedText) = when {
        solid && tone == HeroTone.Danger -> ButtonPalette(HeroDanger, HeroOnPrimary, HeroDangerSoft, HeroOnPrimary)
        solid -> ButtonPalette(HeroPrimary, HeroOnPrimary, HeroPrimary, HeroOnPrimary)
        tone == HeroTone.Danger -> ButtonPalette(HeroDanger.copy(alpha = 0.2f), HeroDangerSoft, HeroDanger, HeroOnPrimary)
        tone == HeroTone.Warning -> ButtonPalette(HeroWarning.copy(alpha = 0.2f), HeroWarning, HeroWarning, HeroOnPrimary)
        else -> ButtonPalette(HeroGlass, HeroForeground, HeroPrimary, HeroOnPrimary)
    }
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        scale = ButtonDefaults.scale(focusedScale = 1.08f),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = rest,
            contentColor = restText,
            focusedContainerColor = focused,
            focusedContentColor = focusedText,
            pressedContainerColor = focused,
            pressedContentColor = focusedText,
            disabledContainerColor = HeroContent2,
            disabledContentColor = HeroForegroundMuted,
        ),
        // A solid button is white at rest, so a white fill alone cannot show focus: it gets a ring too.
        border = ButtonDefaults.border(
            border = Border.None,
            focusedBorder = if (solid) Border(BorderStroke(3.dp, HeroForeground.copy(alpha = 0.6f)), shape = shape) else Border.None,
        ),
        contentPadding = contentPadding,
        content = content,
    )
}

private data class ButtonPalette(val container: Color, val content: Color, val focusedContainer: Color, val focusedContent: Color)

/** Chip: a small pill with a tinted fill, used for status and counters. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HeroChip(text: String, modifier: Modifier = Modifier, tone: HeroTone = HeroTone.Default) {
    val (fill, ink) = when (tone) {
        HeroTone.Primary -> HeroGlass to HeroForeground
        HeroTone.Danger -> HeroDanger.copy(alpha = 0.2f) to HeroDangerSoft
        HeroTone.Warning -> HeroWarning.copy(alpha = 0.2f) to HeroWarning
        HeroTone.Default -> HeroContent3 to HeroForeground
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = ink,
        modifier = modifier.background(fill, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** Progress bar: a fully rounded track with a white indicator. */
@Composable
fun HeroProgress(fraction: Float, modifier: Modifier = Modifier, height: Int = 8, track: Color = HeroContent3, indicator: Color = HeroPrimary) {
    val shape = RoundedCornerShape(50)
    Box(modifier.fillMaxWidth().height(height.dp).background(track, shape)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(indicator, shape))
    }
}

/** Panel surface: content1 fill, hairline divider border, extra-large radius. Not focusable. */
fun Modifier.heroPanel(radius: androidx.compose.ui.unit.Dp = HeroRadiusXl): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.background(com.smaznet.teleplay.ui.theme.HeroContent1, shape).border(1.dp, HeroDivider, shape)
}

/** Padding for a button that holds only an icon: close to a circle at the capsule's height. */
val IconButtonPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)

/** A 32 dp icon tinted with the button's current content colour, so it inverts with the focus state. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HeroIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String?, modifier: Modifier = Modifier) {
    androidx.tv.material3.Icon(icon, contentDescription = description, modifier = modifier.size(32.dp))
}
