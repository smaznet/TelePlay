package com.smaznet.teleplay.ui.pairing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import com.smaznet.teleplay.R
import com.smaznet.teleplay.ui.common.HeroButton
import com.smaznet.teleplay.ui.common.HeroChip
import com.smaznet.teleplay.ui.common.HeroTone
import com.smaznet.teleplay.ui.common.heroPanel
import com.smaznet.teleplay.ui.theme.HeroRadiusLg
import com.smaznet.teleplay.domain.PairingChallenge
import com.smaznet.teleplay.ui.common.ConfirmDialog
import com.smaznet.teleplay.ui.common.requestSafely
import com.smaznet.teleplay.ui.library.formatCountdown
import kotlinx.coroutines.delay

/**
 * Pairing. Initial focus: Regenerate. Back asks for confirmation, then [onLeave] (Token entry).
 * [challenge] is null while the code is being prepared.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PairingScreen(
    challenge: PairingChallenge?,
    botName: String?,
    onRegenerate: () -> Unit,
    onDisconnect: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
) {
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var remaining by remember { mutableLongStateOf(0L) }
    val regenFocus = remember { FocusRequester() }

    LaunchedEffect(challenge) {
        val c = challenge ?: return@LaunchedEffect
        while (true) {
            remaining = (c.expiresAt - now()).coerceAtLeast(0)
            if (remaining == 0L) break
            delay(1000)
        }
    }
    LaunchedEffect(Unit) { regenFocus.requestSafely() }
    BackHandler(enabled = !confirmLeave && !confirmDisconnect) { confirmLeave = true }

    Box(modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 720.dp).heroPanel().padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.pairing_instructions), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            if (botName != null) {
                Text(
                    stringResource(R.string.pairing_bot, botName),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (challenge == null) {
                Text(stringResource(R.string.pairing_preparing), style = MaterialTheme.typography.displaySmall)
            } else {
                // The code sits in an inset well (HeroUI "code" block) so it reads as the one thing to copy.
                Text(
                    challenge.code,
                    fontSize = 80.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(HeroRadiusLg))
                        .padding(horizontal = 32.dp, vertical = 16.dp)
                        .testTag("pairing_code"),
                )
                if (remaining > 0) {
                    HeroChip(stringResource(R.string.pairing_expires_in, formatCountdown(remaining)), tone = HeroTone.Primary)
                } else {
                    HeroChip(stringResource(R.string.pairing_expired), tone = HeroTone.Danger)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HeroButton(onClick = onRegenerate, solid = true, tone = HeroTone.Primary, modifier = Modifier.focusRequester(regenFocus).testTag("regenerate_button")) {
                    Text(stringResource(R.string.pairing_regenerate))
                }
                HeroButton(onClick = { confirmDisconnect = true }, tone = HeroTone.Danger, modifier = Modifier.testTag("pairing_disconnect")) {
                    Text(stringResource(R.string.pairing_disconnect))
                }
            }
        }
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = stringResource(R.string.pairing_leave_title),
            message = stringResource(R.string.pairing_leave_message),
            confirmLabel = stringResource(R.string.pairing_leave_confirm),
            onConfirm = { confirmLeave = false; onLeave() },
            onDismiss = { confirmLeave = false },
        )
    }
    if (confirmDisconnect) {
        ConfirmDialog(
            title = stringResource(R.string.settings_disconnect_title),
            message = stringResource(R.string.settings_disconnect_message),
            confirmLabel = stringResource(R.string.settings_disconnect_confirm),
            onConfirm = { confirmDisconnect = false; onDisconnect() },
            onDismiss = { confirmDisconnect = false },
        )
    }
}
