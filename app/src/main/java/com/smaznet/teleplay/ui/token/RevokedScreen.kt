package com.smaznet.teleplay.ui.token

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.smaznet.teleplay.R
import com.smaznet.teleplay.ui.common.HeroButton
import com.smaznet.teleplay.ui.common.HeroTone
import com.smaznet.teleplay.ui.common.heroPanel
import com.smaznet.teleplay.setup.SetupHost
import com.smaznet.teleplay.ui.common.requestSafely

/** Shown when the bot was revoked or its token stopped working. Initial focus: the action. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RevokedScreen(onEnterNewToken: () -> Unit, modifier: Modifier = Modifier, setupHost: SetupHost? = null) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestSafely() }
    Box(modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.width(640.dp).heroPanel().padding(40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.revoked_title), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.revoked_message), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HeroButton(onClick = onEnterNewToken, solid = true, tone = HeroTone.Primary, modifier = Modifier.focusRequester(focus).testTag("revoked_action")) {
                Text(stringResource(R.string.revoked_action))
            }
            if (setupHost != null) QrSetupPanel(setupHost)
        }
    }
}
