package com.smaznet.teleplay.data.telegram

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.smaznet.teleplay.domain.Video

/** Small thread-safe LRU map. */
class LruMap<K, V>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > maxEntries
    }

    @Synchronized fun get(key: K): V? = map[key]
    @Synchronized fun put(key: K, value: V) { map[key] = value }
    @Synchronized fun clear() = map.clear()
    @Synchronized fun keys(): List<K> = map.keys.toList()
}

/** Loads a video's thumbnail through `upload.getFile` and keeps decoded bitmaps in an LRU. */
class ThumbnailLoader(
    private val fetch: suspend (Video) -> ByteArray?,
    maxEntries: Int = 64,
    private val decode: (ByteArray) -> ImageBitmap? = { b ->
        BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
    },
) {
    constructor(reader: FileReader, maxEntries: Int = 64) : this({ reader.readThumbnail(it) }, maxEntries)

    private val cache = LruMap<Long, ImageBitmap>(maxEntries)

    /** Decoded thumbnail, or null when the video has none or it cannot be loaded. */
    suspend fun load(video: Video): ImageBitmap? {
        if (video.thumbSize == null) return null
        cache.get(video.documentId)?.let { return it }
        val bytes = fetch(video) ?: return null
        val bmp = decode(bytes) ?: return null
        cache.put(video.documentId, bmp)
        return bmp
    }

    fun clear() = cache.clear()
}
