package com.smaznet.teleplay.data.db

import android.content.ContentValues
import com.smaznet.teleplay.domain.PlaybackProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Pure rules for playback progress (FR-012, FR-026). */
object ProgressRules {
    const val COMPLETED_PERCENT = 95
    const val RESUME_MIN_MS = 10_000L

    fun isCompleted(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs * 100 >= durationMs * COMPLETED_PERCENT

    /** Position to resume from, or null to start from the beginning. */
    fun resumePositionMs(p: PlaybackProgress?): Long? =
        if (p != null && !p.completed && p.positionMs > RESUME_MIN_MS) p.positionMs else null
}

/** Raw persistence of playback progress. Rules live in [ProgressRules] and VideoRepository. Blocking calls. */
interface ProgressStore {
    fun get(videoId: Long): PlaybackProgress?
    fun observe(videoId: Long): Flow<PlaybackProgress?>
    fun observeAll(): Flow<List<PlaybackProgress>>
    fun put(progress: PlaybackProgress)
    fun deleteAll()
}

class SqliteProgressStore(private val db: TelePlayDb, private val changes: DbChanges) : ProgressStore {

    override fun get(videoId: Long): PlaybackProgress? =
        db.readableDatabase.rawQuery(
            "SELECT videoId, positionMs, durationMs, completed, updatedAt FROM playback_progress WHERE videoId = ?",
            arrayOf(videoId.toString()),
        ).use { c -> if (c.moveToFirst()) PlaybackProgress(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3) != 0, c.getLong(4)) else null }

    override fun observe(videoId: Long): Flow<PlaybackProgress?> =
        changes.version.map { get(videoId) }.flowOn(Dispatchers.IO)

    override fun observeAll(): Flow<List<PlaybackProgress>> =
        changes.version.map {
            db.readableDatabase.rawQuery(
                "SELECT videoId, positionMs, durationMs, completed, updatedAt FROM playback_progress ORDER BY updatedAt DESC",
                null,
            ).use { c ->
                buildList { while (c.moveToNext()) add(PlaybackProgress(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3) != 0, c.getLong(4))) }
            }
        }.flowOn(Dispatchers.IO)

    override fun put(progress: PlaybackProgress) {
        db.writableDatabase.insertWithOnConflict(
            "playback_progress", null,
            ContentValues().apply {
                put("videoId", progress.videoId); put("positionMs", progress.positionMs)
                put("durationMs", progress.durationMs); put("completed", if (progress.completed) 1 else 0)
                put("updatedAt", progress.updatedAt)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
        changes.bump()
    }

    override fun deleteAll() {
        db.writableDatabase.delete("playback_progress", null, null)
        changes.bump()
    }
}
