package com.smaznet.teleplay.domain.playlist

import com.smaznet.teleplay.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import com.smaznet.teleplay.fakes.FakeBotMessenger

class PlaylistTransferTest {
    private class T(authorized: Long = 42) {
        val env = Env(authorized = authorized)
        val messenger = FakeBotMessenger()
        var botId = 1L
        val transfer = PlaylistTransfer(env.videos, messenger, env.gateway, { botId }, { 1_793_700_000_000 })
        val doc = IncomingDocument(900, 1, byteArrayOf(1), 2, "teleplay-backup.json", "application/json", 100)

        fun entries(n: Int, from: Int = 1) = (from until from + n).map {
            BackupEntry(it, 1000L + it, 11, "Video $it", 60, 1_600_000_000_000L + it)
        }

        /** Registers the backup file and makes [reachable] of its entries fetchable. */
        fun backup(entries: List<BackupEntry>, botUserId: Long = 1, reachable: (BackupEntry) -> Boolean = { true }) {
            env.gateway.documentBytes[doc.id] = BackupCodec.encode(BackupFile(1, 1, botUserId, entries))
            for (e in entries) if (reachable(e)) env.gateway.messagesById[e.messageId] = msg(id = e.messageId, sender = 11, video = vid(doc = e.documentId, name = "v${e.messageId}.mp4"))
        }

        fun report(o: ImportOutcome) = (o as ImportOutcome.Done).report
    }

    // --- export ------------------------------------------------------------------------------

    @Test fun exportEmptyLibrarySendsNothing() = runTest {
        val t = T()
        assertTrue(t.transfer.export() is ExportResult.Empty)
        assertTrue(t.messenger.documents.isEmpty())
    }

    @Test fun exportSendsDecodableBackupWithProgress() = runTest {
        val t = T()
        t.env.videos.ingest(msg(id = 1, date = 1000, video = vid(doc = 1, name = "a.mp4")))
        t.env.videos.ingest(msg(id = 2, date = 2000, video = vid(doc = 2, name = "b.mp4")))
        t.env.videos.saveProgress(t.env.videoStore.all.first { it.documentId == 1L }.id, 30_000, 60_000)
        val r = t.transfer.export() as ExportResult.Sent
        assertEquals(2, r.count)
        val d = t.messenger.documents.single()
        assertEquals("Playlist backup: 2 videos", d.caption)
        assertEquals("application/json", d.mime)
        assertEquals(BackupCodec.fileName(1_793_700_000_000), d.fileName)
        val f = (BackupCodec.decode(d.bytes) as DecodeResult.Ok).file
        assertEquals(listOf("b.mp4", "a.mp4"), f.videos.map { it.title })
        assertNull(f.videos[0].positionMs)
        assertEquals(30_000L, f.videos[1].positionMs)
        assertEquals(1L, f.botUserId)
    }

    @Test fun exportCaptionSingular() = runTest {
        val t = T()
        t.env.videos.ingest(msg(id = 1, video = vid(doc = 1)))
        t.transfer.export()
        assertEquals("Playlist backup: 1 video", t.messenger.documents.single().caption)
    }

    @Test fun exportReportsSendFailure() = runTest {
        val t = T()
        t.env.videos.ingest(msg(id = 1, video = vid(doc = 1)))
        t.messenger.failNext("document", com.smaznet.teleplay.domain.remote.RemoteFailure.OTHER)
        assertTrue(t.transfer.export() is ExportResult.Failed)
    }

    // --- import ------------------------------------------------------------------------------

    @Test fun importRestoresAll() = runTest {
        val t = T()
        t.backup(t.entries(3))
        val r = t.report(t.transfer.import(5, t.doc))
        assertEquals(3, r.restored); assertEquals(0, r.skipped); assertFalse(r.botMismatch); assertFalse(r.stoppedEarly)
        assertEquals(3, t.env.videoStore.all.size)
    }

    @Test fun twoHundredFiftyEntriesUseThreeBatches() = runTest {
        val t = T()
        t.backup(t.entries(250))
        val r = t.report(t.transfer.import(5, t.doc))
        assertEquals(listOf(100, 100, 50), t.env.gateway.fetchBatches.map { it.size })
        assertEquals(250, r.restored)
    }

    @Test fun repeatImportAddsNothing() = runTest {
        val t = T()
        t.backup(t.entries(5))
        t.transfer.import(5, t.doc)
        val r = t.report(t.transfer.import(5, t.doc))
        assertEquals(0, r.restored); assertEquals(5, r.alreadyPresent)
        assertEquals(5, t.env.videoStore.all.size)
    }

    @Test fun missingMessagesAreNotFoundAndOthersStillRestored() = runTest {
        val t = T()
        t.backup(t.entries(4)) { it.messageId != 2 && it.messageId != 3 }
        val r = t.report(t.transfer.import(5, t.doc))
        assertEquals(2, r.restored); assertEquals(2, r.notFound)
    }

    @Test fun differentDocumentIsChanged() = runTest {
        val t = T()
        val e = t.entries(2)
        t.backup(e)
        t.env.gateway.messagesById[1] = msg(id = 1, sender = 11, video = vid(doc = 424242))
        val r = t.report(t.transfer.import(5, t.doc))
        assertEquals(1, r.restored); assertEquals(1, r.changed)
    }

    @Test fun invalidEntriesCountAsChanged() = runTest {
        val t = T()
        t.env.gateway.documentBytes[t.doc.id] =
            """{"app":"teleplay","version":1,"videos":[{"messageId":1,"documentId":1001},{"messageId":2,"documentId":1002},{"messageId":0,"documentId":3}]}""".toByteArray()
        t.env.gateway.messagesById[1] = msg(id = 1, sender = 11, video = vid(doc = 1001))
        t.env.gateway.messagesById[2] = msg(id = 2, sender = 11, video = vid(doc = 1002))
        val r = t.report(t.transfer.import(5, t.doc))
        assertEquals(2, r.restored); assertEquals(1, r.changed)
    }

    @Test fun rateLimitStopsEarlyKeepingEarlierBatches() = runTest {
        val t = T()
        t.backup(t.entries(250))
        t.env.gateway.fetchFailure = TelegramError.RateLimited(30)
        t.env.gateway.fetchFailureAt = 2
        val r = t.report(t.transfer.import(5, t.doc))
        assertTrue(r.stoppedEarly)
        assertEquals(100, r.restored)
        assertEquals(100, t.env.videoStore.all.size)
        // running again completes it
        val again = t.report(t.transfer.import(5, t.doc))
        assertFalse(again.stoppedEarly)
        assertEquals(150, again.restored); assertEquals(100, again.alreadyPresent)
    }

    @Test fun otherBatchFailureCountsUnreachableAndContinues() = runTest {
        val t = T()
        t.backup(t.entries(250))
        t.env.gateway.fetchFailure = IllegalStateException("boom")
        t.env.gateway.fetchFailureAt = 1
        val r = t.report(t.transfer.import(5, t.doc))
        assertEquals(100, r.unreachable); assertEquals(150, r.restored)
    }

    @Test fun offlineAbortsTheImport() = runTest {
        val t = T()
        t.backup(t.entries(3))
        t.env.gateway.fetchFailure = TelegramError.Offline()
        try { t.transfer.import(5, t.doc); fail() } catch (_: TelegramError.Offline) {}
    }

    @Test fun differentBotAddsMismatchFlag() = runTest {
        val t = T()
        t.backup(t.entries(1), botUserId = 77)
        assertTrue(t.report(t.transfer.import(5, t.doc)).botMismatch)
    }

    @Test fun restoredVideosProduceNoAddedNotices() = runTest {
        val t = T()
        t.backup(t.entries(3))
        t.transfer.import(5, t.doc)
        assertTrue(t.messenger.calls.isEmpty()) // the transfer itself sends nothing; the router sends the report
    }

    @Test fun invalidFileIsRejectedAndLibraryUntouched() = runTest {
        val t = T()
        t.env.gateway.documentBytes[t.doc.id] = "not json".toByteArray()
        val o = t.transfer.import(5, t.doc)
        assertEquals(RejectReason.NOT_BACKUP, (o as ImportOutcome.Rejected).reason)
        assertTrue(t.env.videoStore.all.isEmpty())
        assertTrue(t.env.gateway.fetchBatches.isEmpty())
    }

    @Test fun oversizeDocumentIsRejectedBeforeDownload() = runTest {
        val t = T()
        val big = IncomingDocument(901, 1, byteArrayOf(), 2, "x.json", "application/json", BackupCodec.MAX_BYTES + 1L)
        assertEquals(RejectReason.TOO_LARGE, ((t.transfer.import(5, big)) as ImportOutcome.Rejected).reason)
    }

    @Test fun unreadableFileReportsUnavailable() = runTest {
        val t = T()
        assertTrue(t.transfer.import(5, t.doc) is ImportOutcome.FileUnavailable)
    }

    @Test fun importedProgressResumes() = runTest {
        val t = T()
        val e = t.entries(1)[0].copy(positionMs = 30_000, durationMs = 60_000, completed = false, progressUpdatedAt = 5_000)
        t.backup(listOf(e))
        t.transfer.import(5, t.doc)
        assertEquals(30_000L, t.env.videos.resumePositionMs(t.env.videoStore.all.single().id))
    }
}
