package com.smaznet.teleplay.domain.playlist

import com.smaznet.teleplay.data.telegram.BotMessenger
import com.smaznet.teleplay.data.telegram.TelegramGateway
import com.smaznet.teleplay.domain.IncomingMessage
import com.smaznet.teleplay.domain.TelegramError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class PlaylistCommand { EXPORT, IMPORT, HELP }

/**
 * The three bot commands (constitution v2.3.0, Principle III), accepted only from the authorized user.
 * Anything else, from anyone, is not recognised here and flows on as before. Only one export or import
 * runs at a time.
 */
class PlaylistCommands(
    private val authorizedUserId: () -> Long?,
    private val transfer: PlaylistTransfer,
    private val gateway: TelegramGateway,
    private val messenger: BotMessenger,
    /** The bot's username when known; a `/cmd@other` suffix is ignored. */
    private val botUsername: () -> String? = { null },
    private val workingNoticeMs: Long = 3_000,
) {
    private val running = AtomicBoolean(false)

    /** The command in [msg] if it is from the authorized user, else null (never answers anyone else). */
    fun recognise(msg: IncomingMessage): PlaylistCommand? {
        val authorized = authorizedUserId() ?: return null
        if (msg.senderUserId != authorized) return null
        val token = msg.text?.trimStart()?.takeIf { it.startsWith("/") }?.split(Regex("\\s+"), limit = 2)?.first() ?: return null
        val name = token.substring(1).substringBefore('@').lowercase()
        val suffix = token.substringAfter('@', "")
        if (suffix.isNotEmpty() && !suffix.equals(botUsername(), ignoreCase = true)) return null
        return when (name) {
            "export" -> PlaylistCommand.EXPORT
            "import" -> PlaylistCommand.IMPORT
            "help" -> PlaylistCommand.HELP
            else -> null
        }
    }

    /** Runs the command in [msg]; returns false when [msg] is not one of ours. */
    suspend fun handle(msg: IncomingMessage): Boolean {
        val command = recognise(msg) ?: return false
        try {
            when (command) {
                PlaylistCommand.HELP -> reply(PlaylistMessages.HELP)
                PlaylistCommand.EXPORT -> exclusive { doExport() }
                PlaylistCommand.IMPORT -> exclusive { doImport(msg) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reply(if (command == PlaylistCommand.EXPORT) PlaylistMessages.SEND_FAILED else PlaylistMessages.IMPORT_FAILED)
        }
        return true
    }

    private suspend fun exclusive(block: suspend () -> Unit) {
        if (!running.compareAndSet(false, true)) { reply(PlaylistMessages.BUSY); return }
        try {
            coroutineScope {
                val notice = launch { delay(workingNoticeMs); reply(PlaylistMessages.WORKING) }
                try { block() } finally { notice.cancel() }
            }
        } finally {
            running.set(false)
        }
    }

    private suspend fun doExport() {
        when (val r = transfer.export()) {
            ExportResult.Empty -> reply(PlaylistMessages.NOTHING_TO_EXPORT)
            ExportResult.TooLarge -> reply(PlaylistMessages.TOO_LARGE)
            ExportResult.Failed -> reply(PlaylistMessages.SEND_FAILED)
            is ExportResult.Sent -> Unit // the document's caption already says how many
        }
    }

    private suspend fun doImport(msg: IncomingMessage) {
        val (messageId, doc) = when {
            msg.document != null -> msg.messageId to msg.document
            msg.replyToMessageId != null -> msg.replyToMessageId to (try {
                gateway.fetchDocument(msg.replyToMessageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramError) {
                null
            })
            else -> { reply(PlaylistMessages.USAGE_IMPORT); return }
        }
        if (doc == null) { reply(PlaylistMessages.USAGE_IMPORT); return }
        when (val r = transfer.import(messageId, doc)) {
            is ImportOutcome.Done -> reply(PlaylistMessages.importReport(r.report))
            is ImportOutcome.Rejected -> reply(PlaylistMessages.rejection(r.reason))
            ImportOutcome.FileUnavailable -> reply(PlaylistMessages.FILE_UNAVAILABLE)
        }
    }

    private suspend fun reply(text: String) {
        try {
            messenger.sendText(text, null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
