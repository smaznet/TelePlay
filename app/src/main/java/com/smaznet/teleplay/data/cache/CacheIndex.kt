package com.smaznet.teleplay.data.cache


data class CacheRecord(val documentId: Long, val sizeOnDisk: Long, val lastAccess: Long)

/** Index of cache entries (the `cache_entry` table); the chunk bitmaps live in `.map` files. */
interface CacheIndex {
    fun get(documentId: Long): CacheRecord?
    fun upsert(record: CacheRecord)
    fun delete(documentId: Long)
    fun all(): List<CacheRecord>
    fun clear()
}

class InMemoryCacheIndex : CacheIndex {
    private val rows = LinkedHashMap<Long, CacheRecord>()
    override fun get(documentId: Long) = rows[documentId]
    override fun upsert(record: CacheRecord) { rows[record.documentId] = record }
    override fun delete(documentId: Long) { rows.remove(documentId) }
    override fun all() = rows.values.toList()
    override fun clear() = rows.clear()
}
