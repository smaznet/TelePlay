package com.smaznet.teleplay.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.ui.theme.TelePlayTheme
import com.smaznet.teleplay.ui.token.TokenScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Add proxy" on the token screen is reachable with the D-pad. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TokenProxyFocusTest {
    @get:Rule val rule = createComposeRule()

    @Test fun addProxyIsReachableWithTheDpadAndOpens() {
        var opened = 0
        rule.setContent {
            TelePlayTheme { TokenScreen(onSubmit = { Result.success(BotInfo(1, "bot")) }, onAddProxy = { opened++ }) }
        }
        rule.onNodeWithTag("token_field").assertIsFocused()
        rule.onNodeWithTag("token_field").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("connect_button").assertIsFocused()
        rule.onNodeWithTag("connect_button").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("add_proxy_button").assertIsFocused()
        rule.onNodeWithTag("add_proxy_button").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, opened)
    }

    @Test fun noAddProxyButtonWhenNotOffered() {
        rule.setContent { TelePlayTheme { TokenScreen(onSubmit = { Result.success(BotInfo(1, "bot")) }) } }
        rule.onNodeWithTag("connect_button").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("connect_button").assertIsFocused()
    }
}
