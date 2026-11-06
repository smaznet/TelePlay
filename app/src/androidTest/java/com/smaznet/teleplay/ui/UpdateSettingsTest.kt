package com.smaznet.teleplay.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smaznet.teleplay.domain.CacheStats
import com.smaznet.teleplay.domain.update.Announcement
import com.smaznet.teleplay.domain.update.ReleasePackage
import com.smaznet.teleplay.domain.update.UpdateError
import com.smaznet.teleplay.domain.update.UpdateFailure
import com.smaznet.teleplay.domain.update.UpdateState
import com.smaznet.teleplay.ui.settings.SettingsScreen
import com.smaznet.teleplay.ui.settings.UpdateDialog
import com.smaznet.teleplay.ui.theme.TelePlayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UpdateSettingsTest {
    @get:Rule val rule = createComposeRule()
    private var checks = 0
    private var retries = 0
    private var dismisses = 0
    private var updates = 0

    /** D-pad select: this emulator's touch clicks do not reach TV surfaces, so drive focus and the center key. */
    private fun SemanticsNodeInteraction.select(focusFirst: Boolean = false) {
        if (focusFirst) performSemanticsAction(SemanticsActions.RequestFocus)
        performKeyInput { pressKey(Key.DirectionCenter) }
    }

    private fun settings(enabled: Boolean) = rule.setContent {
        TelePlayTheme {
            SettingsScreen(
                cacheStats = CacheStats(1L shl 20, 2L shl 30), cacheClearedNotice = false,
                onClearCache = {}, onResetPairing = {}, onDisconnect = {},
                updateEnabled = enabled, onCheckUpdate = { checks++ }, installedVersionName = "1.1",
            )
        }
    }

    private fun dialog(state: UpdateState) = rule.setContent {
        TelePlayTheme {
            UpdateDialog(
                state = state, installBlocked = false, onUpdate = { updates++ }, onRetry = { retries++ },
                onInstall = {}, onOpenSettings = {}, onDismiss = { dismisses++ },
            )
        }
    }

    @Test fun enabledRowInvokesCallback() {
        settings(true)
        rule.onNodeWithTag("check_update").assertIsEnabled().select(focusFirst = true)
        assertEquals(1, checks)
    }

    @Test fun disabledRowShowsHintAndIsNotClickable() {
        settings(false)
        rule.onNodeWithTag("check_update").assertIsNotEnabled().assertTextContains("Connect your bot to check for updates", substring = true)
        rule.onNodeWithTag("check_update").select(focusFirst = true)
        assertEquals(0, checks)
    }

    @Test fun availablePromptFocusesUpdate() {
        dialog(UpdateState.Available(Announcement(3, 3), ReleasePackage(3, "a.apk", 10)))
        rule.onNodeWithTag("update_primary").assertIsFocused().select()
        assertEquals(1, updates)
        rule.onNodeWithTag("update_secondary").select(focusFirst = true)
        assertEquals(1, dismisses)
    }

    @Test fun everyFailureShowsMessageAndRetryHasFocus() {
        val expected = mapOf(
            UpdateError.Offline to "No internet connection",
            UpdateError.AnnouncementUnreadable to "Couldn't read update info",
            UpdateError.PackageMissing to "The update isn't available yet",
            UpdateError.DownloadFailed to "Download failed",
            UpdateError.PackageInvalid to "The downloaded file isn't a valid update",
        )
        val state = mutableStateOf<UpdateState>(UpdateState.Failed(UpdateFailure(UpdateError.Offline)))
        rule.setContent {
            TelePlayTheme { UpdateDialog(state.value, false, {}, { retries++ }, {}, {}, { dismisses++ }) }
        }
        for ((error, text) in expected) {
            state.value = UpdateState.Failed(UpdateFailure(error))
            rule.waitForIdle()
            rule.onNodeWithTag("update_message").assertTextContains(text)
            rule.onNodeWithTag("update_primary").assertIsFocused()
        }
        rule.onNodeWithTag("update_primary").select(focusFirst = true)
        assertEquals(1, retries)
    }

    @Test fun storageLowShowsNeededSize() {
        dialog(UpdateState.Failed(UpdateFailure(UpdateError.StorageLow, 30L * 1024 * 1024)))
        rule.onNodeWithTag("update_message").assertTextContains("Not enough free space", substring = true)
    }

    @Test fun downloadingShowsCancelAsPrimary() {
        dialog(UpdateState.Downloading(1024, 2048))
        rule.onNodeWithTag("update_primary").assertIsFocused().select()
        assertEquals(1, dismisses)
    }

    @Test fun lastRowScrollsIntoViewWithDpad() {
        settings(true)
        // walk focus down to the last row; a non-scrolling column would leave it off screen on a 720p TV
        rule.onNodeWithTag("disconnect").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("disconnect").assertIsFocused().assertIsDisplayed()
    }
}
