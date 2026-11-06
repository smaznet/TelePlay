package com.smaznet.teleplay.remote

import com.smaznet.teleplay.data.repo.IngestResult
import com.smaznet.teleplay.domain.remote.AddedNotifier
import com.smaznet.teleplay.domain.remote.PlayState
import com.smaznet.teleplay.domain.remote.PlaybackHub
import com.smaznet.teleplay.domain.remote.PlaybackSnapshot
import com.smaznet.teleplay.domain.remote.RemoteMessage
import com.smaznet.teleplay.domain.remote.RemoteSession
import com.smaznet.teleplay.domain.remote.RemoteSettings
import com.smaznet.teleplay.fakes.FakeBotMessenger
import com.smaznet.teleplay.fakes.InMemoryRemoteMessageStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Constitution Principle IV: nothing secret is logged or sent by the remote. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNoSecretsTest {
    private val forbidden = listOf("android.util.Log", "Log.d(", "Log.e(", "Log.i(", "Log.w(", "Log.v(", "println(", "Timber", "printStackTrace")
    private val bareCalls = Regex("(?<![A-Za-z0-9_.])print\\(")

    private fun sourceRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, "app/src/main/java/com/smaznet/teleplay").takeIf { it.isDirectory }?.let { return it }
            File(dir, "src/main/java/com/smaznet/teleplay").takeIf { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("source root not found")
    }

    @Test fun remoteSourcesDoNotLog() {
        val root = sourceRoot()
        val files = File(root, "domain/remote").walkTopDown().filter { it.extension == "kt" }.toList() +
            File(root, "data/telegram/BotMessenger.kt") + File(root, "data/telegram/PushedUpdates.kt") +
            File(root, "data/db/RemoteMessageStore.kt")
        assertTrue("scanned some sources", files.size >= 8)
        for (f in files) {
            val text = f.readText().lineSequence()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
                .joinToString("\n")
            for (token in forbidden) assertFalse("${f.name} must not contain $token", text.contains(token))
            assertFalse("${f.name} must not call print()", bareCalls.containsMatchIn(text))
        }
    }

    @Test fun nothingSentContainsTokenPairingCodeOrSessionKeys() = runTest {
        val token = "987654321:SENTINEL_token_value_ABCDEFGHIJKL"
        val code = "PAIRCODE"
        val messenger = FakeBotMessenger()
        val settings = RemoteSettings(MemoryPrefs())
        val hub = PlaybackHub().apply { setLibraryOrder(listOf(1, 2)) }
        val session = RemoteSession(hub, messenger, InMemoryRemoteMessageStore(), settings, backgroundScope, { 42L }, { testScheduler.currentTime })
        session.start()
        val notifier = AddedNotifier(messenger, settings, backgroundScope)
        notifier.offer(IngestResult.Added(1, "Holiday"))
        hub.publish(PlaybackSnapshot(1, "Holiday", PlayState.PLAYING, 0, 60_000, false, true, 0)); runCurrent()
        advanceTimeBy(40_000)
        hub.publish(PlaybackSnapshot(1, "Holiday", PlayState.PAUSED, 30_000, 60_000, false, true, 0)); runCurrent()
        hub.publish(null); advanceTimeBy(10_000); runCurrent()
        val everything = messenger.calls.joinToString("\n") +
            messenger.calls.filterIsInstance<FakeBotMessenger.Call.Send>().flatMap { it.keyboard.orEmpty().flatten() }.joinToString { it.data + it.label }
        assertTrue(messenger.calls.size >= 4)
        assertFalse(everything.contains(token)); assertFalse(everything.contains("SENTINEL")); assertFalse(everything.contains(code))
        assertFalse(RemoteMessage(42, 7, "AbCd1234", 1, true, 0).toString().contains(token))
    }
}
