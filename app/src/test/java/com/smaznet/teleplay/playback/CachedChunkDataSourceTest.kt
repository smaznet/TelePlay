package com.smaznet.teleplay.playback

import com.smaznet.teleplay.data.cache.ChunkCache
import com.smaznet.teleplay.data.cache.ChunkMath
import com.smaznet.teleplay.data.cache.InMemoryCacheIndex
import com.smaznet.teleplay.domain.Availability
import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.domain.Video
import com.smaznet.teleplay.fakes.FakeTelegramGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

/** Tests the Android-free [ChunkReader] that backs [CachedChunkDataSource]. */
class CachedChunkDataSourceTest {
    private val size = ChunkMath.CHUNK_SIZE * 12L + 777
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gateway = FakeTelegramGateway()
    private lateinit var root: java.io.File
    private lateinit var cache: ChunkCache

    private fun video(ref: Byte = 1) = Video(
        1, 1, 1, 42, 1, byteArrayOf(ref), 2, "t", 10, size, "video/mp4", 1, 1, null, 0, Availability.PLAYABLE,
    )

    private fun byteAt(pos: Long) = (pos * 31 % 251).toByte()
    private fun content(video: Video, idx: Int): ByteArray {
        val off = ChunkMath.chunkOffset(idx)
        return ByteArray(ChunkMath.chunkLength(video.sizeBytes, idx)) { byteAt(off + it) }
    }

    @Before fun setUp() {
        root = Files.createTempDirectory("ds").toFile()
        cache = ChunkCache(root.resolve("c"), InMemoryCacheIndex())
        gateway.chunkProvider = { v, i -> content(v, i) }
    }

    @After fun tearDown() { scope.cancel(); root.deleteRecursively() }

    private fun reader(v: Video = video()) = ChunkReader(AtomicReference(v), cache, gateway, scope)

    private fun ChunkReader.readFully(n: Int): ByteArray {
        val out = ByteArray(n); var got = 0
        while (got < n) { val r = read(out, got, n - got); if (r < 0) break; got += r }
        return out.copyOf(got)
    }

    @Test fun readsAcrossChunkBoundaries() {
        val r = reader()
        val start = ChunkMath.CHUNK_SIZE - 100L
        assertEquals(size - start, r.open(start))
        val data = r.readFully(300_000 + ChunkMath.CHUNK_SIZE)
        assertArrayEquals(ByteArray(data.size) { byteAt(start + it) }, data)
        r.close()
    }

    @Test fun readsToEndThenEof() {
        val r = reader()
        val start = size - 1000
        r.open(start)
        assertEquals(1000, r.readFully(5000).size)
        assertEquals(-1, r.read(ByteArray(10), 0, 10))
    }

    @Test fun readAheadFetchesFourChunks() {
        val r = reader()
        r.open(0)
        r.readFully(10)
        Thread.sleep(300)
        val requested = synchronized(gateway.chunkRequests) { gateway.chunkRequests.map { it.second }.toSet() }
        assertEquals(setOf(0, 1, 2, 3, 4), requested)
    }

    @Test fun seekPrioritizesNewChunkAndDropsOldReadAhead() {
        val r = reader()
        r.open(0)
        r.readFully(10)
        Thread.sleep(300)
        synchronized(gateway.chunkRequests) { gateway.chunkRequests.clear() }
        val pos = ChunkMath.CHUNK_SIZE * 9L + 5
        r.open(pos)
        assertArrayEquals(ByteArray(100) { byteAt(pos + it) }, r.readFully(100))
        Thread.sleep(300)
        val requested = synchronized(gateway.chunkRequests) { gateway.chunkRequests.map { it.second } }
        assertTrue("new chunk fetched, requested=$requested", 9 in requested)
        assertTrue(requested.all { it >= 9 })
        r.close()
    }

    @Test fun expiredReferenceRefreshesOnceAndRetries() {
        var refreshCount = 0
        val fresh = video(ref = 2)
        gateway.refreshed = { refreshCount++; fresh }
        gateway.chunkProvider = { v, i ->
            if (v.fileReference[0] == 1.toByte()) throw TelegramError.FileReferenceExpired()
            content(v, i)
        }
        val r = reader()
        r.open(0)
        assertArrayEquals(content(video(), 0).copyOf(100), r.readFully(100))
        Thread.sleep(300)
        assertEquals(1, refreshCount)
    }

    @Test fun failuresMapToIOException() {
        gateway.chunkProvider = { _, _ -> throw TelegramError.Offline() }
        val r = reader()
        try {
            r.open(0); r.readFully(10)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is TelegramError.Offline })
        }
    }

    @Test fun secondExpiryFailsAsIOException() {
        gateway.chunkProvider = { _, _ -> throw TelegramError.FileReferenceExpired() }
        val r = reader()
        try { r.open(0); r.readFully(10); fail() } catch (e: IOException) { /* ok */ }
    }

    @Test fun cachedChunksAreNotRefetched() {
        val r = reader()
        r.open(0); r.readFully(100); Thread.sleep(300); r.close()
        synchronized(gateway.chunkRequests) { gateway.chunkRequests.clear() }
        val r2 = reader()
        r2.open(0); r2.readFully(100); Thread.sleep(200)
        assertTrue(synchronized(gateway.chunkRequests) { gateway.chunkRequests.none { it.second <= 4 } })
    }
}
