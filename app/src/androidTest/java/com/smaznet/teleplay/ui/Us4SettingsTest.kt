package com.smaznet.teleplay.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smaznet.teleplay.domain.CacheStats
import com.smaznet.teleplay.ui.settings.SettingsScreen
import com.smaznet.teleplay.ui.theme.TelePlayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Us4SettingsTest {
    @get:Rule val rule = createComposeRule()
    private var resets = 0
    private var disconnects = 0

    private fun show() = rule.setContent {
        TelePlayTheme {
            SettingsScreen(
                cacheStats = CacheStats(1L shl 20, 2L shl 30), cacheClearedNotice = false,
                onClearCache = {}, onResetPairing = { resets++ }, onDisconnect = { disconnects++ },
            )
        }
    }

    @Test fun cacheRowHasInitialFocus() {
        show()
        rule.onNodeWithTag("cache_row").assertIsFocused()
    }

    @Test fun disconnectNeedsConfirmation_andCancelIsFocused() {
        show()
        rule.onNodeWithTag("disconnect").performClick()
        rule.onNodeWithTag("dialog_cancel").assertIsFocused()
        rule.onNodeWithTag("dialog_cancel").performClick()
        assertEquals(0, disconnects)
        rule.onNodeWithTag("disconnect").performClick()
        rule.onNodeWithTag("dialog_confirm").performClick()
        assertEquals(1, disconnects)
    }

    @Test fun resetPairingNeedsConfirmation() {
        show()
        rule.onNodeWithTag("reset_pairing").performClick()
        assertEquals(0, resets)
        rule.onNodeWithTag("dialog_confirm").performClick()
        assertEquals(1, resets)
    }

    @Test fun remoteTogglesChangeStateAndShowOnOff() {
        var notifyNow = true
        var liveNow = true
        rule.setContent {
            var notify by remember { mutableStateOf(true) }
            var live by remember { mutableStateOf(true) }
            notifyNow = notify; liveNow = live
            TelePlayTheme {
                SettingsScreen(
                    cacheStats = CacheStats(1L shl 20, 2L shl 30), cacheClearedNotice = false,
                    onClearCache = {}, onResetPairing = {}, onDisconnect = {},
                    notifyAdded = notify, onNotifyAddedChange = { notify = it },
                    liveRemote = live, onLiveRemoteChange = { live = it },
                )
            }
        }
        rule.onNodeWithTag("toggle_notify_added").assertTextContains("On", substring = true)
        rule.onNodeWithTag("toggle_notify_added").performClick()
        rule.onNodeWithTag("toggle_notify_added").assertTextContains("Off", substring = true)
        assertEquals(false, notifyNow)
        rule.onNodeWithTag("toggle_live_remote").performClick()
        rule.onNodeWithTag("toggle_live_remote").assertTextContains("Off", substring = true)
        assertEquals(false, liveNow)
        rule.onNodeWithTag("toggle_live_remote").performClick()
        assertEquals(true, liveNow)
    }
}
