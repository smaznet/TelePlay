package com.smaznet.teleplay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.smaznet.teleplay.R
import com.smaznet.teleplay.domain.update.UpdateError
import com.smaznet.teleplay.domain.update.UpdateState
import com.smaznet.teleplay.ui.common.HeroButton
import com.smaznet.teleplay.ui.common.HeroProgress
import com.smaznet.teleplay.ui.common.HeroTone
import com.smaznet.teleplay.ui.common.heroPanel
import com.smaznet.teleplay.ui.common.requestSafely
import com.smaznet.teleplay.ui.library.formatSize

/**
 * The update prompt for every non-idle [UpdateState]. The primary button holds initial focus; Back calls
 * [onDismiss], which cancels any active check or download. [installBlocked] means the system does not yet
 * allow TelePlay to install apps, so the ready state explains it and offers "Open settings".
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdateDialog(
    state: UpdateState,
    installBlocked: Boolean,
    onUpdate: () -> Unit,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember(state::class, installBlocked) { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(560.dp).heroPanel().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(message(state, installBlocked), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("update_message"))
            if (state is UpdateState.Downloading) {
                HeroProgress(if (state.total > 0) state.done.toFloat() / state.total else 0f)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                when (state) {
                    UpdateState.Idle, UpdateState.Verifying -> Unit
                    UpdateState.Checking, is UpdateState.Downloading ->
                        Primary(R.string.update_btn_cancel, focus, onDismiss)
                    is UpdateState.UpToDate -> Primary(R.string.update_btn_ok, focus, onDismiss)
                    is UpdateState.Available -> {
                        Primary(R.string.update_btn_update, focus, onUpdate)
                        Secondary(R.string.update_btn_not_now, onDismiss)
                    }
                    is UpdateState.ReadyToInstall ->
                        if (installBlocked) {
                            Primary(R.string.update_btn_open_settings, focus, onOpenSettings)
                            Secondary(R.string.update_btn_install, onInstall)
                        } else {
                            Primary(R.string.update_btn_install, focus, onInstall)
                            Secondary(R.string.update_btn_not_now, onDismiss)
                        }
                    is UpdateState.Failed ->
                        if (state.failure.error == UpdateError.NotConnected) {
                            Primary(R.string.update_btn_ok, focus, onDismiss)
                        } else {
                            Primary(R.string.update_btn_retry, focus, onRetry)
                            Secondary(R.string.update_btn_ok, onDismiss)
                        }
                }
            }
        }
    }
    LaunchedEffect(state::class, installBlocked) { focus.requestSafely() }
}

@Composable
private fun Primary(label: Int, focus: FocusRequester, onClick: () -> Unit) {
    HeroButton(onClick = onClick, solid = true, modifier = Modifier.focusRequester(focus).testTag("update_primary")) { Text(stringResource(label)) }
}

@Composable
private fun Secondary(label: Int, onClick: () -> Unit) {
    HeroButton(onClick = onClick, modifier = Modifier.testTag("update_secondary")) { Text(stringResource(label)) }
}

@Composable
private fun message(state: UpdateState, installBlocked: Boolean): String = when (state) {
    UpdateState.Idle -> ""
    UpdateState.Checking -> stringResource(R.string.update_checking)
    is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date, state.versionCode)
    is UpdateState.Available -> stringResource(R.string.update_available, state.announcement.versionCode)
    is UpdateState.Downloading -> stringResource(R.string.update_downloading, formatSize(state.done), formatSize(state.total))
    UpdateState.Verifying -> stringResource(R.string.update_verifying)
    is UpdateState.ReadyToInstall -> stringResource(if (installBlocked) R.string.update_err_install_blocked else R.string.update_ready)
    is UpdateState.Failed -> when (state.failure.error) {
        UpdateError.Offline -> stringResource(R.string.update_err_offline)
        UpdateError.NotConnected -> stringResource(R.string.settings_update_need_bot)
        UpdateError.AnnouncementUnreadable -> stringResource(R.string.update_err_unreadable)
        UpdateError.PackageMissing -> stringResource(R.string.update_err_missing)
        UpdateError.StorageLow -> stringResource(R.string.update_err_storage, formatSize(state.failure.neededBytes))
        UpdateError.DownloadFailed -> stringResource(R.string.update_err_download)
        UpdateError.PackageInvalid -> stringResource(R.string.update_err_invalid)
        UpdateError.InstallNotAllowed -> stringResource(R.string.update_err_install_blocked)
    }
}
