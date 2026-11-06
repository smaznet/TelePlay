package com.smaznet.teleplay.domain.playlist

import com.smaznet.teleplay.domain.*
import com.smaznet.teleplay.fakes.FakeBotMessenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistCommandsTest {
    private class T(var authorized: Long? = 42, username: String? = null) {
        val env = Env(authorized = authorized)
        val messenger = FakeBotMessenger()
        val transfer = PlaylistTransfer(env.videos, messenger, env.gateway, { 1L }, { 1_793_700_000_000 })
        val commands = PlaylistCommands({ authorized }, transfer, env.gateway, messenger, { username })
        val doc = IncomingDocument(900, 1, byteArrayOf(1), 2, "b.json", "application/json", 100)

        fun addVideos(n: Int) { for (i in 1..n) env.videos.ingest(msg(id = i, date = i.toLong(), video = vid(doc = i.toLong(), name = "v$i.mp4"))) }
        val texts get() = messenger.sends.map { it.text }
    }

    // --- export ------------------------------------------------------------------------------

    @Test fun exportSendsOneDocumentWithCount() = runTest {
        val t = T(); t.addVideos(3)
        assertTrue(t.commands.handle(msg(id = 50, text = "/export")))
        val d = t.messenger.documents.single()
        assertEquals("Playlist backup: 3 videos", d.caption)
        assertTrue(d.fileName.matches(Regex("teleplay-backup-\\d{8}-\\d{6}\\.json")))
        assertTrue(t.texts.isEmpty())
    }

    @Test fun exportEmptyLibraryRepliesNothing() = runTest {
        val t = T()
        t.commands.handle(msg(text = "/export"))
        assertEquals(listOf(PlaylistMessages.NOTHING_TO_EXPORT), t.texts)
        assertTrue(t.messenger.documents.isEmpty())
    }

    @Test fun exportFailureRepliesPlainly() = runTest {
        val t = T(); t.addVideos(1)
        t.messenger.failNext("document", com.smaznet.teleplay.domain.remote.RemoteFailure.OTHER)
        t.commands.handle(msg(text = "/export"))
        assertEquals(listOf(PlaylistMessages.SEND_FAILED), t.texts)
    }

    // --- authorization and recognition -------------------------------------------------------

    @Test fun strangersAreNeverAnsweredOrServed() = runTest {
        val t = T(); t.addVideos(2)
        for (c in listOf("/export", "/import", "/help")) assertFalse(t.commands.handle(msg(sender = 7, text = c)))
        assertTrue(t.messenger.calls.isEmpty())
        assertTrue(t.env.gateway.fetchBatches.isEmpty())
    }

    @Test fun notPairedMeansNoCommands() = runTest {
        val t = T(authorized = null)
        assertFalse(t.commands.handle(msg(text = "/help")))
        assertTrue(t.messenger.calls.isEmpty())
    }

    @Test fun otherTextAndCommandsAreNotOurs() = runTest {
        val t = T()
        for (c in listOf("hello", "/start", "/exports", "export", "/exportnow", "")) assertFalse(c, t.commands.handle(msg(text = c)))
        assertFalse(t.commands.handle(msg(text = null, video = vid())))
        assertTrue(t.messenger.calls.isEmpty())
    }

    @Test fun commandIsCaseInsensitiveAndMayHaveArguments() = runTest {
        val t = T()
        assertTrue(t.commands.handle(msg(text = "/HELP now")))
        assertEquals(listOf(PlaylistMessages.HELP), t.texts)
    }

    @Test fun botSuffixMustMatchKnownUsername() = runTest {
        val t = T(username = "MyBot")
        assertFalse(t.commands.handle(msg(text = "/export@otherbot")))
        assertTrue(t.commands.handle(msg(text = "/help@mybot")))
        val t2 = T()
        assertFalse(t2.commands.handle(msg(text = "/help@anybot")))
    }

    @Test fun recogniseIsPure() {
        val t = T()
        assertEquals(PlaylistCommand.IMPORT, t.commands.recognise(msg(text = "/import")))
        assertNull(t.commands.recognise(msg(sender = 9, text = "/import")))
        assertTrue(t.messenger.calls.isEmpty())
    }

    // --- single flight / working notice ------------------------------------------------------

    @Test fun secondCommandWhileRunningGetsBusy() = runTest {
        val t = T()
        val gate = CompletableDeferred<Unit>()
        t.env.gateway.documentBytes[t.doc.id] = BackupCodec.encode(BackupFile(1, 1, 1, emptyList()))
        // block the first import inside fetchDocument-free path: use a slow messenger instead
        val slow = object : com.smaznet.teleplay.data.telegram.BotMessenger by t.messenger {
            override suspend fun sendDocument(fileName: String, mime: String, bytes: ByteArray, caption: String): Result<Int> {
                gate.await(); return t.messenger.sendDocument(fileName, mime, bytes, caption)
            }
        }
        val cmds = PlaylistCommands({ 42 }, PlaylistTransfer(t.env.videos, slow, t.env.gateway, { 1L }), t.env.gateway, slow)
        t.addVideos(1)
        val first = launch { cmds.handle(msg(text = "/export")) }
        runCurrent()
        cmds.handle(msg(text = "/export"))
        assertEquals(listOf(PlaylistMessages.BUSY), t.texts)
        gate.complete(Unit)
        first.join()
        assertEquals(1, t.messenger.documents.size)
        // and the flag is released afterwards
        cmds.handle(msg(text = "/export"))
        assertEquals(2, t.messenger.documents.size)
    }

    @Test fun slowRunSendsWorkingNoticeAfterThreeSeconds() = runTest {
        val t = T(); t.addVideos(1)
        val gate = CompletableDeferred<Unit>()
        val slow = object : com.smaznet.teleplay.data.telegram.BotMessenger by t.messenger {
            override suspend fun sendDocument(fileName: String, mime: String, bytes: ByteArray, caption: String): Result<Int> {
                gate.await(); return t.messenger.sendDocument(fileName, mime, bytes, caption)
            }
        }
        val cmds = PlaylistCommands({ 42 }, PlaylistTransfer(t.env.videos, slow, t.env.gateway, { 1L }), t.env.gateway, slow)
        val job = launch { cmds.handle(msg(text = "/export")) }
        advanceTimeBy(2_900); runCurrent()
        assertTrue(t.texts.isEmpty())
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf(PlaylistMessages.WORKING), t.texts)
        gate.complete(Unit); job.join()
        assertEquals(1, t.messenger.documents.size)
    }

    @Test fun fastRunSendsNoWorkingNotice() = runTest {
        val t = T(); t.addVideos(1)
        t.commands.handle(msg(text = "/export"))
        advanceTimeBy(10_000); runCurrent()
        assertTrue(t.texts.isEmpty())
    }

    // --- import ------------------------------------------------------------------------------

    private fun T.backup(n: Int) {
        val entries = (1..n).map { BackupEntry(it, 1000L + it, 11, "Video $it", 60, 1_600_000_000_000L + it) }
        env.gateway.documentBytes[doc.id] = BackupCodec.encode(BackupFile(1, 1, 1, entries))
        for (e in entries) env.gateway.messagesById[e.messageId] = msg(id = e.messageId, sender = 11, video = vid(doc = e.documentId))
    }

    @Test fun importFromCaptionUsesAttachedDocument() = runTest {
        val t = T(); t.backup(3)
        t.commands.handle(msg(id = 77, text = "/import", document = t.doc))
        assertEquals(3, t.env.videoStore.all.size)
        assertEquals("Import finished\nRestored: 3\nAlready in library: 0\nSkipped: 0", t.texts.single())
    }

    @Test fun importFromReplyResolvesTheRepliedMessage() = runTest {
        val t = T(); t.backup(2)
        t.env.gateway.docsById[60] = t.doc
        t.commands.handle(msg(id = 78, text = "/import", replyTo = 60))
        assertEquals(2, t.env.videoStore.all.size)
        assertTrue(t.texts.single().startsWith("Import finished"))
    }

    @Test fun importWithoutFileShowsUsageAndChangesNothing() = runTest {
        val t = T(); t.backup(2)
        t.commands.handle(msg(text = "/import"))
        assertEquals(listOf(PlaylistMessages.USAGE_IMPORT), t.texts)
        assertTrue(t.env.videoStore.all.isEmpty())
    }

    @Test fun replyToAMessageWithoutDocumentShowsUsage() = runTest {
        val t = T(); t.backup(2)
        t.commands.handle(msg(text = "/import", replyTo = 61))
        assertEquals(listOf(PlaylistMessages.USAGE_IMPORT), t.texts)
        assertTrue(t.env.videoStore.all.isEmpty())
    }

    @Test fun invalidFileSendsRejectionAndLeavesLibrary() = runTest {
        val t = T()
        t.env.gateway.documentBytes[t.doc.id] = "{\"hello\":1}".toByteArray()
        t.commands.handle(msg(text = "/import", document = t.doc))
        assertEquals(listOf(PlaylistMessages.NOT_BACKUP), t.texts)
        assertTrue(t.env.videoStore.all.isEmpty())
    }

    @Test fun unreadableFileSendsUnavailable() = runTest {
        val t = T()
        t.commands.handle(msg(text = "/import", document = t.doc))
        assertEquals(listOf(PlaylistMessages.FILE_UNAVAILABLE), t.texts)
    }

    @Test fun strangerImportNeverDownloads() = runTest {
        val t = T(); t.backup(2)
        assertFalse(t.commands.handle(msg(sender = 7, text = "/import", document = t.doc)))
        assertTrue(t.messenger.calls.isEmpty())
        assertTrue(t.env.videoStore.all.isEmpty())
    }

    @Test fun unexpectedFailureRepliesAndReleasesTheLock() = runTest {
        val t = T(); t.backup(2)
        t.env.gateway.fetchFailure = TelegramError.Offline()
        t.commands.handle(msg(text = "/import", document = t.doc))
        assertEquals(listOf(PlaylistMessages.IMPORT_FAILED), t.texts)
        t.commands.handle(msg(text = "/import", document = t.doc))
        assertEquals(2, t.env.videoStore.all.size)
    }

    // --- help and menu -----------------------------------------------------------------------

    @Test fun helpMentionsBothCommandsAndReplyUsage() = runTest {
        val t = T()
        t.commands.handle(msg(text = "/help"))
        val h = t.texts.single()
        assertTrue(h.contains("/export")); assertTrue(h.contains("/import")); assertTrue(h.contains("/help"))
        assertTrue(h.contains("reply", ignoreCase = true))
    }

    @Test fun theBackupRouterLeavesProxiesToItsOwnCommand() = runTest {
        val t = T()
        assertNull(t.commands.recognise(msg(text = "/proxies tg://socks?server=1.1.1.1&port=1")))
        assertFalse(t.commands.handle(msg(text = "/proxies")))
        assertTrue(t.messenger.calls.isEmpty())
    }

    @Test fun helpMentionsProxies() {
        assertTrue(PlaylistMessages.HELP.contains("/proxies"))
    }

    @Test fun menuRegistersExactlyTheFourCommands() = runTest {
        val t = T()
        val reg = CommandMenuRegistrar(t.messenger)
        reg.register()
        assertEquals(CommandMenuRegistrar.COMMANDS, t.messenger.setCommandCalls.single().commands)
        assertEquals(listOf("export", "import", "help", "proxies"), t.messenger.setCommandCalls.single().commands.map { it.first })
        assertTrue(t.messenger.setCommandCalls.single().commands.all { it.second.isNotBlank() })
        reg.register()
        assertEquals(2, t.messenger.setCommandCalls.size)
    }

    @Test fun menuFailureIsSwallowed() = runTest {
        val t = T()
        t.messenger.failNext("commands", com.smaznet.teleplay.domain.remote.RemoteFailure.OFFLINE)
        CommandMenuRegistrar(t.messenger).register() // must not throw
        assertTrue(t.messenger.setCommandCalls.isEmpty())
    }
}
