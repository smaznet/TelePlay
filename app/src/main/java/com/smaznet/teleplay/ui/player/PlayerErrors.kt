package com.smaznet.teleplay.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.smaznet.teleplay.R
import com.smaznet.teleplay.ui.common.HeroButton
import com.smaznet.teleplay.ui.common.HeroTone
import com.smaznet.teleplay.playback.PlayerError
import kotlinx.coroutines.delay

/** Plain-language error panel. Focus starts on Retry (or Back when retry is not possible). */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerErrorPanel(error: PlayerError, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val spec = error.toSpec()
    var remaining by remember(error) { mutableIntStateOf(spec.countdownSec ?: 0) }
    LaunchedEffect(error) {
        if (spec.countdownSec != null) {
            while (remaining > 0) { delay(1000); remaining-- }
            onRetry()
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(error) { runCatching { focus.requestFocus() } }
    Column(
        modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(spec.titleRes), style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center)
        Text(
            if (spec.countdownSec != null) stringResource(spec.bodyRes, remaining) else stringResource(spec.bodyRes),
            style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp).widthIn(max = 560.dp),
        )
        Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (spec.canRetry) {
                HeroButton(onClick = onRetry, solid = true, tone = HeroTone.Primary, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.player_retry)) }
                HeroButton(onClick = onBack) { Text(stringResource(R.string.player_back_to_library)) }
            } else {
                HeroButton(onClick = onBack, solid = true, tone = HeroTone.Primary, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.player_back_to_library)) }
            }
        }
    }
}
