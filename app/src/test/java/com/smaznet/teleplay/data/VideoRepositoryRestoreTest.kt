package com.smaznet.teleplay.data

import com.smaznet.teleplay.data.repo.RestoreResult
import com.smaznet.teleplay.domain.*
import com.smaznet.teleplay.domain.playlist.BackupEntry
import org.junit.Assert.*
import org.junit.Test

class VideoRepositoryRestoreTest {
    private fun entry(doc: Long = 100, messageId: Int = 5, withProgress: Boolean = false, updatedAt: Long = 5_000) = BackupEntry(
        messageId = messageId, documentId = doc, chatUserId = 11, title = "Backup title", durationSec = 60, receivedAt = 1_600_000_000_000,
        positionMs = if (withProgress) 30_000 else null, durationMs = if (withProgress) 60_000 else null,
        completed = if (withProgress) false else null, progressUpdatedAt = if (withProgress) updatedAt else null,
    )

    @Test fun restoreInsertsWithFetchedMetadataAndBackupDate() {
        val e = Env(authorized = 42)
        val r = e.videos.restore(entry(), msg(id = 5, sender = 11, video = vid(doc = 100, name = "real.mp4")))
        assertEquals(RestoreResult.RESTORED, r)
        val v = e.videoStore.all.single()
        assertEquals("real.mp4", v.title)
        assertEquals(1_600_000_000_000, v.receivedAt)
        assertEquals(11L, v.chatUserId) // the old chat, not the currently authorized user
        assertEquals(Availability.PLAYABLE, v.availability)
        assertEquals(5, v.messageId)
    }

    @Test fun titleFallsBackToBackup() {
        val e = Env(authorized = 42)
        e.videos.restore(entry(), msg(id = 5, sender = 11, video = vid(doc = 100, name = null)))
        assertEquals("Backup title", e.videoStore.all.single().title)
    }

    @Test fun secondRestoreIsAlreadyPresentAndAddsNothing() {
        val e = Env(authorized = 42)
        val m = msg(id = 5, sender = 11, video = vid(doc = 100))
        assertEquals(RestoreResult.RESTORED, e.videos.restore(entry(), m))
        assertEquals(RestoreResult.ALREADY_PRESENT, e.videos.restore(entry(), m))
        assertEquals(1, e.videoStore.all.size)
    }

    @Test fun existingLibraryEntryIsNotDuplicated() {
        val e = Env(authorized = 42)
        e.videos.ingest(msg(id = 9, sender = 42, video = vid(doc = 100)))
        assertEquals(RestoreResult.ALREADY_PRESENT, e.videos.restore(entry(), msg(id = 5, sender = 11, video = vid(doc = 100))))
        assertEquals(1, e.videoStore.all.size)
    }

    @Test fun documentMismatchIsChanged() {
        val e = Env(authorized = 42)
        assertEquals(RestoreResult.CHANGED, e.videos.restore(entry(doc = 100), msg(id = 5, sender = 11, video = vid(doc = 999))))
        assertEquals(RestoreResult.CHANGED, e.videos.restore(entry(), msg(id = 5, sender = 11, text = "no video")))
        assertTrue(e.videoStore.all.isEmpty())
    }

    @Test fun progressIsWrittenWhenNoneLocally() {
        val e = Env(authorized = 42)
        e.videos.restore(entry(withProgress = true), msg(id = 5, sender = 11, video = vid(doc = 100)))
        val id = e.videoStore.all.single().id
        val p = e.progressStore.get(id)!!
        assertEquals(30_000, p.positionMs)
        assertEquals(60_000, p.durationMs)
        assertEquals(5_000, p.updatedAt)
    }

    @Test fun newerLocalProgressIsKept() {
        val e = Env(authorized = 42)
        e.videos.restore(entry(), msg(id = 5, sender = 11, video = vid(doc = 100)))
        val id = e.videoStore.all.single().id
        e.progressStore.put(PlaybackProgress(id, 50_000, 60_000, false, 9_000))
        e.videos.restore(entry(withProgress = true, updatedAt = 5_000), msg(id = 5, sender = 11, video = vid(doc = 100)))
        assertEquals(50_000, e.progressStore.get(id)!!.positionMs)
    }

    @Test fun olderLocalProgressIsReplaced() {
        val e = Env(authorized = 42)
        e.videos.restore(entry(), msg(id = 5, sender = 11, video = vid(doc = 100)))
        val id = e.videoStore.all.single().id
        e.progressStore.put(PlaybackProgress(id, 1_000, 60_000, false, 1_000))
        e.videos.restore(entry(withProgress = true, updatedAt = 5_000), msg(id = 5, sender = 11, video = vid(doc = 100)))
        assertEquals(30_000, e.progressStore.get(id)!!.positionMs)
    }

    @Test fun snapshotPairsVideosWithProgressNewestFirst() {
        val e = Env(authorized = 42)
        e.videos.ingest(msg(id = 1, date = 1000, video = vid(doc = 1)))
        e.videos.ingest(msg(id = 2, date = 2000, video = vid(doc = 2)))
        val first = e.videoStore.all.first { it.documentId == 1L }
        e.progressStore.put(PlaybackProgress(first.id, 10, 100, false, 7))
        val snap = e.videos.snapshot()
        assertEquals(listOf(2L, 1L), snap.map { it.first.documentId })
        assertNull(snap[0].second)
        assertEquals(10L, snap[1].second!!.positionMs)
    }
}
