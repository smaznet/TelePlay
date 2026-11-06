package com.smaznet.teleplay.data.db

import android.content.ContentValues
import android.database.Cursor
import com.smaznet.teleplay.domain.Availability
import com.smaznet.teleplay.domain.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Bumped after every write so the SQLite stores can re-emit their queries. Share one instance between stores. */
class DbChanges {
    val version = MutableStateFlow(0L)
    fun bump() { version.value = version.value + 1 }
}

fun Video.withId(newId: Long) = Video(
    newId, chatUserId, messageId, documentId, accessHash, fileReference, dcId, title, durationSec,
    sizeBytes, mimeType, width, height, thumbSize, receivedAt, availability,
)

/**
 * Persistence for the video library. One row per documentId: a repeat send of the same document
 * updates the existing row when the new message is newer (keeps the local id, so progress survives).
 * Blocking calls; invoke off the main thread.
 */
interface VideoStore {
    /** All videos, newest received first. */
    fun observeAll(): Flow<List<Video>>
    /** One-shot snapshot of all videos, newest received first (feature 005 export). */
    fun all(): List<Video>
    fun get(id: Long): Video?

    /** The newest row for a document, or null. */
    fun findByDocumentId(documentId: Long): Video?

    /** Inserts or collapses by documentId (newest message wins). Returns the local id of the surviving row. */
    fun upsert(video: Video): Long
    fun markUnavailable(documentId: Long, availability: Availability = Availability.REMOTE_GONE)
    fun updateFileReference(documentId: Long, fileReference: ByteArray)
    fun deleteAll()
}

class SqliteVideoStore(private val db: TelePlayDb, private val changes: DbChanges) : VideoStore {

    override fun observeAll(): Flow<List<Video>> =
        changes.version.map { queryAll() }.flowOn(Dispatchers.IO)

    private fun queryAll(): List<Video> =
        db.readableDatabase.rawQuery("SELECT * FROM video ORDER BY receivedAt DESC, messageId DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.toVideo()) }
        }

    override fun all(): List<Video> = queryAll()

    override fun get(id: Long): Video? =
        db.readableDatabase.rawQuery("SELECT * FROM video WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toVideo() else null
        }

    override fun findByDocumentId(documentId: Long): Video? =
        db.readableDatabase.rawQuery(
            "SELECT * FROM video WHERE documentId = ? ORDER BY receivedAt DESC, messageId DESC LIMIT 1", arrayOf(documentId.toString()),
        ).use { c -> if (c.moveToFirst()) c.toVideo() else null }

    override fun upsert(video: Video): Long {
        val d = db.writableDatabase
        var result = -1L
        d.beginTransaction()
        try {
            val existing = d.rawQuery(
                "SELECT id, receivedAt, messageId FROM video WHERE documentId = ? ORDER BY receivedAt DESC, messageId DESC LIMIT 1",
                arrayOf(video.documentId.toString()),
            ).use { c -> if (c.moveToFirst()) Triple(c.getLong(0), c.getLong(1), c.getInt(2)) else null }
            if (existing == null) {
                result = d.insertWithOnConflict("video", null, video.toValues(), android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
                if (result == -1L) {
                    result = d.rawQuery(
                        "SELECT id FROM video WHERE chatUserId = ? AND messageId = ?",
                        arrayOf(video.chatUserId.toString(), video.messageId.toString()),
                    ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
                }
            } else {
                result = existing.first
                val newer = video.receivedAt > existing.second ||
                    (video.receivedAt == existing.second && video.messageId > existing.third)
                if (newer) d.update("video", video.toValues(), "id = ?", arrayOf(result.toString()))
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        changes.bump()
        return result
    }

    override fun markUnavailable(documentId: Long, availability: Availability) {
        db.writableDatabase.update(
            "video", ContentValues().apply { put("availability", availability.name) },
            "documentId = ?", arrayOf(documentId.toString()),
        )
        changes.bump()
    }

    override fun updateFileReference(documentId: Long, fileReference: ByteArray) {
        db.writableDatabase.update(
            "video", ContentValues().apply { put("fileReference", fileReference) },
            "documentId = ?", arrayOf(documentId.toString()),
        )
        changes.bump()
    }

    override fun deleteAll() {
        db.writableDatabase.apply {
            beginTransaction()
            try {
                delete("playback_progress", null, null)
                delete("video", null, null)
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
        changes.bump()
    }

    private fun Video.toValues() = ContentValues().apply {
        put("chatUserId", chatUserId); put("messageId", messageId); put("documentId", documentId)
        put("accessHash", accessHash); put("fileReference", fileReference); put("dcId", dcId)
        put("title", title); put("durationSec", durationSec); put("sizeBytes", sizeBytes)
        put("mimeType", mimeType); put("width", width); put("height", height)
        put("thumbSize", thumbSize); put("receivedAt", receivedAt); put("availability", availability.name)
    }

    private fun Cursor.toVideo() = Video(
        id = getLong(getColumnIndexOrThrow("id")),
        chatUserId = getLong(getColumnIndexOrThrow("chatUserId")),
        messageId = getInt(getColumnIndexOrThrow("messageId")),
        documentId = getLong(getColumnIndexOrThrow("documentId")),
        accessHash = getLong(getColumnIndexOrThrow("accessHash")),
        fileReference = getBlob(getColumnIndexOrThrow("fileReference")),
        dcId = getInt(getColumnIndexOrThrow("dcId")),
        title = getString(getColumnIndexOrThrow("title")),
        durationSec = getInt(getColumnIndexOrThrow("durationSec")),
        sizeBytes = getLong(getColumnIndexOrThrow("sizeBytes")),
        mimeType = getString(getColumnIndexOrThrow("mimeType")),
        width = getInt(getColumnIndexOrThrow("width")),
        height = getInt(getColumnIndexOrThrow("height")),
        thumbSize = getColumnIndexOrThrow("thumbSize").let { if (isNull(it)) null else getString(it) },
        receivedAt = getLong(getColumnIndexOrThrow("receivedAt")),
        availability = Availability.valueOf(getString(getColumnIndexOrThrow("availability"))),
    )
}
