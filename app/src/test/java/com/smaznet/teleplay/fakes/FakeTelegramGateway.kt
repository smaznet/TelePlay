package com.smaznet.teleplay.fakes

import com.smaznet.teleplay.data.telegram.TelegramGateway
import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.domain.GatewayState
import com.smaznet.teleplay.domain.IncomingMessage
import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.domain.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeTelegramGateway : TelegramGateway {
    val state = MutableStateFlow(GatewayState.CONNECTING)
    val messages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)

    var acceptedToken: String? = "123:valid"
    var chunkProvider: (Video, Int) -> ByteArray = { _, _ -> ByteArray(0) }
    var refreshed: ((Video) -> Video)? = null
    var wiped = false
    val chunkRequests = mutableListOf<Pair<Long, Int>>()

    val callbacks = MutableSharedFlow<com.smaznet.teleplay.domain.remote.CallbackQuery>(extraBufferCapacity = 64)
    val hashes = MutableSharedFlow<Pair<Long, Long>>(extraBufferCapacity = 64)
    override val callbackQueries: Flow<com.smaznet.teleplay.domain.remote.CallbackQuery> get() = callbacks
    override val userAccessHashes: Flow<Pair<Long, Long>> get() = hashes

    override val connectionState: Flow<GatewayState> get() = state
    override val incomingMessages: Flow<IncomingMessage> get() = messages

    override suspend fun signInWithBotToken(token: String): Result<BotInfo> =
        if (token == acceptedToken) Result.success(BotInfo(1L, "fake_bot")).also { state.value = GatewayState.READY }
        else Result.failure(TelegramError.TokenRejected())

    override suspend fun refreshFileReference(video: Video): Video = refreshed?.invoke(video) ?: video

    /** Messages [fetchMessages] can resolve, by id. */
    val messagesById = mutableMapOf<Int, IncomingMessage>()
    val docsById = mutableMapOf<Int, com.smaznet.teleplay.domain.IncomingDocument>()
    val documentBytes = mutableMapOf<Long, ByteArray>()
    val fetchBatches = mutableListOf<List<Int>>()
    /** When set, the [fetchFailureAt]-th [fetchMessages] call (1-based) throws it (once). */
    var fetchFailure: Exception? = null
    var fetchFailureAt = 1

    /** When set, [fetchMessages] waits for it before answering (to hold an import open). */
    var fetchGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun fetchMessages(ids: List<Int>): List<IncomingMessage> {
        fetchBatches += ids
        fetchGate?.await()
        if (fetchBatches.size == fetchFailureAt) fetchFailure?.let { fetchFailure = null; throw it }
        return ids.mapNotNull { messagesById[it] }
    }

    override suspend fun fetchDocument(messageId: Int): com.smaznet.teleplay.domain.IncomingDocument? = docsById[messageId]

    override suspend fun downloadDocument(messageId: Int, doc: com.smaznet.teleplay.domain.IncomingDocument, maxBytes: Int): ByteArray {
        val b = documentBytes[doc.id] ?: throw TelegramError.FileUnavailable()
        if (b.size > maxBytes) throw TelegramError.Unsupported("too large")
        return b
    }

    override suspend fun readChunk(video: Video, chunkIndex: Int): ByteArray {
        synchronized(chunkRequests) { chunkRequests += video.documentId to chunkIndex }
        return chunkProvider(video, chunkIndex)
    }

    override suspend fun signOutAndWipe() { wiped = true }
}
