package com.smaznet.teleplay.data

import com.smaznet.teleplay.data.repo.IngestResult
import com.smaznet.teleplay.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VideoRepositoryTest {
    private fun env() = Env(authorized = 42)

    @Test fun dropsNonAuthorizedSender() {
        val e = env()
        assertNull(e.videos.ingest(msg(sender = 7, video = vid())))
        assertTrue(e.videoStore.all.isEmpty())
    }

    @Test fun dropsWhenNotPaired() {
        val e = Env(authorized = null)
        assertNull(e.videos.ingest(msg(video = vid())))
    }

    @Test fun dropsNonVideoDocuments() {
        val e = env()
        assertNull(e.videos.ingest(msg(text = "hi")))
        assertNull(e.videos.ingest(msg(video = vid(mime = "application/pdf", w = 0, h = 0))))
        assertTrue(e.videoStore.all.isEmpty())
    }

    @Test fun acceptsVideoMimeOrVideoAttribute() {
        val e = env()
        assertNotNull(e.videos.ingest(msg(id = 1, video = vid(doc = 1, mime = "video/x-matroska", w = 0, h = 0))))
        assertNotNull(e.videos.ingest(msg(id = 2, video = vid(doc = 2, mime = "application/octet-stream", w = 640, h = 360))))
        assertEquals(2, e.videoStore.all.size)
        assertEquals(Availability.PLAYABLE, e.videoStore.all[0].availability)
    }

    @Test fun dedupesByDocumentIdKeepingNewest() = runTest {
        val e = env()
        val id1 = e.videos.ingest(msg(id = 1, date = 1000, video = vid(doc = 5, name = "old")))!!
        val id2 = e.videos.ingest(msg(id = 9, date = 2000, video = vid(doc = 5, name = "new")))!!
        assertEquals(id1, id2)
        // an older message arriving later does not replace it
        e.videos.ingest(msg(id = 3, date = 1500, video = vid(doc = 5, name = "mid")))
        val list = e.videos.videos.first()
        assertEquals(1, list.size)
        assertEquals("new", list[0].title)
        assertEquals(9, list[0].messageId)
    }

    @Test fun orderedNewestFirst() = runTest {
        val e = env()
        e.videos.ingest(msg(id = 1, date = 100, video = vid(doc = 1)))
        e.videos.ingest(msg(id = 2, date = 300, video = vid(doc = 2)))
        e.videos.ingest(msg(id = 3, date = 200, video = vid(doc = 3)))
        assertEquals(listOf(2L, 3L, 1L), e.videos.videos.first().map { it.documentId })
    }

    @Test fun titleIsFileNameThenCaptionThenDate() {
        val e = env()
        e.videos.ingest(msg(id = 1, text = "  Caption ", video = vid(doc = 1, name = " f.mp4 ")))
        e.videos.ingest(msg(id = 2, text = "  Caption ", video = vid(doc = 2, name = null)))
        e.videos.ingest(msg(id = 3, date = 1_700_000_000, text = " ", video = vid(doc = 3, name = null)))
        val t = e.videoStore.all.associate { it.documentId to it.title }
        assertEquals("f.mp4", t[1])
        assertEquals("Caption", t[2])
        assertEquals("2023-11-14 22:13", t[3])
    }

    @Test fun markUnavailable() {
        val e = env()
        e.videos.ingest(msg(video = vid(doc = 5)))
        e.videos.markUnavailable(5)
        assertEquals(Availability.REMOTE_GONE, e.videoStore.all[0].availability)
    }

    @Test fun ingestResultDistinguishesAddedDuplicateAndRedelivery() = runTest {
        val e = env()
        assertTrue(e.videos.ingestResult(msg(id = 1, date = 100, video = vid(doc = 5))) is IngestResult.Added)
        assertTrue(e.videos.ingestResult(msg(id = 1, date = 100, video = vid(doc = 5))) is IngestResult.Redelivered)
        assertTrue(e.videos.ingestResult(msg(id = 2, date = 200, video = vid(doc = 5))) is IngestResult.Duplicate)
        assertTrue(e.videos.ingestResult(msg(id = 3, date = 300, video = vid(doc = 6))) is IngestResult.Added)
        assertTrue(e.videos.ingestResult(msg(id = 4, text = "hi")) is IngestResult.Ignored)
        assertTrue(e.videos.ingestResult(msg(id = 5, sender = 7, video = vid(doc = 9))) is IngestResult.Ignored)
    }
}
