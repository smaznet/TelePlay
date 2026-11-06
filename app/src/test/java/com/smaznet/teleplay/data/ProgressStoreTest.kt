package com.smaznet.teleplay.data

import com.smaznet.teleplay.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProgressStoreTest {
    private val e = Env(authorized = 42)
    private fun add(n: Int) = e.videos.ingest(msg(id = n, date = n.toLong(), video = vid(doc = n.toLong())))!!

    @Test fun saveOnEveryTick() = runTest {
        val id = add(1)
        for (i in 1..3) { e.time += 5000; e.videos.saveProgress(id, i * 5000L, 100_000) }
        val p = e.videos.progress(id).first()!!
        assertEquals(15_000, p.positionMs)
        assertEquals(e.time, p.updatedAt)
        assertFalse(p.completed)
    }

    @Test fun completedAt95Percent() {
        val id = add(1)
        e.videos.saveProgress(id, 94_999, 100_000)
        assertFalse(e.progressStore.get(id)!!.completed)
        e.videos.saveProgress(id, 95_000, 100_000)
        assertTrue(e.progressStore.get(id)!!.completed)
    }

    @Test fun resumeOnlyIfNotCompletedAndOver10s() {
        val id = add(1)
        assertNull(e.videos.resumePositionMs(id))
        e.videos.saveProgress(id, 10_000, 100_000)
        assertNull(e.videos.resumePositionMs(id))
        e.videos.saveProgress(id, 10_001, 100_000)
        assertEquals(10_001L, e.videos.resumePositionMs(id))
        e.videos.saveProgress(id, 96_000, 100_000)
        assertNull(e.videos.resumePositionMs(id))
    }

    @Test fun continueWatchingEmptyWhenNone() = runTest {
        add(1)
        assertTrue(e.videos.continueWatching().first().isEmpty())
    }

    @Test fun continueWatchingFiltersAndOrders() = runTest {
        val a = add(1); val b = add(2); val c = add(3); add(4)
        e.time = 100; e.videos.saveProgress(a, 20_000, 100_000)
        e.time = 300; e.videos.saveProgress(b, 20_000, 100_000)
        e.time = 200; e.videos.saveProgress(c, 99_000, 100_000) // completed
        assertEquals(listOf(b, a), e.videos.continueWatching().first().map { it.id })
    }

    @Test fun startOverOverwritesPositionWithZero() = runTest {
        val id = add(1)
        e.videos.saveProgress(id, 50_000, 100_000)
        e.videos.startOver(id)
        val p = e.progressStore.get(id)!!
        assertEquals(0L, p.positionMs)
        assertFalse(p.completed)
        assertNull(e.videos.resumePositionMs(id))
        assertTrue(e.videos.continueWatching().first().isEmpty())
    }

    @Test fun startOverCompletedVideoResetsCompleted() {
        val id = add(1)
        e.videos.saveProgress(id, 99_000, 100_000)
        e.videos.startOver(id)
        assertFalse(e.progressStore.get(id)!!.completed)
    }
}
