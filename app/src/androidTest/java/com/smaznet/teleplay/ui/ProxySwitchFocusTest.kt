package com.smaznet.teleplay.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smaznet.teleplay.domain.Availability
import com.smaznet.teleplay.domain.PlaybackProgress
import com.smaznet.teleplay.domain.Video
import com.smaznet.teleplay.ui.library.LibraryScreen
import com.smaznet.teleplay.ui.proxy.FailureAction
import com.smaznet.teleplay.ui.proxy.ProxyIndicator
import com.smaznet.teleplay.ui.proxy.ProxySwitchUi
import com.smaznet.teleplay.ui.proxy.ToggleAction
import com.smaznet.teleplay.ui.theme.TelePlayTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Home proxy switch is a D-pad stop in the top bar, toggles with OK, and leaves the rest of Home reachable. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ProxySwitchFocusTest {
    @get:Rule val rule = createComposeRule()

    private fun video(id: Long, at: Long) = Video(
        id, 1, id.toInt(), id, 0, ByteArray(0), 2, "Video $id", 60, 1000, "video/mp4", 1, 1, null, at, Availability.PLAYABLE,
    )

    private fun setHome(ui: () -> ProxySwitchUi, toggles: MutableList<ToggleAction>, failures: MutableList<FailureAction> = mutableListOf()) {
        rule.setContent {
            TelePlayTheme {
                LibraryScreen(
                    videos = listOf(video(1, 10), video(2, 20)), continueWatching = emptyList(),
                    progressOf = { flowOf<PlaybackProgress?>(null) }, thumbnail = { null }, offline = false, lastPlayedId = null,
                    onPlay = { _, _ -> }, onRefresh = {}, onOpenSettings = {},
                    proxySwitch = ui(), onToggleProxy = { toggles += it }, onProxyFailureAction = { failures += it },
                )
            }
        }
    }

    @Test fun switchIsShownAndOkToggles() {
        val toggles = mutableListOf<ToggleAction>()
        setHome({ ProxySwitchUi(on = false, name = "Home", indicator = ProxyIndicator.OFF, hasProxies = true) }, toggles)
        rule.onNodeWithTag("proxy_switch").assertIsDisplayed()
        rule.onNodeWithTag("proxy_switch").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf(ToggleAction.ENABLE), toggles)
    }

    @Test fun switchIsReachableFromTheHeroWithUp() {
        setHome({ ProxySwitchUi(false, null, ProxyIndicator.OFF, false) }, mutableListOf())
        rule.onNodeWithTag("hero_play").assertIsFocused()
        // Up from the hero reaches the top bar; the switch is its first stop (left-most), then Refresh and Settings follow.
        rule.onNodeWithTag("hero_play").performKeyInput { pressKey(Key.DirectionUp) }
        rule.onNodeWithTag("proxy_switch").assertIsFocused()
        rule.onNodeWithTag("proxy_switch").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("hero_play").assertIsFocused()
    }

    @Test fun withNoSavedProxyTheSwitchAsksToAddOne() {
        val toggles = mutableListOf<ToggleAction>()
        setHome({ ProxySwitchUi(false, null, ProxyIndicator.OFF, false) }, toggles)
        rule.onNodeWithTag("proxy_switch").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf(ToggleAction.OPEN_ADD), toggles)
    }

    @Test fun aFailingProxyOffersRetryChooseAndDirect() {
        val toggles = mutableListOf<ToggleAction>(); val failures = mutableListOf<FailureAction>()
        setHome({ ProxySwitchUi(true, "Home", ProxyIndicator.FAILED, true) }, toggles, failures)
        rule.onNodeWithTag("proxy_switch").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithTag("proxy_fail_retry").assertIsFocused()
        rule.onNodeWithTag("proxy_fail_retry").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("proxy_fail_choose").assertIsFocused()
        rule.onNodeWithTag("proxy_fail_choose").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("proxy_fail_direct").assertIsFocused()
        rule.onNodeWithTag("proxy_fail_direct").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf(FailureAction.GO_DIRECT), failures)
        assertEquals(emptyList<ToggleAction>(), toggles)
    }

    @Test fun indicatorShowsTheProxyNameAndState() {
        setHome({ ProxySwitchUi(true, "Home", ProxyIndicator.CONNECTED, true) }, mutableListOf())
        rule.onNodeWithTag("proxy_indicator").assertIsDisplayed()
    }
}
