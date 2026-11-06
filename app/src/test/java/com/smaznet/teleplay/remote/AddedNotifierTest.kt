package com.smaznet.teleplay.remote

import com.smaznet.teleplay.data.repo.IngestResult
import com.smaznet.teleplay.domain.remote.AddedNotifier
import com.smaznet.teleplay.domain.remote.BooleanPrefs
import com.smaznet.teleplay.domain.remote.RemoteFailure
import com.smaznet.teleplay.domain.remote.RemoteSettings
import com.smaznet.teleplay.fakes.FakeBotMessenger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPrefs : BooleanPrefs {
    val map = mutableMapOf<String, Boolean>()
    override fun get(key: String, default: Boolean) = map[key] ?: default
    override fun put(key: String, value: Boolean) { map[key] = value }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AddedNotifierTest {
    private val messenger = FakeBotMessenger()
    private val settings = RemoteSettings(MemoryPrefs())

    private fun TestScope.notifier() = AddedNotifier(messenger, settings, backgroundScope)

    @Test fun loneVideoGetsItsOwnMessageAfterTenSeconds() = runTest {
        val n = notifier()
        n.offer(IngestResult.Added(1, "Movie"))
        advanceTimeBy(9_999); assertTrue(messenger.sends.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf("Added to TelePlay: Movie"), messenger.sends.map { it.text })
    }

    @Test fun burstIsGroupedIntoOneMessage() = runTest {
        val n = notifier()
        n.offer(IngestResult.Added(1, "a")); advanceTimeBy(3_000)
        n.offer(IngestResult.Added(2, "b")); advanceTimeBy(3_000)
        n.offer(IngestResult.Added(3, "c")); advanceTimeBy(5_000)
        val s = messenger.sends.single().text
        assertTrue(s, s.startsWith("Added 3 videos:"))
        assertTrue(s.contains("• a") && s.contains("• b") && s.contains("• c"))
    }

    @Test fun duplicateUsesAlreadyInLibraryWording() = runTest {
        val n = notifier()
        n.offer(IngestResult.Duplicate(1, "Movie")); advanceTimeBy(11_000)
        assertEquals("Already in your library: Movie", messenger.sends.single().text)
    }

    @Test fun videosElevenSecondsApartAreTwoMessages() = runTest {
        val n = notifier()
        n.offer(IngestResult.Added(1, "a")); advanceTimeBy(11_000)
        n.offer(IngestResult.Added(2, "b")); advanceTimeBy(11_000)
        assertEquals(2, messenger.sends.size)
    }

    @Test fun ignoredAndRedeliveredProduceNothing() = runTest {
        val n = notifier()
        n.offer(IngestResult.Ignored); n.offer(IngestResult.Redelivered(1)); advanceTimeBy(30_000)
        assertTrue(messenger.sends.isEmpty())
    }

    @Test fun toggleOffSendsNothing() = runTest {
        val n = notifier()
        settings.setNotifyAdded(false)
        n.offer(IngestResult.Added(1, "a")); advanceTimeBy(30_000)
        assertTrue(messenger.sends.isEmpty())
    }

    @Test fun toggleOffDuringWindowDropsTheNotice() = runTest {
        val n = notifier()
        n.offer(IngestResult.Added(1, "a")); advanceTimeBy(2_000)
        settings.setNotifyAdded(false); advanceTimeBy(20_000)
        assertTrue(messenger.sends.isEmpty())
    }

    @Test fun messengerFailureDoesNotThrow() = runTest {
        val n = notifier()
        messenger.failAll = RemoteFailure.BLOCKED
        n.offer(IngestResult.Added(1, "a")); advanceTimeBy(11_000)
        assertTrue(messenger.sends.isEmpty())
    }
}
