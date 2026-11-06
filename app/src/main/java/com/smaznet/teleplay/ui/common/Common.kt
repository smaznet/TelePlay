package com.smaznet.teleplay.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.smaznet.teleplay.R
import com.smaznet.teleplay.ui.theme.HeroDivider
import com.smaznet.teleplay.ui.theme.HeroRadiusLg

/** Requests focus once the target is attached; ignores the failure when it is not (yet) in the tree. */
fun FocusRequester.requestSafely() {
    try { requestFocus() } catch (_: IllegalStateException) { }
}

/** Pressable card border: a hairline divider at rest, the shared thick light focus ring when focused. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun focusedBorder(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(HeroRadiusLg)) = ClickableSurfaceDefaults.border(
    border = Border(BorderStroke(1.dp, HeroDivider), shape = shape),
    focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.border), shape = shape),
)

@OptIn(ExperimentalTvMaterial3Api::class)
/** Confirmation dialog; focus starts on Cancel so an accidental click never destroys data. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(560.dp)
                .heroPanel()
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HeroButton(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(cancelFocus).testTag("dialog_cancel"),
                ) { Text(stringResource(R.string.common_cancel)) }
                HeroButton(onClick = onConfirm, tone = HeroTone.Danger, solid = true, modifier = Modifier.testTag("dialog_confirm")) { Text(confirmLabel) }
            }
        }
    }
    LaunchedEffect(Unit) { cancelFocus.requestSafely() }
}
