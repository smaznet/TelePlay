package com.smaznet.teleplay.data.cache

/** Chunk arithmetic. 512 KB aligned chunks satisfy Telegram's 4 KB offset and 1 MB boundary rules. */
object ChunkMath {
    const val CHUNK_SIZE = 524_288
    const val READ_ALIGN = 4096
    const val BOUNDARY = 1_048_576

    fun chunkIndexOf(position: Long): Int = (position / CHUNK_SIZE).toInt()

    fun chunkOffset(chunkIndex: Int): Long = chunkIndex.toLong() * CHUNK_SIZE

    fun chunkCount(sizeBytes: Long): Int = ((sizeBytes + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()

    /** Expected byte length of the chunk (the last one may be shorter). */
    fun chunkLength(sizeBytes: Long, chunkIndex: Int): Int =
        minOf(CHUNK_SIZE.toLong(), sizeBytes - chunkOffset(chunkIndex)).coerceAtLeast(0).toInt()
}
