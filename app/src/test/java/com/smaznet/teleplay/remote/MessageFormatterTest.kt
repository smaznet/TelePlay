package com.smaznet.teleplay.remote

import com.smaznet.teleplay.domain.remote.MessageFormatter
import com.smaznet.teleplay.domain.remote.PlayState
import com.smaznet.teleplay.domain.remote.PlaybackSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFormatterTest {
    private fun snap(state: PlayState = PlayState.PLAYING, pos: Long = 0, dur: Long = 100_000, title: String = "Movie") =
        PlaybackSnapshot(1, title, state, pos, dur, hasPrev = true, hasNext = true, capturedAt = 0)

    @Test fun singleAndDuplicateWording() {
        assertEquals("Added to TelePlay: A", MessageFormatter.addedSingle("A"))
        assertEquals("Already in your library: A", MessageFormatter.duplicateSingle("A"))
    }

    @Test fun batchListsTenThenMore() {
        val titles = (1..13).map { "v$it" }
        val text = MessageFormatter.addedBatch(titles, emptyList())
        val lines = text.lines()
        assertEquals("Added 13 videos:", lines[0])
        assertEquals(10, lines.count { it.startsWith("• ") })
        assertEquals("…and 3 more", lines.last())
    }

    @Test fun batchCountsDuplicatesSeparately() {
        val text = MessageFormatter.addedBatch(listOf("a", "b"), listOf("c"))
        assertTrue(text.contains("Added 2 videos:"))
        assertTrue(text.endsWith("1 already in the library"))
        assertEquals("1 already in the library", MessageFormatter.addedBatch(emptyList(), listOf("c")))
    }

    @Test fun titlesAreTruncatedAt80() {
        val text = MessageFormatter.addedSingle("x".repeat(200))
        assertEquals("Added to TelePlay: ".length + 80, text.length)
        assertTrue(text.endsWith("…"))
    }

    @Test fun progressBarCells() {
        assertEquals("▱▱▱▱▱▱▱▱▱▱", MessageFormatter.bar(0, 100))
        assertEquals("▰▰▰▰▰▱▱▱▱▱", MessageFormatter.bar(50, 100))
        assertEquals("▰▰▰▰▰▰▰▰▰▰", MessageFormatter.bar(100, 100))
        assertEquals("▱▱▱▱▱▱▱▱▱▱", MessageFormatter.bar(50, 0))
        assertEquals("▰▰▰▰▰▰▰▰▰▰", MessageFormatter.bar(500, 100))
    }

    @Test fun nowPlayingLayout() {
        val text = MessageFormatter.nowPlaying(snap(pos = 754_000, dur = 6_130_000, title = "Film"))
        val lines = text.lines()
        assertEquals("▶ Now playing", lines[0])
        assertEquals("Film", lines[1])
        assertTrue(lines[2].endsWith("12:34 / 1:42:10"))
        assertTrue(MessageFormatter.nowPlaying(snap(PlayState.PAUSED)).startsWith("⏸ Paused"))
        assertTrue(MessageFormatter.nowPlaying(snap(PlayState.BUFFERING)).startsWith("⏳ Buffering"))
    }

    @Test fun finalTexts() {
        assertEquals("⏹ Stopped\nFilm", MessageFormatter.finalText("Film", finished = false))
        assertEquals("✔ Finished\nFilm", MessageFormatter.finalText("Film", finished = true))
    }

    @Test fun playPauseLabelShowsTheAction() {
        val playing = MessageFormatter.keyboard("AbCd1234", snap(PlayState.PLAYING)).single()
        val paused = MessageFormatter.keyboard("AbCd1234", snap(PlayState.PAUSED)).single()
        assertEquals("⏸", playing[2].label)
        assertEquals("▶", paused[2].label)
        assertEquals(5, playing.size)
    }

    @Test fun callbackDataFormat() {
        val row = MessageFormatter.keyboard("AbCd1234", snap()).single()
        val rx = Regex("^v1:[A-Za-z0-9]{8}:(pp|sb|sf|pv|nx)$")
        row.forEach {
            assertTrue(it.data, rx.matches(it.data))
            assertTrue(it.data.toByteArray().size <= 64)
        }
        assertEquals("AbCd1234" to "pp", MessageFormatter.parse("v1:AbCd1234:pp"))
        assertNull(MessageFormatter.parse("v2:AbCd1234:pp"))
        assertNull(MessageFormatter.parse("v1:short:pp"))
        assertNull(MessageFormatter.parse("v1:AbCd1234:zz"))
    }
}
