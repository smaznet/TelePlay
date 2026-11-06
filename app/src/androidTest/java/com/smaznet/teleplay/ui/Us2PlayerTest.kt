package com.smaznet.teleplay.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.smaznet.teleplay.playback.PlayerError
import com.smaznet.teleplay.ui.player.PlayerControls
import com.smaznet.teleplay.ui.player.PlayerErrorPanel
import com.smaznet.teleplay.ui.player.TAG_PLAYER_ROOT
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class Us2PlayerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun dpadCenterTogglesAndArrowsSeek() {
        var toggles = 0
        var seeked = 0L
        rule.setContent {
            PlayerControls(
                positionMs = 0, durationMs = 100_000, isBuffering = false, isPlaying = false,
                onTogglePlayPause = { toggles++ }, onPlay = {}, onPause = {}, onSeekBy = { seeked += it },
            )
        }
        val root = rule.onNodeWithTag(TAG_PLAYER_ROOT)
        root.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, toggles)
        root.performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(10_000L, seeked)
        root.performKeyInput { pressKey(Key.DirectionLeft) }
        assertEquals(0L, seeked)
        root.performKeyInput { pressKey(Key.MediaPlayPause) }
        assertEquals(2, toggles)
    }

    @Test fun unplayableShowsBackButtonWithoutRetry() {
        var backs = 0
        rule.setContent { PlayerErrorPanel(PlayerError.UnplayableFormat, onRetry = {}, onBack = { backs++ }) }
        rule.onNodeWithText("Back to library").assertIsDisplayed().performClick()
        assertEquals(1, backs)
    }
}
