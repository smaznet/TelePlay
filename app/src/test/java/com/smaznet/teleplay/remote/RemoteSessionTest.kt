package com.smaznet.teleplay.remote

import com.smaznet.teleplay.domain.remote.CallbackQuery
import com.smaznet.teleplay.domain.remote.PlayState
import com.smaznet.teleplay.domain.remote.PlaybackHub
import com.smaznet.teleplay.domain.remote.PlaybackSnapshot
import com.smaznet.teleplay.domain.remote.RemoteFailure
import com.smaznet.teleplay.domain.remote.RemoteMessage
import com.smaznet.teleplay.domain.remote.RemoteSession
import com.smaznet.teleplay.domain.remote.RemoteSettings
import com.smaznet.teleplay.fakes.FakeBotMessenger
import com.smaznet.teleplay.fakes.InMemoryRemoteMessageStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSessionTest {
    private class Env(val t: TestScope, var peer: Long? = 42L) {
        val hub = PlaybackHub().apply { setLibraryOrder(listOf(3, 2, 1)) }
        val messenger = FakeBotMessenger()
        val store = InMemoryRemoteMessageStore()
        val settings = RemoteSettings(MemoryPrefs())
        var counter = 0
        val session = RemoteSession(
            hub, messenger, store, settings, t.backgroundScope, peerUserId = { peer },
            now = { t.testScheduler.currentTime }, newSessionId = { "Sess%04d".format(counter++) },
        ).also { it.start() }

        fun snap(video: Long = 1, state: PlayState = PlayState.PLAYING, pos: Long = 0, dur: Long = 600_000, title: String = "Movie $video") =
            PlaybackSnapshot(video, title, state, pos, dur, true, true, 0)

        fun publish(s: PlaybackSnapshot?) { hub.publish(s); t.runCurrent() }

        fun step(ms: Long) { t.advanceTimeBy(ms); t.runCurrent() }
    }

    // --- US2: start, reuse, failures -------------------------------------------------------

    @Test fun firstSnapshotSendsOneMessageWithKeyboardAndPinsIt() = runTest {
        val e = Env(this)
        e.publish(e.snap())
        val send = e.messenger.sends.single()
        assertTrue(send.text.startsWith("▶ Now playing\nMovie 1"))
        assertEquals(5, send.keyboard!!.single().size)
        assertEquals(listOf(send.id), e.messenger.pins.map { it.id })
        val row = e.store.row!!
        assertEquals(send.id, row.messageId); assertTrue(row.pinned); assertEquals(42L, row.chatUserId)
        assertEquals("Sess0000", e.session.currentSessionId())
    }

    @Test fun nextVideoInSameSessionEditsTheSameMessage() = runTest {
        val e = Env(this)
        e.publish(e.snap(video = 1))
        e.publish(e.snap(video = 2))
        e.step(1_500)
        assertEquals(1, e.messenger.sends.size)
        assertEquals(1, e.messenger.pins.size)
        val edit = e.messenger.edits.single()
        assertEquals(e.messenger.sends[0].id, edit.id)
        assertTrue(edit.text.contains("Movie 2"))
    }

    @Test fun noMessageWhenLiveRemoteIsOffOrNoPeer() = runTest {
        val e = Env(this)
        e.settings.setLiveRemote(false)
        e.publish(e.snap())
        assertTrue(e.messenger.calls.isEmpty())
        val f = Env(this, peer = null)
        f.publish(f.snap())
        assertTrue(f.messenger.calls.isEmpty())
    }

    @Test fun deletedMessageIsRecreated() = runTest {
        val e = Env(this)
        e.publish(e.snap(pos = 0))
        val first = e.messenger.sends[0].id
        e.messenger.failNext("edit", RemoteFailure.GONE)
        e.publish(e.snap(pos = 25_000))
        e.step(30_000)
        assertEquals(2, e.messenger.sends.size)
        assertTrue(e.messenger.sends[1].id != first)
        assertEquals(e.messenger.sends[1].id, e.store.row!!.messageId)
        assertEquals("Sess0001", e.session.currentSessionId())
    }

    @Test fun sendFailureLeavesEverythingQuietThenRetriesLater() = runTest {
        val e = Env(this)
        e.messenger.failNext("send", RemoteFailure.OTHER)
        e.publish(e.snap(pos = 0))
        assertTrue(e.messenger.sends.isEmpty()); assertNull(e.session.currentSessionId())
        e.publish(e.snap(pos = 2_000))   // too soon: backoff
        assertTrue(e.messenger.sends.isEmpty())
        e.step(31_000)
        e.publish(e.snap(pos = 40_000))
        assertEquals(1, e.messenger.sends.size)
    }

    @Test fun blockedUserStopsMessagingUntilTheNextSession() = runTest {
        val e = Env(this)
        e.messenger.failNext("send", RemoteFailure.BLOCKED)
        e.publish(e.snap(pos = 0))
        e.step(60_000); e.publish(e.snap(pos = 61_000))
        assertTrue(e.messenger.sends.isEmpty())
        e.publish(null)                  // playback ends: block lifted
        e.publish(e.snap(pos = 0))
        assertEquals(1, e.messenger.sends.size)
    }

    @Test fun twoRequestsWithinOneSecondProduceOneEdit() = runTest {
        val e = Env(this)
        e.publish(e.snap(state = PlayState.PLAYING))
        e.publish(e.snap(state = PlayState.PAUSED))
        e.session.requestRefresh(); e.session.requestRefresh()
        e.step(1_200)
        assertEquals(1, e.messenger.edits.size)
        assertTrue(e.messenger.edits[0].text.startsWith("⏸ Paused"))
    }

    // --- US3: pin, 30 s refresh, finalize, cleanup ------------------------------------------

    @Test fun refreshesEveryThirtySecondsInPlace() = runTest {
        val e = Env(this)
        e.publish(e.snap(pos = 0))
        e.step(20_000); e.publish(e.snap(pos = 20_000))
        e.step(9_000)
        assertTrue(e.messenger.edits.isEmpty())      // 29 s: nothing yet
        e.step(1_500)                                  // 30.5 s
        assertEquals(1, e.messenger.edits.size)
        assertTrue(e.messenger.edits[0].text.contains("0:20 / 10:00"))
        e.step(30_000); e.publish(e.snap(pos = 50_000)); e.step(500)
        assertEquals(1, e.messenger.sends.size)
    }

    @Test fun pausedPlaybackProducesNoFurtherEdits() = runTest {
        val e = Env(this)
        e.publish(e.snap(state = PlayState.PAUSED, pos = 5_000))
        e.step(5 * 60_000)
        assertTrue(e.messenger.edits.size <= 1)       // at most the state change itself
        val n = e.messenger.edits.size
        e.step(5 * 60_000)
        assertEquals(n, e.messenger.edits.size)
    }

    @Test fun stoppingFinalizesUnpinsAndKeepsTheMessage() = runTest {
        val e = Env(this)
        e.publish(e.snap())
        val id = e.messenger.sends[0].id
        e.publish(null)
        e.step(5_000)
        val last = e.messenger.edits.last()
        assertEquals(id, last.id)
        assertEquals("⏹ Stopped\nMovie 1", last.text)
        assertNull(last.keyboard)
        assertEquals(listOf(id), e.messenger.unpins.map { it.id })
        assertNull(e.store.row); assertNull(e.session.currentSessionId())
        assertTrue(e.messenger.calls.none { it is FakeBotMessenger.Call.Answer })
    }

    @Test fun endedPlaybackShowsFinished() = runTest {
        val e = Env(this)
        e.publish(e.snap())
        e.publish(e.snap(state = PlayState.ENDED, pos = 600_000))
        e.step(5_000)
        assertEquals("✔ Finished\nMovie 1", e.messenger.edits.last().text)
        assertEquals(1, e.messenger.unpins.size)
    }

    @Test fun skipGapDoesNotEndTheSession() = runTest {
        val e = Env(this)
        e.publish(e.snap(video = 1))
        e.publish(null)
        e.step(1_500)
        e.publish(e.snap(video = 2))
        e.step(10_000)
        assertTrue(e.messenger.unpins.isEmpty())
        assertEquals(1, e.messenger.sends.size)
        assertEquals("Sess0000", e.session.currentSessionId())
    }

    @Test fun nextSessionSendsANewMessageAndLeavesTheOldOne() = runTest {
        val e = Env(this)
        e.publish(e.snap(video = 1)); e.publish(null); e.step(5_000)
        val oldEdits = e.messenger.edits.size
        e.publish(e.snap(video = 2))
        assertEquals(2, e.messenger.sends.size)
        assertEquals(2, e.messenger.pins.size)
        assertEquals(oldEdits, e.messenger.edits.size)
        assertTrue(e.messenger.sends[1].id != e.messenger.sends[0].id)
    }

    @Test fun leftoverFromAKilledRunIsFinalizedBeforeTheNewMessage() = runTest {
        val e = Env(this)
        e.store.row = RemoteMessage(42, 7, "OldSess01", 1, pinned = true, updatedAt = 0)
        e.publish(e.snap())
        val calls = e.messenger.calls
        val editIdx = calls.indexOfFirst { it is FakeBotMessenger.Call.Edit }
        val unpinIdx = calls.indexOfFirst { it is FakeBotMessenger.Call.Unpin }
        val sendIdx = calls.indexOfFirst { it is FakeBotMessenger.Call.Send }
        assertTrue(editIdx in 0 until sendIdx && unpinIdx in 0 until sendIdx)
        assertEquals("⏹ Stopped", e.messenger.edits[0].text)
        assertEquals(7, e.messenger.edits[0].id)
        assertEquals(7, e.messenger.unpins[0].id)
        assertEquals(e.messenger.sends[0].id, e.store.row!!.messageId)
    }

    @Test fun pressOnLeftoverMessageFinalizesIt() = runTest {
        val e = Env(this)
        e.store.row = RemoteMessage(42, 7, "OldSess01", 1, pinned = true, updatedAt = 0)
        e.session.onStalePress(CallbackQuery(1, 42, 7, "v1:OldSess01:pp"))
        assertEquals("⏹ Stopped", e.messenger.edits.single().text)
        assertEquals(7, e.messenger.unpins.single().id)
        assertNull(e.store.row)
    }

    @Test fun pinFailureDoesNotStopTheSession() = runTest {
        val e = Env(this)
        e.messenger.failNext("pin", RemoteFailure.OTHER)
        e.publish(e.snap(pos = 0))
        assertFalse(e.store.row!!.pinned)
        e.step(20_000); e.publish(e.snap(pos = 20_000)); e.step(11_000)
        assertEquals(1, e.messenger.edits.size)
        e.publish(null); e.step(5_000)
        assertTrue(e.messenger.unpins.isEmpty())      // never pinned, nothing to unpin
    }

    @Test fun floodOnATickIsSkippedAndTheNextTickEdits() = runTest {
        val e = Env(this)
        e.publish(e.snap(pos = 0))
        e.messenger.failNext("edit", RemoteFailure.FLOOD)
        e.step(20_000); e.publish(e.snap(pos = 20_000)); e.step(10_500)
        assertTrue(e.messenger.edits.isEmpty())
        e.step(20_000); e.publish(e.snap(pos = 50_000)); e.step(10_000)
        assertEquals(1, e.messenger.edits.size)
        assertEquals(1, e.messenger.sends.size)
    }

    @Test fun turningTheRemoteOffFinalizesAndUnpins() = runTest {
        val e = Env(this)
        e.publish(e.snap())
        val id = e.messenger.sends[0].id
        e.settings.setLiveRemote(false); runCurrent()
        assertEquals("⏹ Stopped", e.messenger.edits.last().text)
        assertEquals(listOf(id), e.messenger.unpins.map { it.id })
        assertNull(e.store.row)
        e.publish(e.snap(pos = 50_000))              // still playing, but the remote is off
        assertEquals(1, e.messenger.sends.size)
    }

    @Test fun shutdownEndsEverythingForDisconnect() = runTest {
        val e = Env(this)
        e.publish(e.snap())
        e.session.shutdown(unpin = true)
        assertEquals(1, e.messenger.unpins.size)
        assertNull(e.store.row)
        e.step(120_000)
        assertTrue(e.messenger.edits.size == 1)       // only the final edit, no further ticks
    }
}
