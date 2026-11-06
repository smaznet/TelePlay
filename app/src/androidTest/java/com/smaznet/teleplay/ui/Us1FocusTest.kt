package com.smaznet.teleplay.ui

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smaznet.teleplay.domain.Availability
import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.domain.PairingChallenge
import com.smaznet.teleplay.domain.PlaybackProgress
import com.smaznet.teleplay.domain.Video
import com.smaznet.teleplay.ui.library.LibraryScreen
import com.smaznet.teleplay.ui.pairing.PairingScreen
import com.smaznet.teleplay.ui.theme.TelePlayTheme
import com.smaznet.teleplay.ui.token.TokenScreen
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class Us1FocusTest {
    @get:Rule val rule = createComposeRule()

    private fun video(id: Long, at: Long) = Video(
        id, 1, id.toInt(), id, 0, ByteArray(0), 2, "Video $id", 60, 1000, "video/mp4", 1, 1, null, at, Availability.PLAYABLE,
    )

    @Test fun tokenField_hasInitialFocus_thenDownReachesConnect() {
        rule.setContent { TelePlayTheme { TokenScreen(onSubmit = { Result.success(BotInfo(1, "bot")) }) } }
        rule.onNodeWithTag("token_field").assertIsFocused()
        rule.onNodeWithTag("token_field").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("connect_button").assertIsFocused()
    }

    @Test fun pairing_regenerateIsFocused() {
        rule.setContent {
            TelePlayTheme {
                PairingScreen(
                    challenge = PairingChallenge("ABCD2345", Long.MAX_VALUE), botName = "bot",
                    onRegenerate = {}, onDisconnect = {}, onLeave = {},
                )
            }
        }
        rule.onNodeWithTag("regenerate_button").assertIsFocused()
    }

    @Test fun library_heroPlayFocused_whenNoContinueRow() {
        rule.setContent {
            TelePlayTheme {
                LibraryScreen(
                    videos = listOf(video(1, 10), video(2, 20)), continueWatching = emptyList(),
                    progressOf = { flowOf(null) }, thumbnail = { null }, offline = false, lastPlayedId = null,
                    onPlay = { _, _ -> }, onRefresh = {}, onOpenSettings = {},
                )
            }
        }
        rule.onNodeWithTag("hero_play").assertIsFocused() // hero features the newest video
    }

    @Test fun library_heroPlayFocused_whenContinueRow() {
        val v1 = video(1, 10)
        rule.setContent {
            TelePlayTheme {
                LibraryScreen(
                    videos = listOf(v1, video(2, 20)), continueWatching = listOf(v1),
                    progressOf = { flowOf(PlaybackProgress(it, 500, 1000, false, 0)) }, thumbnail = { null },
                    offline = false, lastPlayedId = null,
                    onPlay = { _, _ -> }, onRefresh = {}, onOpenSettings = {},
                )
            }
        }
        rule.onNodeWithTag("hero_play").assertIsFocused()
    }

    @Test fun library_emptyFocusesRefresh() {
        rule.setContent {
            TelePlayTheme {
                LibraryScreen(
                    videos = emptyList(), continueWatching = emptyList(),
                    progressOf = { flowOf(null) }, thumbnail = { null }, offline = false, lastPlayedId = null,
                    onPlay = { _, _ -> }, onRefresh = {}, onOpenSettings = {},
                )
            }
        }
        rule.onNodeWithTag("refresh_button").assertIsFocused()
    }
}
