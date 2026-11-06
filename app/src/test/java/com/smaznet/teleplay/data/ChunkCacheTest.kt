package com.smaznet.teleplay.data

import com.smaznet.teleplay.data.cache.ChunkCache
import com.smaznet.teleplay.data.cache.InMemoryCacheIndex
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChunkCacheTest {
    private lateinit var dir: File
    private var now = 0L
    private val index = InMemoryCacheIndex()

    @Before fun setUp() { dir = Files.createTempDirectory("cc").toFile().resolve("cache") }
    @After fun tearDown() { dir.parentFile.deleteRecursively() }

    private fun cache(limit: Long = Long.MAX_VALUE) = ChunkCache(dir, index, limit) { ++now }
    private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it + seed).toByte() }

    @Test fun putGetRoundTripAndSparse() = runTest {
        val c = cache()
        val a = bytes(524_288, 1)
        val b = bytes(1000, 2)
        c.put(7, 3, a)
        c.put(7, 5, b)
        assertArrayEquals(a, c.get(7, 3))
        assertArrayEquals(b, c.get(7, 5))
        assertNull(c.get(7, 4))
        assertEquals(524_288L + 1000, c.usage().usedBytes)
    }

    @Test fun bitmapPersistedAndReloaded() = runTest {
        val a = bytes(524_288, 9)
        cache().put(1, 2, a)
        val reloaded = cache()
        assertArrayEquals(a, reloaded.get(1, 2))
        assertNull(reloaded.get(1, 0))
        assertTrue(reloaded.has(1, 2))
    }

    @Test fun evictsLeastRecentlyWatchedFirst() = runTest {
        val c = cache(limit = 3L * 524_288)
        c.put(1, 0, bytes(524_288, 1))
        c.put(2, 0, bytes(524_288, 2))
        c.put(3, 0, bytes(524_288, 3))
        c.get(1, 0) // 1 is now most recent; 2 is oldest
        c.put(4, 0, bytes(524_288, 4))
        assertNull(c.get(2, 0))
        assertNotNull(c.get(1, 0))
        assertNotNull(c.get(3, 0))
        assertNotNull(c.get(4, 0))
        assertTrue(c.usage().usedBytes <= 3L * 524_288)
        assertFalse(File(dir, "2.part").exists())
    }

    @Test fun entryInUseIsNeverEvicted() = runTest {
        val c = cache(limit = 524_288)
        c.put(1, 0, bytes(524_288, 1))
        c.setInUse(1)
        c.put(2, 0, bytes(524_288, 2))
        assertNotNull(c.get(1, 0))
        assertNull(c.get(2, 0))
        c.trim(0)
        assertNotNull(c.get(1, 0))
    }

    @Test fun trimLimitsTotal() = runTest {
        val c = cache()
        for (i in 1L..4L) c.put(i, 0, bytes(524_288, i.toInt()))
        c.trim(2L * 524_288)
        assertEquals(2L * 524_288, c.usage().usedBytes)
        assertNull(c.get(1, 0)); assertNull(c.get(2, 0))
    }

    @Test fun clearAndWipeRemoveFiles() = runTest {
        val c = cache()
        c.put(1, 0, bytes(10, 1)); c.put(2, 0, bytes(10, 2))
        c.clear()
        assertEquals(0L, c.usage().usedBytes)
        assertEquals(0, dir.listFiles()?.size ?: 0)
        c.put(3, 0, bytes(10, 3))
        c.wipe()
        assertFalse(dir.exists())
        assertEquals(0L, c.usage().usedBytes)
        assertNull(c.get(3, 0))
    }

    @Test fun defaultLimitIsTwoGigabytes() {
        assertEquals(2L * 1024 * 1024 * 1024, ChunkCache(dir, index).usage().limitBytes)
    }
}
