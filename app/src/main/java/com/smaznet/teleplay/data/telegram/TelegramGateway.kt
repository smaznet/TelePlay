package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.domain.GatewayState
import com.smaznet.teleplay.domain.IncomingDocument
import com.smaznet.teleplay.domain.IncomingMessage
import com.smaznet.teleplay.domain.Video
import com.smaznet.teleplay.domain.remote.CallbackQuery
import kotlinx.coroutines.flow.Flow

/** The only seam between the app and Telegram. Only `mtproto/` and `data/telegram/` know protocol details. */
interface TelegramGateway {
    val connectionState: Flow<GatewayState>

    /** Signs in as the bot. Fails with TokenRejected, Offline, or RateLimited. */
    suspend fun signInWithBotToken(token: String): Result<BotInfo>

    /** Messages found by the update catch-up loop. */
    val incomingMessages: Flow<IncomingMessage>

    /** Button presses on the bot's inline keyboards, parsed from pushed updates. */
    val callbackQueries: Flow<CallbackQuery>

    /** `user id to access hash` pairs seen in updates; needed to address a user's private chat. */
    val userAccessHashes: Flow<Pair<Long, Long>>

    suspend fun refreshFileReference(video: Video): Video

    /** One 512 KB-aligned chunk of the video file. */
    suspend fun readChunk(video: Video, chunkIndex: Int): ByteArray

    /**
     * Resolves messages by id from the bot's own message box (feature 005). Ids Telegram no longer
     * has, or that are not private messages, are simply missing from the result. Batches of up to 100.
     */
    suspend fun fetchMessages(ids: List<Int>): List<IncomingMessage>

    /**
     * The document attached to message [messageId] in the bot's message box, whoever sent it (used to resolve
     * the backup file a `/import` replies to; it may be a file the bot itself sent). Null when there is none.
     */
    suspend fun fetchDocument(messageId: Int): IncomingDocument?

    /**
     * Downloads a small document of message [messageId] (the backup file); the message is re-read once
     * if the file reference has expired. Throws [com.smaznet.teleplay.domain.TelegramError.Unsupported]
     * above [maxBytes].
     */
    suspend fun downloadDocument(messageId: Int, doc: IncomingDocument, maxBytes: Int): ByteArray

    /** Erases sessions, database and cache. */
    suspend fun signOutAndWipe()
}
