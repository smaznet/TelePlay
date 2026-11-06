package com.smaznet.teleplay.domain.proxy

import com.smaznet.teleplay.data.telegram.BotMessenger
import com.smaznet.teleplay.data.telegram.TelegramGateway
import com.smaznet.teleplay.domain.IncomingMessage
import com.smaznet.teleplay.domain.TelegramError
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `/proxies` bot command (constitution v2.4.0, Principle III), accepted only from the authorized user. Proxy
 * links are read only from the message carrying the command, or from the message it replies to; anything else,
 * from anyone, is not recognised here and flows on as before. Replies carry counts only. One import at a time.
 */
class ProxyCommands(
    private val authorizedUserId: () -> Long?,
    private val importer: ProxyImport,
    private val gateway: TelegramGateway,
    private val messenger: BotMessenger,
    private val proxyIsOff: () -> Boolean,
    /** The bot's username when known; a `/cmd@other` suffix is ignored. */
    private val botUsername: () -> String? = { null },
) {
    private val running = AtomicBoolean(false)

    /** True if [msg] is `/proxies` from the authorized user (never answers anyone else). */
    fun recognise(msg: IncomingMessage): Boolean {
        val authorized = authorizedUserId() ?: return false
        if (msg.senderUserId != authorized) return false
        val token = msg.text?.trimStart()?.takeIf { it.startsWith("/") }?.split(Regex("\\s+"), limit = 2)?.first() ?: return false
        val name = token.substring(1).substringBefore('@').lowercase()
        val suffix = token.substringAfter('@', "")
        if (suffix.isNotEmpty() && !suffix.equals(botUsername(), ignoreCase = true)) return false
        return name == "proxies"
    }

    /** Where the links come from: the command's own message, the message it replies to, or nowhere. */
    private sealed interface Source {
        class Text(val value: String) : Source
        data object Nothing : Source
        data object Unreadable : Source
    }

    /** Runs the command in [msg]; returns false when [msg] is not ours. */
    suspend fun handle(msg: IncomingMessage): Boolean {
        if (!recognise(msg)) return false
        if (!running.compareAndSet(false, true)) { reply(ProxyMessages.BUSY); return true }
        try {
            when (val source = sourceOf(msg)) {
                is Source.Text -> reply(ProxyMessages.report(importer.import(source.value), proxyIsOff()))
                Source.Nothing -> reply(ProxyMessages.USAGE)
                Source.Unreadable -> reply(ProxyMessages.CANNOT_READ)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reply(ProxyMessages.FAILED)
        } finally {
            running.set(false)
        }
        return true
    }

    private suspend fun sourceOf(msg: IncomingMessage): Source {
        val own = msg.text.orEmpty()
        if (ProxyLinkParser.extractLinks(own).isNotEmpty()) return Source.Text(own)
        val id = msg.replyToMessageId ?: return Source.Nothing
        val fetched = try {
            gateway.fetchMessages(listOf(id)).firstOrNull { it.messageId == id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TelegramError) {
            null
        } ?: return Source.Unreadable
        val text = fetched.text.orEmpty()
        return if (ProxyLinkParser.extractLinks(text).isEmpty()) Source.Nothing else Source.Text(text)
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
