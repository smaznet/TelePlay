package com.smaznet.teleplay.ui.proxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.smaznet.teleplay.R
import com.smaznet.teleplay.ui.common.HeroButton
import com.smaznet.teleplay.ui.common.HeroChip
import com.smaznet.teleplay.ui.common.HeroIcon
import com.smaznet.teleplay.ui.common.TeleIcons
import com.smaznet.teleplay.ui.common.HeroTone
import com.smaznet.teleplay.ui.common.heroPanel
import com.smaznet.teleplay.ui.common.requestSafely

/**
 * The proxy switch at the top of Home. OK toggles it; the label always says On or Off in words, with the proxy's
 * name and its state next to it. When the proxy is on but Telegram is unreachable, OK opens a small menu instead.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProxySwitch(
    ui: ProxySwitchUi,
    onToggle: () -> Unit,
    onFailureAction: (FailureAction) -> Unit,
    showFailureMenu: Boolean,
    onDismissFailureMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        HeroButton(onClick = onToggle, contentPadding = PaddingValues(start = 20.dp, end = 28.dp, top = 14.dp, bottom = 14.dp), modifier = Modifier.testTag("proxy_switch")) {
            HeroIcon(if (ui.on) TeleIcons.ShieldCheck else TeleIcons.Shield, stringResource(R.string.proxy_switch_label))
            Spacer(Modifier.width(12.dp))
            // The state is spelled out, never only drawn.
            Text(stringResource(if (ui.on) R.string.proxy_switch_on else R.string.proxy_switch_off), style = MaterialTheme.typography.titleMedium)
        }
        when {
            !ui.on && !ui.hasProxies -> HeroChip(stringResource(R.string.proxy_switch_add), modifier = Modifier.testTag("proxy_indicator"))
            !ui.on -> HeroChip(stringResource(R.string.proxy_switch_none), modifier = Modifier.testTag("proxy_indicator"))
            else -> {
                val (text, tone) = when (ui.indicator) {
                    ProxyIndicator.CONNECTING -> R.string.proxy_state_connecting to HeroTone.Default
                    ProxyIndicator.FAILED -> R.string.proxy_state_failed to HeroTone.Warning
                    else -> R.string.proxy_state_connected to HeroTone.Primary
                }
                HeroChip(ui.name.orEmpty() + "  ·  " + stringResource(text), tone = tone, modifier = Modifier.testTag("proxy_indicator"))
            }
        }
    }
    if (showFailureMenu) FailureMenu(onFailureAction, onDismissFailureMenu)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FailureMenu(onAction: (FailureAction) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(520.dp).heroPanel().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.proxy_state_failed), style = MaterialTheme.typography.headlineSmall)
            HeroButton({ onAction(FailureAction.RETRY) }, solid = true, modifier = Modifier.fillMaxWidth().focusRequester(first).testTag("proxy_fail_retry")) { Text(stringResource(R.string.proxy_fail_retry)) }
            HeroButton({ onAction(FailureAction.CHOOSE_ANOTHER) }, modifier = Modifier.fillMaxWidth().testTag("proxy_fail_choose")) { Text(stringResource(R.string.proxy_fail_choose)) }
            HeroButton({ onAction(FailureAction.GO_DIRECT) }, modifier = Modifier.fillMaxWidth().testTag("proxy_fail_direct")) { Text(stringResource(R.string.proxy_fail_direct)) }
        }
    }
    LaunchedEffect(Unit) { first.requestSafely() }
}
