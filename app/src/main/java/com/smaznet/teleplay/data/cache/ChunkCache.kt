package com.smaznet.teleplay.data.cache

import com.smaznet.teleplay.domain.CacheStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.BitSet

/**
 * Sparse per-video cache: `<dir>/<documentId>.part` holds chunks at their natural offsets and
 * `<documentId>.map` is a persisted bitmap of the chunks fetched. Whole entries are evicted
 * least-recently-watched first, never the entry in use.
 */
class ChunkCache(
    private val dir: File,
    private val index: CacheIndex,
    @Volatile var limitBytes: Long = DEFAULT_LIMIT_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val maps = HashMap<Long, BitSet>()
    private var inUse: Long? = null

    /** Marks the entry being played so that eviction skips it. */
    fun setInUse(documentId: Long?) = synchronized(lock) { inUse = documentId }

    fun has(documentId: Long, chunkIndex: Int): Boolean =
        synchronized(lock) { bitmap(documentId).get(chunkIndex) }

    suspend fun get(documentId: Long, chunkIndex: Int): ByteArray? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!bitmap(documentId).get(chunkIndex)) return@synchronized null
            val part = partFile(documentId)
            val data = try {
                RandomAccessFile(part, "r").use { f ->
                    val offset = ChunkMath.chunkOffset(chunkIndex)
                    val len = minOf(ChunkMath.CHUNK_SIZE.toLong(), f.length() - offset).toInt()
                    if (len <= 0) null else ByteArray(len).also { f.seek(offset); f.readFully(it) }
                }
            } catch (e: java.io.IOException) { null }
            if (data == null) {
                bitmap(documentId).clear(chunkIndex)
                return@synchronized null
            }
            index.get(documentId)?.let { index.upsert(it.copy(lastAccess = clock())) }
            data
        }
    }

    suspend fun put(documentId: Long, chunkIndex: Int, bytes: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            require(bytes.size <= ChunkMath.CHUNK_SIZE)
            dir.mkdirs()
            val bits = bitmap(documentId)
            val record = index.get(documentId) ?: CacheRecord(documentId, 0, 0)
            if (bits.get(chunkIndex)) {
                index.upsert(record.copy(lastAccess = clock()))
                return@synchronized
            }
            val size = record.sizeOnDisk
            RandomAccessFile(partFile(documentId), "rw").use { f ->
                f.seek(ChunkMath.chunkOffset(chunkIndex))
                f.write(bytes)
            }
            bits.set(chunkIndex)
            persistMap(documentId, bits)
            index.upsert(CacheRecord(documentId, size + bytes.size, clock()))
            trimLocked(limitBytes)
        }
    }

    fun usage(): CacheStats = synchronized(lock) {
        CacheStats(index.all().sumOf { it.sizeOnDisk }, limitBytes)
    }

    /** Deletes entries, oldest access first, until the total is within [limit]. */
    fun trim(limit: Long = limitBytes) = synchronized(lock) { trimLocked(limit) }

    fun clear() = synchronized(lock) {
        for (r in index.all()) deleteFiles(r.documentId)
        maps.clear()
        index.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Removes everything including the folder (disconnect). */
    fun wipe() = synchronized(lock) {
        maps.clear()
        index.clear()
        dir.deleteRecursively()
    }

    private fun trimLocked(limit: Long) {
        var total = index.all().sumOf { it.sizeOnDisk }
        if (total <= limit) return
        for (r in index.all().filter { it.documentId != inUse }.sortedBy { it.lastAccess }) {
            if (total <= limit) break
            deleteFiles(r.documentId)
            maps.remove(r.documentId)
            index.delete(r.documentId)
            total -= r.sizeOnDisk
        }
    }

    private fun bitmap(documentId: Long): BitSet = maps.getOrPut(documentId) {
        val map = mapFile(documentId)
        if (map.exists() && partFile(documentId).exists()) BitSet.valueOf(map.readBytes()) else BitSet()
    }

    private fun persistMap(documentId: Long, bits: BitSet) {
        val tmp = File(dir, "$documentId.map.tmp")
        tmp.writeBytes(bits.toByteArray())
        if (!tmp.renameTo(mapFile(documentId))) { mapFile(documentId).writeBytes(bits.toByteArray()); tmp.delete() }
    }

    private fun deleteFiles(documentId: Long) {
        partFile(documentId).delete()
        mapFile(documentId).delete()
    }

    private fun partFile(documentId: Long) = File(dir, "$documentId.part")
    private fun mapFile(documentId: Long) = File(dir, "$documentId.map")

    companion object {
        const val DEFAULT_LIMIT_BYTES = 2L * 1024 * 1024 * 1024
    }
}
