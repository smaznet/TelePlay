package com.smaznet.teleplay.domain.playlist

/** Fixed reply texts (contracts/commands.md). Nothing here takes a token, code or session value. */
object PlaylistMessages {
    const val NOTHING_TO_EXPORT = "Nothing to export yet"
    const val USAGE_IMPORT = "Reply /import to a TelePlay backup file, or send the file with /import as its caption"
    const val NOT_BACKUP = "That file is not a TelePlay backup"
    const val NEWER_VERSION = "This backup is from a newer TelePlay version, please update"
    const val DAMAGED = "The backup file is damaged"
    const val TOO_LARGE = "The backup file is too large"
    const val BUSY = "Another export or import is already running"
    const val SEND_FAILED = "Could not send the backup, please try again"
    const val IMPORT_FAILED = "Could not finish the import, please try again. Videos already restored are kept"
    const val FILE_UNAVAILABLE = "Could not read the backup file, please send it again"
    const val WORKING = "Working on it..."
    const val STOPPED_EARLY = "Stopped early because Telegram asked us to slow down. Run /import again to continue."
    const val DIFFERENT_BOT = "This backup came from a different bot, so some videos may be unreachable."

    const val HELP = "TelePlay playlist backup\n\n" +
        "/export - send me a backup file of your playlist (titles and watch progress)\n" +
        "/import - restore your playlist: reply /import to a backup file, or send the file with /import as its caption\n" +
        "/proxies - add proxy links: send /proxies followed by SOCKS5 or MTProto proxy links, or reply /proxies to a message that has them (they are saved, not turned on)\n" +
        "/help - show this message\n\n" +
        "Restoring works with the same bot that made the backup."

    fun exported(count: Int) = "Playlist backup: $count video" + if (count == 1) "" else "s"

    fun rejection(reason: RejectReason) = when (reason) {
        RejectReason.NOT_BACKUP -> NOT_BACKUP
        RejectReason.NEWER_VERSION -> NEWER_VERSION
        RejectReason.DAMAGED -> DAMAGED
        RejectReason.TOO_LARGE -> TOO_LARGE
    }

    fun importReport(r: ImportReport): String = buildString {
        append("Import finished\n")
        append("Restored: ").append(r.restored).append('\n')
        append("Already in library: ").append(r.alreadyPresent).append('\n')
        append("Skipped: ").append(r.skipped)
        if (r.skipped > 0) append(" (not found ").append(r.notFound).append(", changed ").append(r.changed)
            .append(", unreachable ").append(r.unreachable).append(')')
        if (r.stoppedEarly) append('\n').append(STOPPED_EARLY)
        if (r.botMismatch) append('\n').append(DIFFERENT_BOT)
    }
}
