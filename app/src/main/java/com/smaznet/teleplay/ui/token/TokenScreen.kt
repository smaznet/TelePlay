package com.smaznet.teleplay.ui.token

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.smaznet.teleplay.R
import com.smaznet.teleplay.ui.common.HeroButton
import com.smaznet.teleplay.ui.common.HeroTone
import com.smaznet.teleplay.ui.common.heroPanel
import com.smaznet.teleplay.ui.theme.HeroDangerSoft
import com.smaznet.teleplay.ui.theme.HeroDivider
import com.smaznet.teleplay.ui.theme.HeroRadiusMd
import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.setup.SetupHost
import com.smaznet.teleplay.ui.common.requestSafely
import kotlinx.coroutines.launch

/** Token entry. Initial focus: the token field. The token is masked and never logged. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TokenScreen(
    onSubmit: suspend (String) -> Result<BotInfo>,
    modifier: Modifier = Modifier,
    setupHost: SetupHost? = null,
    onAddProxy: (() -> Unit)? = null,
) {
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<TokenError?>(null) }
    var fieldFocused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        val t = token.trim()
        if (t.isEmpty()) { error = TokenError.Empty; return }
        busy = true
        error = null
        scope.launch {
            val result = onSubmit(t)
            busy = false
            result.onFailure { error = tokenErrorFor(it) }
        }
    }

    LaunchedEffect(Unit) { fieldFocus.requestSafely() }

    Box(modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            (if (setupHost == null) Modifier.width(640.dp) else Modifier.widthIn(max = 560.dp).weight(1f, fill = false))
                .heroPanel().padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.token_title), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.token_instructions), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val borderColor = if (fieldFocused) MaterialTheme.colorScheme.border else HeroDivider
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(HeroRadiusMd))
                    .border(BorderStroke(if (fieldFocused) 3.dp else 1.dp, borderColor), RoundedCornerShape(HeroRadiusMd))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                if (token.isEmpty()) {
                    Text(stringResource(R.string.token_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = token,
                    onValueChange = { token = it; error = null },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        .testTag("token_field"),
                )
            }
            error?.let {
                Text(
                    tokenErrorText(it),
                    color = HeroDangerSoft,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("token_error"),
                )
            }
            if (error == TokenError.Offline) {
                Text(stringResource(R.string.token_error_offline_proxy_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HeroButton(onClick = ::submit, enabled = !busy, solid = true, tone = HeroTone.Primary, modifier = Modifier.testTag("connect_button")) {
                Text(stringResource(if (busy) R.string.token_connecting else R.string.token_connect))
            }
            if (onAddProxy != null) {
                HeroButton(onClick = onAddProxy, modifier = Modifier.testTag("add_proxy_button")) {
                    Text(stringResource(R.string.token_add_proxy))
                }
            }
        }
        if (setupHost != null) QrSetupPanel(setupHost, Modifier.width(320.dp))
        }
    }
}

@Composable
private fun tokenErrorText(e: TokenError): String = when (e) {
    TokenError.Empty -> stringResource(R.string.token_error_empty)
    TokenError.Invalid -> stringResource(R.string.token_error_invalid)
    TokenError.Offline -> stringResource(R.string.token_error_offline)
    TokenError.Proxy -> stringResource(R.string.token_error_proxy)
    TokenError.Busy -> stringResource(R.string.token_error_busy)
    is TokenError.RateLimited -> stringResource(R.string.token_error_rate_limited, e.seconds)
    TokenError.Other -> stringResource(R.string.token_error_other)
}
