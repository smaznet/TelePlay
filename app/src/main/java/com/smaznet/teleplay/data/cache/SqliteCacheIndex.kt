package com.smaznet.teleplay.data.cache

import android.content.ContentValues
import com.smaznet.teleplay.data.db.TelePlayDb

class SqliteCacheIndex(private val db: TelePlayDb) : CacheIndex {
    override fun get(documentId: Long): CacheRecord? =
        db.readableDatabase.query(
            "cache_entry", arrayOf("documentId", "sizeOnDisk", "lastAccess"),
            "documentId = ?", arrayOf(documentId.toString()), null, null, null,
        ).use { c -> if (c.moveToFirst()) CacheRecord(c.getLong(0), c.getLong(1), c.getLong(2)) else null }

    override fun upsert(record: CacheRecord) {
        val values = ContentValues().apply {
            put("documentId", record.documentId)
            put("sizeOnDisk", record.sizeOnDisk)
            put("lastAccess", record.lastAccess)
        }
        db.writableDatabase.insertWithOnConflict("cache_entry", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun delete(documentId: Long) {
        db.writableDatabase.delete("cache_entry", "documentId = ?", arrayOf(documentId.toString()))
    }

    override fun all(): List<CacheRecord> =
        db.readableDatabase.query("cache_entry", arrayOf("documentId", "sizeOnDisk", "lastAccess"), null, null, null, null, null)
            .use { c ->
                buildList { while (c.moveToNext()) add(CacheRecord(c.getLong(0), c.getLong(1), c.getLong(2))) }
            }

    override fun clear() {
        db.writableDatabase.delete("cache_entry", null, null)
    }
}
