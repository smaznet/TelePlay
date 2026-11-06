package com.smaznet.teleplay.playback

import com.smaznet.teleplay.data.cache.ChunkCache
import com.smaznet.teleplay.data.cache.ChunkMath
import com.smaznet.teleplay.data.telegram.TelegramGateway
import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.domain.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Android-free core of [CachedChunkDataSource]: blocking sequential reads over cached chunks, with
 * pipelined read-ahead. All failures surface as [IOException].
 */
class ChunkReader(
    private val videoRef: AtomicReference<Video>,
    private val cache: ChunkCache,
    private val gateway: TelegramGateway,
    private val scope: CoroutineScope,
    private val readAhead: Int = READ_AHEAD_CHUNKS,
) {
    private val inFlight = HashMap<Int, Deferred<ByteArray>>()
    private val refreshLock = Mutex()
    private var position = 0L
    private var end = 0L
    private var current: ByteArray? = null
    private var currentIndex = -1
    private var opened = false

    private val size get() = videoRef.get().sizeBytes

    /** Positions the reader; returns the number of readable bytes. Starts fetching the first chunk. */
    @Synchronized
    fun open(startPosition: Long, length: Long = -1): Long {
        if (startPosition < 0 || startPosition > size) throw IOException("position out of range")
        position = startPosition
        end = if (length < 0) size else minOf(size, startPosition + length)
        current = null
        currentIndex = -1
        opened = true
        val first = ChunkMath.chunkIndexOf(startPosition)
        // Seek: drop read-ahead that is no longer useful, new position goes first.
        val keep = first..(first + readAhead)
        inFlight.keys.filter { it !in keep }.forEach { inFlight.remove(it)?.cancel() }
        if (position < end) {
            chunk(first)
            scheduleReadAhead(first)
        }
        return end - startPosition
    }

    /** Returns bytes read, or -1 at the end of the range. */
    @Synchronized
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(opened) { "not open" }
        if (length == 0) return 0
        if (position >= end) return -1
        val index = ChunkMath.chunkIndexOf(position)
        val data = if (index == currentIndex) current!! else load(index)
        val inChunk = (position - ChunkMath.chunkOffset(index)).toInt()
        val n = minOf(length.toLong(), (data.size - inChunk).toLong(), end - position).toInt()
        if (n <= 0) throw IOException("short chunk")
        System.arraycopy(data, inChunk, buffer, offset, n)
        position += n
        return n
    }

    @Synchronized
    fun close() {
        opened = false
        current = null
        currentIndex = -1
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }

    private fun load(index: Int): ByteArray {
        val data = await(chunk(index))
        inFlight.keys.filter { it < index }.forEach { inFlight.remove(it)?.cancel() }
        current = data
        currentIndex = index
        scheduleReadAhead(index)
        return data
    }

    private fun scheduleReadAhead(index: Int) {
        val count = ChunkMath.chunkCount(size)
        for (i in index + 1..index + readAhead) {
            if (i >= count) break
            if (inFlight[i] == null && !cache.has(videoRef.get().documentId, i)) chunk(i)
        }
    }

    private fun chunk(index: Int): Deferred<ByteArray> =
        inFlight[index]?.takeIf { !it.isCancelled } ?: scope.async { fetch(index) }.also { inFlight[index] = it }

    private fun await(d: Deferred<ByteArray>): ByteArray = try {
        runBlocking { d.await() }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException()
    } catch (e: CancellationException) {
        throw IOException("chunk request cancelled", e.cause ?: e)
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("chunk request failed", e)
    }

    private suspend fun fetch(index: Int): ByteArray {
        val docId = videoRef.get().documentId
        cache.get(docId, index)?.let { return it }
        val used = videoRef.get()
        val data = try {
            gateway.readChunk(used, index)
        } catch (e: TelegramError.FileReferenceExpired) {
            val fresh = refreshLock.withLock {
                val cur = videoRef.get()
                if (cur !== used) cur else gateway.refreshFileReference(used).also { videoRef.set(it) }
            }
            gateway.readChunk(fresh, index)
        }
        val expected = ChunkMath.chunkLength(size, index)
        if (data.size != expected) throw IOException("unexpected chunk length ${data.size}, expected $expected")
        cache.put(docId, index, data)
        return data
    }

    companion object {
        const val READ_AHEAD_CHUNKS = 4
    }
}
