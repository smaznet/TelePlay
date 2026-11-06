package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.domain.remote.RemoteButton
import com.smaznet.teleplay.domain.remote.RemoteFailure
import com.smaznet.teleplay.domain.remote.RemoteSendException
import com.smaznet.teleplay.mtproto.Rpc
import com.smaznet.teleplay.mtproto.session.RpcException
import com.smaznet.teleplay.mtproto.tl.generated.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom

/**
 * The only senders in the app (constitution v2.1.0, Principle III): they talk to the authorized user's
 * private chat and nowhere else. Every call returns a [Result]; failures are [RemoteSendException]s.
 */
interface BotMessenger {
    /** Sends [text] with an optional inline keyboard; returns the new message id. */
    suspend fun sendText(text: String, keyboard: List<List<RemoteButton>>?): Result<Int>

    /** Replaces the text; a null [keyboard] removes the buttons. */
    suspend fun editText(messageId: Int, text: String, keyboard: List<List<RemoteButton>>?): Result<Unit>

    suspend fun pin(messageId: Int): Result<Unit>
    suspend fun unpin(messageId: Int): Result<Unit>

    /**
     * Sends [bytes] (at most [MAX_DOCUMENT_BYTES]) as a document with [caption]; returns the new message id.
     * Feature 005, constitution v2.3.0 Principle III.
     */
    suspend fun sendDocument(fileName: String, mime: String, bytes: ByteArray, caption: String): Result<Int>

    /** Registers the bot's command menu as `(command, description)` pairs. */
    suspend fun setCommands(commands: List<Pair<String, String>>): Result<Unit>

    /** Stops the button spinner; [toast] is shown briefly to the user when non-null. */
    suspend fun answerCallback(queryId: Long, toast: String?): Result<Unit>
}

const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024

/** [BotMessenger] over MTProto. [peer] returns (userId, accessHash) of the authorized user or null. */
class RpcBotMessenger(
    private val rpc: Rpc,
    private val peer: () -> Pair<Long, Long>?,
    private val random: SecureRandom = SecureRandom(),
    private val callTimeoutMs: Long = 30_000,
) : BotMessenger {

    override suspend fun sendText(text: String, keyboard: List<List<RemoteButton>>?): Result<Int> =
        call { p ->
            val res = rpc.invoke(
                F_messages_sendMessage(
                    peer = p, message = text, random_id = random.nextLong(),
                    reply_markup = keyboard?.let(::markup),
                ),
            )
            sentMessageId(res) ?: throw RemoteSendException(RemoteFailure.OTHER)
        }

    override suspend fun editText(messageId: Int, text: String, keyboard: List<List<RemoteButton>>?): Result<Unit> =
        call(okOn = NOT_MODIFIED) { p ->
            rpc.invoke(F_messages_editMessage(peer = p, id = messageId, message = text, reply_markup = keyboard?.let(::markup) ?: C_replyInlineMarkup(rows = emptyList())))
            Unit
        }

    override suspend fun pin(messageId: Int): Result<Unit> =
        call { p -> rpc.invoke(F_messages_updatePinnedMessage(silent = true, peer = p, id = messageId)); Unit }

    override suspend fun unpin(messageId: Int): Result<Unit> =
        call { p -> rpc.invoke(F_messages_updatePinnedMessage(unpin = true, silent = true, peer = p, id = messageId)); Unit }

    override suspend fun sendDocument(fileName: String, mime: String, bytes: ByteArray, caption: String): Result<Int> {
        if (bytes.size > MAX_DOCUMENT_BYTES) return Result.failure(RemoteSendException(RemoteFailure.OTHER))
        return call { p ->
            val fileId = random.nextLong()
            val parts = maxOf(1, (bytes.size + PART_SIZE - 1) / PART_SIZE)
            for (i in 0 until parts) {
                val chunk = bytes.copyOfRange(i * PART_SIZE, minOf(bytes.size, (i + 1) * PART_SIZE))
                if (!rpc.invoke(F_upload_saveFilePart(fileId, i, chunk))) throw RemoteSendException(RemoteFailure.OTHER)
            }
            val media = C_inputMediaUploadedDocument(
                force_file = true,
                file = C_inputFile(fileId, parts, fileName, ""),
                mime_type = mime,
                attributes = listOf(C_documentAttributeFilename(fileName)),
            )
            val res = rpc.invoke(F_messages_sendMedia(peer = p, media = media, message = caption, random_id = random.nextLong()))
            sentMessageId(res) ?: throw RemoteSendException(RemoteFailure.OTHER)
        }
    }

    override suspend fun setCommands(commands: List<Pair<String, String>>): Result<Unit> =
        call { _ ->
            rpc.invoke(
                F_bots_setBotCommands(
                    scope = C_botCommandScopeDefault(), lang_code = "",
                    commands = commands.map { (c, d) -> C_botCommand(command = c, description = d) },
                ),
            )
            Unit
        }

    override suspend fun answerCallback(queryId: Long, toast: String?): Result<Unit> =
        // The query id is not tied to the chat, but nothing may be sent while no authorized peer exists.
        call(okOn = QUERY_INVALID) { _ ->
            rpc.invoke(F_messages_setBotCallbackAnswer(query_id = queryId, message = toast, cache_time = 0))
            Unit
        }

    private suspend fun <R> call(okOn: Regex? = null, block: suspend (T_InputPeer) -> R): Result<R> {
        val (userId, hash) = peer() ?: return Result.failure(RemoteSendException(RemoteFailure.BLOCKED))
        return try {
            Result.success(withTimeout(callTimeoutMs) { block(C_inputPeerUser(userId, hash)) })
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) Result.failure(RemoteSendException(RemoteFailure.FLOOD)) else throw e
        } catch (e: RemoteSendException) {
            Result.failure(e)
        } catch (e: Exception) {
            val rpcMessage = (e as? TelegramError.Unknown)?.rpcMessage ?: (e as? RpcException)?.rpcMessage
            if (okOn != null && rpcMessage != null && okOn.matches(rpcMessage)) {
                @Suppress("UNCHECKED_CAST")
                Result.success(Unit as R)
            } else Result.failure(RemoteSendException(classify(e, rpcMessage)))
        }
    }

    private fun markup(rows: List<List<RemoteButton>>) = C_replyInlineMarkup(
        rows = rows.map { row ->
            C_keyboardInlineButtonRow(
                row.map { C_keyboardInlineButton(text = it.label, type = C_inlineButtonTypeCallback(data = it.data.toByteArray(Charsets.UTF_8))) },
            )
        },
    )

    private fun sentMessageId(u: T_Updates): Int? = when (u) {
        is C_updateShortSentMessage -> u.id
        is C_updateShortMessage -> u.id
        is C_updates -> ids(u.updates)
        is C_updatesCombined -> ids(u.updates)
        is C_updateShort -> ids(listOf(u.update))
        else -> null
    }

    private fun ids(updates: List<T_Update>): Int? =
        updates.firstNotNullOfOrNull { (it as? C_updateMessageID)?.id }
            ?: updates.firstNotNullOfOrNull { ((it as? C_updateNewMessage)?.message as? C_message)?.id }

    companion object {
        private const val PART_SIZE = 128 * 1024
        private val NOT_MODIFIED = Regex("MESSAGE_NOT_MODIFIED")
        private val QUERY_INVALID = Regex("QUERY_ID_INVALID")

        /** Maps an error to a [RemoteFailure]; exposed for tests. */
        fun classify(e: Exception, rpcMessage: String? = (e as? TelegramError.Unknown)?.rpcMessage): RemoteFailure = when {
            e is TelegramError.Offline -> RemoteFailure.OFFLINE
            e is TelegramError.RateLimited -> RemoteFailure.FLOOD
            rpcMessage == null -> RemoteFailure.OTHER
            rpcMessage.startsWith("MESSAGE_ID_INVALID") || rpcMessage == "MESSAGE_DELETE_FORBIDDEN" -> RemoteFailure.GONE
            rpcMessage == "PEER_ID_INVALID" || rpcMessage == "USER_IS_BLOCKED" ||
                rpcMessage == "INPUT_USER_DEACTIVATED" || rpcMessage == "USER_DEACTIVATED" -> RemoteFailure.BLOCKED
            rpcMessage.startsWith("FLOOD_WAIT") -> RemoteFailure.FLOOD
            else -> RemoteFailure.OTHER
        }
    }
}
