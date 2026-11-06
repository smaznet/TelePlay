package com.smaznet.teleplay.domain.playlist

import com.smaznet.teleplay.data.telegram.BotMessenger
import kotlinx.coroutines.CancellationException

/** Registers the bot commands in the bot's menu. A failure is ignored; the next call retries. */
class CommandMenuRegistrar(private val messenger: BotMessenger) {
    suspend fun register() {
        try {
            messenger.setCommands(COMMANDS)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    companion object {
        val COMMANDS = listOf(
            "export" to "Save my playlist as a backup file",
            "import" to "Restore my playlist from a backup file",
            "help" to "How to back up and restore",
            "proxies" to "Add proxy links to TelePlay",
        )
    }
}
