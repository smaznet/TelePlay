package com.smaznet.teleplay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.smaznet.teleplay.R
import com.smaznet.teleplay.ui.common.HeroButton
import com.smaznet.teleplay.ui.common.HeroChip
import com.smaznet.teleplay.ui.common.HeroProgress
import com.smaznet.teleplay.ui.common.HeroTone
import com.smaznet.teleplay.ui.theme.HeroContent1
import com.smaznet.teleplay.ui.theme.HeroContent2
import com.smaznet.teleplay.ui.theme.HeroRadiusLg
import com.smaznet.teleplay.domain.CacheStats
import com.smaznet.teleplay.ui.common.ConfirmDialog
import com.smaznet.teleplay.ui.common.focusedBorder
import com.smaznet.teleplay.ui.common.requestSafely
import com.smaznet.teleplay.ui.library.formatSize

/**
 * Settings. Initial focus: the cache usage row. Reset pairing and Disconnect ask for confirmation.
 * Back is handled by the caller (returns to Library).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    cacheStats: CacheStats?,
    cacheClearedNotice: Boolean,
    onClearCache: () -> Unit,
    onResetPairing: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    notifyAdded: Boolean = true,
    onNotifyAddedChange: (Boolean) -> Unit = {},
    liveRemote: Boolean = true,
    onLiveRemoteChange: (Boolean) -> Unit = {},
    updatesAvailable: Boolean = true,
    updateEnabled: Boolean = true,
    onCheckUpdate: () -> Unit = {},
    installedVersionName: String? = null,
    proxyCount: Int = 0,
    proxyInUse: String? = null,
    onOpenProxies: () -> Unit = {},
) {
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val cacheFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cacheFocus.requestSafely() }

    // The padding sits inside the scroll container so focused rows (scaled, bordered) are never clipped at the edges;
    // D-pad focus brings the focused row into view.
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(48.dp).width(640.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineLarge)
            Surface(
                onClick = {},
                modifier = Modifier.fillMaxWidth().focusRequester(cacheFocus).testTag("cache_row"),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(HeroRadiusLg)),
                border = focusedBorder(),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = HeroContent1,
                    focusedContainerColor = HeroContent1,
                    pressedContainerColor = HeroContent2,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        if (cacheStats == null) stringResource(R.string.settings_cache_unknown)
                        else stringResource(R.string.settings_cache_usage, formatSize(cacheStats.usedBytes), formatSize(cacheStats.limitBytes)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (cacheStats != null && cacheStats.limitBytes > 0) {
                        HeroProgress((cacheStats.usedBytes.toFloat() / cacheStats.limitBytes).coerceIn(0f, 1f))
                    }
                }
            }
            HeroButton(onClick = onClearCache, modifier = Modifier.fillMaxWidth().testTag("clear_cache")) {
                Text(stringResource(R.string.settings_clear_cache))
            }
            if (cacheClearedNotice) HeroChip(stringResource(R.string.settings_cache_cleared), tone = HeroTone.Primary)
            if (updatesAvailable) {
                HeroButton(onClick = onCheckUpdate, enabled = updateEnabled, modifier = Modifier.fillMaxWidth().testTag("check_update")) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.settings_update_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(if (updateEnabled) R.string.settings_update_desc else R.string.settings_update_need_bot),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (installedVersionName != null) {
                            Text(stringResource(R.string.settings_update_version, installedVersionName), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else if (installedVersionName != null) {
                Text(stringResource(R.string.settings_update_version, installedVersionName), style = MaterialTheme.typography.bodySmall)
            }
            HeroButton(onClick = onOpenProxies, modifier = Modifier.fillMaxWidth().testTag("open_proxies")) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.proxy_settings_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            proxyInUse != null -> stringResource(R.string.proxy_settings_desc_on, proxyInUse)
                            proxyCount > 0 -> stringResource(R.string.proxy_settings_desc_off, proxyCount)
                            else -> stringResource(R.string.proxy_settings_desc_none)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            ToggleRow(
                title = stringResource(R.string.settings_notify_added_title),
                description = stringResource(R.string.settings_notify_added_desc),
                on = notifyAdded, onToggle = { onNotifyAddedChange(!notifyAdded) }, tag = "toggle_notify_added",
            )
            ToggleRow(
                title = stringResource(R.string.settings_live_remote_title),
                description = stringResource(R.string.settings_live_remote_desc),
                on = liveRemote, onToggle = { onLiveRemoteChange(!liveRemote) }, tag = "toggle_live_remote",
            )
            HeroButton(onClick = { confirmReset = true }, tone = HeroTone.Warning, modifier = Modifier.fillMaxWidth().testTag("reset_pairing")) {
                Text(stringResource(R.string.settings_reset_pairing))
            }
            HeroButton(onClick = { confirmDisconnect = true }, tone = HeroTone.Danger, modifier = Modifier.fillMaxWidth().testTag("disconnect")) {
                Text(stringResource(R.string.settings_disconnect))
            }
        }
    }
    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.settings_reset_title),
            message = stringResource(R.string.settings_reset_message),
            confirmLabel = stringResource(R.string.settings_reset_confirm),
            onConfirm = { confirmReset = false; onResetPairing() },
            onDismiss = { confirmReset = false },
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

/** A focusable on/off row: select with D-pad center/Enter. The state is shown in text, not only by colour. */
@Composable
private fun ToggleRow(title: String, description: String, on: Boolean, onToggle: () -> Unit, tag: String) {
    HeroButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(if (on) R.string.settings_toggle_on else R.string.settings_toggle_off), style = MaterialTheme.typography.titleMedium)
    }
}
