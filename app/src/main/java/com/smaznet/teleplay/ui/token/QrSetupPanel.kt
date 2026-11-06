package com.smaznet.teleplay.ui.token

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.smaznet.teleplay.R
import com.smaznet.teleplay.ui.common.heroPanel
import com.smaznet.teleplay.ui.theme.HeroRadiusMd
import com.smaznet.teleplay.setup.SetupError
import com.smaznet.teleplay.setup.SetupHost
import com.smaznet.teleplay.setup.SetupStatus
import com.smaznet.teleplay.setup.SetupUiState
import com.smaznet.teleplay.setup.qr.QrEncoder

private const val QUIET_ZONE_MODULES = 4

/**
 * Display-only QR panel: it owns the host's lifecycle (running only while this screen is visible) and contains
 * no focusable elements, so D-pad order on the surrounding screen is unchanged.
 */
@Composable
fun QrSetupPanel(host: SetupHost, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(host, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> host.start()
                Lifecycle.Event.ON_STOP -> host.stop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            host.stop()
        }
    }
    val state by host.uiState.collectAsState()
    QrSetupContent(state, modifier)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun QrSetupContent(state: SetupUiState, modifier: Modifier = Modifier) {
    Column(modifier.testTag("qr_panel").heroPanel().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.setup_qr_title), style = MaterialTheme.typography.titleMedium)
        if (state.status == SetupStatus.NO_NETWORK) {
            Text(
                stringResource(R.string.setup_no_network),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("setup_no_network"),
            )
            return@Column
        }
        val url = state.url
        if (url != null) {
            QrCode(url)
            Text(stringResource(R.string.setup_qr_hint_same_wifi), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.setup_address_label, url.removePrefix("http://")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("setup_address"),
            )
        }
        state.proxyAdded?.let {
            Text(stringResource(R.string.setup_proxy_added) + ": " + it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.testTag("setup_proxy_added"))
        }
        Text(statusText(state), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("setup_status"))
    }
}

/** Black modules on white, with the 4-module quiet zone scanners need, regardless of the app theme. */
@Composable
private fun QrCode(payload: String) {
    val matrix = remember(payload) { runCatching { QrEncoder.encode(payload) }.getOrNull() } ?: return
    Box(Modifier.size(240.dp).clip(RoundedCornerShape(HeroRadiusMd)).background(Color.White).clearAndSetSemantics { }.testTag("qr_code"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(240.dp).padding(0.dp)) {
            val modules = matrix.size + QUIET_ZONE_MODULES * 2
            val cell = size.minDimension / modules
            for (y in matrix.indices) for (x in matrix.indices) {
                if (matrix[y][x]) {
                    drawRect(
                        Color.Black,
                        topLeft = Offset((x + QUIET_ZONE_MODULES) * cell, (y + QUIET_ZONE_MODULES) * cell),
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun statusText(s: SetupUiState): String = when (s.status) {
    SetupStatus.IDLE, SetupStatus.WAITING -> stringResource(R.string.setup_status_waiting)
    SetupStatus.NO_NETWORK -> stringResource(R.string.setup_no_network)
    SetupStatus.PHONE_CONNECTED -> stringResource(R.string.setup_status_phone_connected)
    SetupStatus.SUBMITTING, SetupStatus.TOKEN_RECEIVED -> stringResource(R.string.setup_status_submitting)
    SetupStatus.BUSY -> stringResource(R.string.setup_status_busy)
    SetupStatus.EXPIRED_REFRESHING -> stringResource(R.string.setup_status_expired_refreshing)
    SetupStatus.REJECTED -> when (s.error) {
        SetupError.OFFLINE -> stringResource(R.string.token_error_offline)
        SetupError.RATE_LIMITED -> stringResource(R.string.setup_status_rate_limited)
        SetupError.OTHER -> stringResource(R.string.token_error_other)
        else -> stringResource(R.string.setup_status_rejected)
    }
}
