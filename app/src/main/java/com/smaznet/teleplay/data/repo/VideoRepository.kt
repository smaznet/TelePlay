package com.smaznet.teleplay.data.repo

import com.smaznet.teleplay.data.db.ProgressRules
import com.smaznet.teleplay.data.db.ProgressStore
import com.smaznet.teleplay.data.db.VideoStore
import com.smaznet.teleplay.domain.Availability
import com.smaznet.teleplay.domain.IncomingMessage
import com.smaznet.teleplay.domain.PlaybackProgress
import com.smaznet.teleplay.domain.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What [VideoRepository.ingestResult] did with a message. */
sealed class IngestResult {
    /** A new library entry. */
    class Added(val id: Long, val title: String) : IngestResult()

    /** The same file was already in the library (sent again in a different message). */
    class Duplicate(val id: Long, val title: String) : IngestResult()

    /** The very same message delivered again (update replay); nothing changed and nothing to announce. */
    class Redelivered(val id: Long) : IngestResult()

    /** Not from the authorized user, or not a video. */
    object Ignored : IngestResult()

    val localId: Long? get() = when (this) {
        is Added -> id
        is Duplicate -> id
        is Redelivered -> id
        Ignored -> null
    }
}

/** What [VideoRepository.restore] did with one backup entry. */
enum class RestoreResult { RESTORED, ALREADY_PRESENT, CHANGED }

/**
 * Library and playback progress. Calls are blocking (SQLite); invoke off the main thread.
 * [IncomingMessage.date] is epoch SECONDS (Telegram native); Video.receivedAt is epoch millis.
 */
class VideoRepository(
    private val videoStore: VideoStore,
    private val progressStore: ProgressStore,
    /** Current authorized user id, null until paired. */
    private val authorizedUserId: () -> Long?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Library: newest received first, one entry per documentId. */
    val videos: Flow<List<Video>> get() = videoStore.observeAll()

    /** Partly watched, not completed videos, most recently watched first (FR-026). Empty when none. */
    fun continueWatching(): Flow<List<Video>> =
        combine(videoStore.observeAll(), progressStore.observeAll()) { videos, progress ->
            val byId = videos.associateBy { it.id }
            progress.filter { !it.completed && it.positionMs > 0 }
                .sortedByDescending { it.updatedAt }
                .mapNotNull { byId[it.videoId] }
        }

    fun video(id: Long): Video? = videoStore.get(id)

    /** Stores the message's video if it is from the authorized user and is a video. Returns the local id or null if dropped. */
    fun ingest(msg: IncomingMessage): Long? = ingestResult(msg).localId

    /** Like [ingest] but says whether the video is new, a duplicate file, a replay, or ignored. */
    fun ingestResult(msg: IncomingMessage): IngestResult {
        val authorized = authorizedUserId() ?: return IngestResult.Ignored
        if (msg.senderUserId != authorized) return IngestResult.Ignored
        val v = msg.video ?: return IngestResult.Ignored
        val isVideo = v.mime.startsWith("video/", ignoreCase = true) || (v.width > 0 && v.height > 0)
        if (!isVideo) return IngestResult.Ignored
        val receivedAt = msg.date * 1000
        // The file name is the title; a caption is only the fallback for files sent without a name.
        val title = v.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: msg.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: formatDate(receivedAt)
        val before = videoStore.findByDocumentId(v.documentId)
        val id = videoStore.upsert(
            Video(
                id = 0, chatUserId = msg.senderUserId, messageId = msg.messageId, documentId = v.documentId,
                accessHash = v.accessHash, fileReference = v.fileReference, dcId = v.dcId, title = title,
                durationSec = v.duration, sizeBytes = v.size, mimeType = v.mime, width = v.width, height = v.height,
                thumbSize = v.thumbSize, receivedAt = receivedAt, availability = Availability.PLAYABLE,
            ),
        )
        return when {
            before == null -> IngestResult.Added(id, title)
            before.messageId == msg.messageId -> IngestResult.Redelivered(id)
            else -> IngestResult.Duplicate(id, title)
        }
    }

    /** All videos (newest first) with their progress, for the playlist backup. */
    fun snapshot(): List<Pair<Video, PlaybackProgress?>> =
        videoStore.all().map { it to progressStore.get(it.id) }

    /**
     * Restores one backup entry from the message Telegram returned for it. Unlike [ingestResult] there is no
     * sender check: the entry may come from an earlier account's chat. The document must still be the one
     * the backup remembers; local progress is only replaced by older-than-backup progress.
     */
    fun restore(entry: com.smaznet.teleplay.domain.playlist.BackupEntry, fetched: IncomingMessage): RestoreResult {
        val v = fetched.video ?: return RestoreResult.CHANGED
        if (v.documentId != entry.documentId) return RestoreResult.CHANGED
        val before = videoStore.findByDocumentId(v.documentId)
        val title = v.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: entry.title.trim().takeIf { it.isNotEmpty() }
            ?: fetched.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: formatDate(entry.receivedAt)
        val receivedAt = entry.receivedAt.takeIf { it > 0 } ?: (fetched.date * 1000)
        val id = videoStore.upsert(
            Video(
                id = 0, chatUserId = fetched.senderUserId, messageId = fetched.messageId, documentId = v.documentId,
                accessHash = v.accessHash, fileReference = v.fileReference, dcId = v.dcId, title = title,
                durationSec = v.duration, sizeBytes = v.size, mimeType = v.mime, width = v.width, height = v.height,
                thumbSize = v.thumbSize, receivedAt = receivedAt, availability = Availability.PLAYABLE,
            ),
        )
        if (entry.hasProgress) {
            val backupUpdatedAt = entry.progressUpdatedAt!!
            val local = progressStore.get(id)
            if (local == null || local.updatedAt < backupUpdatedAt) {
                progressStore.put(PlaybackProgress(id, entry.positionMs!!, entry.durationMs!!, entry.completed!!, backupUpdatedAt))
            }
        }
        return if (before == null) RestoreResult.RESTORED else RestoreResult.ALREADY_PRESENT
    }

    fun markUnavailable(documentId: Long, availability: Availability = Availability.REMOTE_GONE) =
        videoStore.markUnavailable(documentId, availability)

    fun updateFileReference(documentId: Long, fileReference: ByteArray) =
        videoStore.updateFileReference(documentId, fileReference)

    fun progress(videoId: Long): Flow<PlaybackProgress?> = progressStore.observe(videoId)

    /** Called every 5 s while playing and on pause/exit. */
    fun saveProgress(videoId: Long, positionMs: Long, durationMs: Long) {
        progressStore.put(
            PlaybackProgress(videoId, positionMs, durationMs, ProgressRules.isCompleted(positionMs, durationMs), now()),
        )
    }

    /** Position to resume from, or null to start at the beginning. */
    fun resumePositionMs(videoId: Long): Long? = ProgressRules.resumePositionMs(progressStore.get(videoId))

    /** Overwrites the saved position with 0 (the "start over" choice). */
    fun startOver(videoId: Long) {
        val old = progressStore.get(videoId)
        val duration = old?.durationMs ?: ((videoStore.get(videoId)?.durationSec ?: 0) * 1000L)
        progressStore.put(PlaybackProgress(videoId, 0, duration, false, now()))
    }

    /** Deletes all videos and progress. */
    fun clearAll() {
        progressStore.deleteAll()
        videoStore.deleteAll()
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
}
