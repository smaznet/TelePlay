package com.smaznet.teleplay.ui.player

import com.smaznet.teleplay.R
import com.smaznet.teleplay.playback.PlayerError
import org.junit.Assert.*
import org.junit.Test

class PlayerLogicTest {
    @Test fun seekAccelerates() {
        assertEquals(10_000L, PlayerLogic.seekStepMs(0))
        assertEquals(0L, PlayerLogic.seekStepMs(3))
        assertEquals(10_000L, PlayerLogic.seekStepMs(5))
        assertEquals(30_000L, PlayerLogic.seekStepMs(10))
        assertEquals(60_000L, PlayerLogic.seekStepMs(30))
    }
    @Test fun formatTime() {
        assertEquals("0:00", PlayerLogic.formatTime(-5))
        assertEquals("1:05", PlayerLogic.formatTime(65_000))
        assertEquals("1:01:01", PlayerLogic.formatTime(3_661_000))
    }
    @Test fun fraction() {
        assertEquals(0f, PlayerLogic.progressFraction(5, 0), 0f)
        assertEquals(0.5f, PlayerLogic.progressFraction(5, 10), 0f)
        assertEquals(1f, PlayerLogic.progressFraction(50, 10), 0f)
    }
    @Test fun errorSpecs() {
        assertTrue(PlayerError.Offline.toSpec().canRetry)
        assertTrue(PlayerError.ProxyFailed.toSpec().canRetry)
        assertEquals(R.string.player_error_proxy_title, PlayerError.ProxyFailed.toSpec().titleRes)
        assertEquals(7, PlayerError.RateLimited(7).toSpec().countdownSec)
        assertEquals(1, PlayerError.RateLimited(0).toSpec().countdownSec)
        assertFalse(PlayerError.UnplayableFormat.toSpec().canRetry)
        assertEquals(R.string.player_error_expired_title, PlayerError.LinkExpired.toSpec().titleRes)
        assertTrue(PlayerError.Unknown.toSpec().canRetry)
    }
}
