package com.smaznet.teleplay.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.setup.LocalAddressProvider
import com.smaznet.teleplay.setup.SetupHost
import com.smaznet.teleplay.ui.theme.TelePlayTheme
import com.smaznet.teleplay.ui.token.RevokedScreen
import com.smaznet.teleplay.ui.token.TokenScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class QrPanelFocusTest {
    @get:Rule val rule = createComposeRule()

    private class FakeAddresses(address: String?) : LocalAddressProvider {
        private val flow = MutableStateFlow(address)
        override fun current() = flow.value
        override fun changes(): Flow<String?> = flow
    }

    private fun host(address: String?) = SetupHost(submit = { Result.success(BotInfo(1, "bot")) }, addresses = FakeAddresses(address))

    @Test fun tokenScreen_focusOrderUnchangedWithPanel() {
        val h = host("127.0.0.1")
        rule.setContent { TelePlayTheme { TokenScreen(onSubmit = { Result.success(BotInfo(1, "bot")) }, setupHost = h) } }
        rule.onNodeWithTag("token_field").assertIsFocused()
        rule.onNodeWithTag("qr_panel").assertIsDisplayed()
        rule.onNodeWithTag("token_field").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("connect_button").assertIsFocused()
        // Nothing focusable exists beyond Connect: moving down again keeps focus on it, and Right never enters the panel.
        rule.onNodeWithTag("connect_button").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("connect_button").assertIsFocused()
        rule.onNodeWithTag("connect_button").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("connect_button").assertIsFocused()
        assertPanelHasNoFocusableNodes()
    }

    @Test fun tokenScreen_noNetworkShowsExplanationAndManualEntryWorks() {
        val h = host(null)
        rule.setContent { TelePlayTheme { TokenScreen(onSubmit = { Result.success(BotInfo(1, "bot")) }, setupHost = h) } }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTagCount("setup_no_network") > 0
        }
        rule.onNodeWithTag("setup_no_network").assertIsDisplayed()
        rule.onNodeWithTag("token_field").assertIsFocused()
        rule.onNodeWithTag("token_field").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("connect_button").assertIsFocused()
    }

    @Test fun revokedScreen_actionFocusedAndPanelPresent() {
        val h = host("127.0.0.1")
        rule.setContent { TelePlayTheme { RevokedScreen(onEnterNewToken = {}, setupHost = h) } }
        rule.onNodeWithTag("revoked_action").assertIsFocused()
        rule.onNodeWithTag("qr_panel").assertIsDisplayed()
        assertPanelHasNoFocusableNodes()
    }

    private fun assertPanelHasNoFocusableNodes() {
        rule.onNode(hasTestTag("qr_panel").and(isFocusable())).assertDoesNotExist()
        rule.onNode(hasTestTag("qr_panel").and(hasAnyDescendant(isFocusable()))).assertDoesNotExist()
    }

    private fun ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
}
