package com.smaznet.teleplay.fakes

import com.smaznet.teleplay.data.db.ProgressStore
import com.smaznet.teleplay.data.db.VideoStore
import com.smaznet.teleplay.data.db.withId
import com.smaznet.teleplay.data.repo.ConnectionStore
import com.smaznet.teleplay.data.repo.TokenVault
import com.smaznet.teleplay.domain.Availability
import com.smaznet.teleplay.domain.BotConnection
import com.smaznet.teleplay.domain.PlaybackProgress
import com.smaznet.teleplay.domain.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class InMemoryVideoStore : VideoStore {
    private val rows = MutableStateFlow<List<Video>>(emptyList())
    private var nextId = 1L
    val all: List<Video> get() = rows.value

    override fun observeAll(): Flow<List<Video>> =
        rows.map { l -> l.sortedWith(compareByDescending<Video> { it.receivedAt }.thenByDescending { it.messageId }) }

    override fun all(): List<Video> = rows.value.sortedWith(compareByDescending<Video> { it.receivedAt }.thenByDescending { it.messageId })

    override fun get(id: Long) = rows.value.firstOrNull { it.id == id }

    override fun findByDocumentId(documentId: Long) =
        rows.value.filter { it.documentId == documentId }.maxWithOrNull(compareBy<Video> { it.receivedAt }.thenBy { it.messageId })

    override fun upsert(video: Video): Long {
        val existing = rows.value.firstOrNull { it.documentId == video.documentId }
        if (existing == null) {
            val dup = rows.value.firstOrNull { it.chatUserId == video.chatUserId && it.messageId == video.messageId }
            if (dup != null) return dup.id
            val id = nextId++
            rows.value = rows.value + video.withId(id)
            return id
        }
        val newer = video.receivedAt > existing.receivedAt ||
            (video.receivedAt == existing.receivedAt && video.messageId > existing.messageId)
        if (newer) rows.value = rows.value.map { if (it.id == existing.id) video.withId(existing.id) else it }
        return existing.id
    }

    private fun mutate(documentId: Long, f: (Video) -> Video) {
        rows.value = rows.value.map { if (it.documentId == documentId) f(it) else it }
    }

    override fun markUnavailable(documentId: Long, availability: Availability) = mutate(documentId) {
        Video(it.id, it.chatUserId, it.messageId, it.documentId, it.accessHash, it.fileReference, it.dcId, it.title,
            it.durationSec, it.sizeBytes, it.mimeType, it.width, it.height, it.thumbSize, it.receivedAt, availability)
    }

    override fun updateFileReference(documentId: Long, fileReference: ByteArray) = mutate(documentId) {
        Video(it.id, it.chatUserId, it.messageId, it.documentId, it.accessHash, fileReference, it.dcId, it.title,
            it.durationSec, it.sizeBytes, it.mimeType, it.width, it.height, it.thumbSize, it.receivedAt, it.availability)
    }

    override fun deleteAll() { rows.value = emptyList() }
}

class InMemoryProgressStore : ProgressStore {
    private val rows = MutableStateFlow<Map<Long, PlaybackProgress>>(emptyMap())
    val all: Map<Long, PlaybackProgress> get() = rows.value
    override fun get(videoId: Long) = rows.value[videoId]
    override fun observe(videoId: Long): Flow<PlaybackProgress?> = rows.map { it[videoId] }
    override fun observeAll(): Flow<List<PlaybackProgress>> = rows.map { it.values.toList() }
    override fun put(progress: PlaybackProgress) { rows.value = rows.value + (progress.videoId to progress) }
    override fun deleteAll() { rows.value = emptyMap() }
}

class InMemoryConnectionStore(var row: BotConnection? = null) : ConnectionStore {
    override fun load() = row
    override fun save(connection: BotConnection) { row = connection }
    override fun clear() { row = null }
}

class InMemoryTokenVault(var token: String? = null) : TokenVault {
    override fun get() = token
    override fun put(token: String) { this.token = token }
    override fun clear() { token = null }
}

class InMemoryRemoteMessageStore(var row: com.smaznet.teleplay.domain.remote.RemoteMessage? = null) :
    com.smaznet.teleplay.data.db.RemoteMessageStore {
    override fun get() = row
    override fun put(m: com.smaznet.teleplay.domain.remote.RemoteMessage) { row = m }
    override fun clear() { row = null }
}
