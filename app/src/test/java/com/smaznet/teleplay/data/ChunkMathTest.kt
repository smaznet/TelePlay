package com.smaznet.teleplay.data

import com.smaznet.teleplay.data.cache.ChunkMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkMathTest {
    @Test
    fun offsetsAreMultiplesOf4096AndChunksStayWithinOneMegabyteBoundary() {
        for (i in 0..5000) {
            val offset = ChunkMath.chunkOffset(i)
            assertEquals(0L, offset % ChunkMath.READ_ALIGN)
            val last = offset + ChunkMath.CHUNK_SIZE - 1
            assertEquals(offset / ChunkMath.BOUNDARY, last / ChunkMath.BOUNDARY)
        }
        assertEquals(524_288, ChunkMath.CHUNK_SIZE)
    }

    @Test
    fun indexCountAndLength() {
        assertEquals(0, ChunkMath.chunkIndexOf(524_287))
        assertEquals(1, ChunkMath.chunkIndexOf(524_288))
        assertEquals(3, ChunkMath.chunkCount(524_288L * 2 + 1))
        assertEquals(1, ChunkMath.chunkLength(524_288L * 2 + 1, 2))
        assertEquals(524_288, ChunkMath.chunkLength(524_288L * 2 + 1, 1))
    }
}
