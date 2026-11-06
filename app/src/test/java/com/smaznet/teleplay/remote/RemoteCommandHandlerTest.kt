package com.smaznet.teleplay.remote

import com.smaznet.teleplay.domain.remote.CallbackQuery
import com.smaznet.teleplay.domain.remote.PlayState
import com.smaznet.teleplay.domain.remote.PlaybackCommand
import com.smaznet.teleplay.domain.remote.PlaybackHub
import com.smaznet.teleplay.domain.remote.PlaybackSnapshot
import com.smaznet.teleplay.domain.remote.RemoteCommandHandler
import com.smaznet.teleplay.fakes.FakeBotMessenger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCommandHandlerTest {
    private val hub = PlaybackHub().apply { setLibraryOrder(listOf(3, 2, 1)) }
    private val messenger = FakeBotMessenger()
    private var accepted = 0
    private val stale = mutableListOf<CallbackQuery>()
    private var session: String? = "AbCd1234"
    private val handler = RemoteCommandHandler(
        hub, messenger, authorizedUserId = { 42 }, currentSessionId = { session },
        onAccepted = { accepted++ }, onStale = { stale += it },
    )

    private fun snap(video: Long = 2, state: PlayState = PlayState.PLAYING, pos: Long = 100_000, dur: Long = 600_000) =
        PlaybackSnapshot(video, "t", state, pos, dur, true, true, 0)

    private fun q(action: String, user: Long = 42, id: Long = 7, session: String = "AbCd1234", raw: String? = null) =
        CallbackQuery(id, user, 10, raw ?: "v1:$session:$action")

    /** Runs [block] while collecting the hub's commands. */
    private fun kotlinx.coroutines.test.TestScope.collecting(block: suspend () -> Unit): List<PlaybackCommand> {
        val got = mutableListOf<PlaybackCommand>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { hub.commands.collect { got += it } }
        kotlinx.coroutines.runBlocking { block() }
        job.cancel()
        return got
    }

    @Test fun otherUserIsIgnoredWithoutAnswer() = runTest {
        hub.publish(snap())
        val cmds = collecting { handler.handle(q("pp", user = 99)) }
        assertTrue(cmds.isEmpty())
        assertTrue(messenger.answers.isEmpty())
        assertEquals(0, accepted)
    }

    @Test fun staleSessionGetsToastAndNoCommand() = runTest {
        hub.publish(snap())
        val cmds = collecting { handler.handle(q("pp", session = "ZZZZZZZZ")) }
        assertTrue(cmds.isEmpty())
        assertEquals(RemoteCommandHandler.STALE, messenger.answers.single().toast)
        assertEquals(1, stale.size)
    }

    @Test fun noActiveSessionIsStaleToo() = runTest {
        hub.publish(snap()); session = null
        collecting { handler.handle(q("pp")) }
        assertEquals(RemoteCommandHandler.STALE, messenger.answers.single().toast)
    }

    @Test fun toggleFollowsCurrentState() = runTest {
        hub.publish(snap(state = PlayState.PLAYING))
        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Pause), collecting { handler.handle(q("pp")) })
        hub.publish(snap(state = PlayState.PAUSED))
        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.Play), collecting { handler.handle(q("pp")) })
        assertEquals(2, accepted)
    }

    @Test fun seekIsThirtySecondsAndClamped() = runTest {
        hub.publish(snap(pos = 100_000))
        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.SeekBy(30_000)), collecting { handler.handle(q("sf")) })
        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.SeekBy(-30_000)), collecting { handler.handle(q("sb")) })
        hub.publish(snap(pos = 10_000))
        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.SeekBy(-10_000)), collecting { handler.handle(q("sb")) })
        hub.publish(snap(pos = 590_000))
        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.SeekBy(10_000)), collecting { handler.handle(q("sf")) })
    }

    @Test fun skipFollowsLibraryOrderWithoutWrap() = runTest {
        hub.publish(snap(video = 2))
        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.SkipTo(1)), collecting { handler.handle(q("nx")) })
        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.SkipTo(3)), collecting { handler.handle(q("pv")) })
        hub.publish(snap(video = 1))
        assertTrue(collecting { handler.handle(q("nx", id = 8)) }.isEmpty())
        assertEquals(RemoteCommandHandler.NO_NEXT, messenger.answers.last().toast)
        hub.publish(snap(video = 3))
        assertTrue(collecting { handler.handle(q("pv", id = 9)) }.isEmpty())
        assertEquals(RemoteCommandHandler.NO_PREVIOUS, messenger.answers.last().toast)
    }

    @Test fun garbageIsAnsweredSilently() = runTest {
        hub.publish(snap())
        val cmds = collecting { handler.handle(q("pp", raw = "nonsense")) }
        assertTrue(cmds.isEmpty())
        assertEquals(null, messenger.answers.single().toast)
        assertEquals(0, accepted)
    }

    @Test fun everyAcceptedPressIsAnsweredExactlyOnce() = runTest {
        hub.publish(snap())
        collecting { handler.handle(q("pp", id = 1)); handler.handle(q("sf", id = 2)); handler.handle(q("nx", id = 3)) }
        assertEquals(listOf(1L, 2L, 3L), messenger.answers.map { it.queryId })
    }
}
