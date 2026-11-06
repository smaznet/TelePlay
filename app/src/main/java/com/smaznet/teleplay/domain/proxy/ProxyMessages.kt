package com.smaznet.teleplay.domain.proxy

/** Fixed reply texts (contracts/commands.md). Nothing here takes a proxy address, credential or secret. */
object ProxyMessages {
    const val USAGE = "Send /proxies followed by proxy links, or reply /proxies to a message that has them"
    const val CANNOT_READ = "Could not read that message"
    const val BUSY = "Another proxy import is already running"
    const val FAILED = "Could not import the proxies, please try again"
    const val SAVED_BUT_OFF = "Proxies are saved but turned off. Turn one on from the Home switch or Settings > Proxies."

    fun report(c: ImportCounts, proxyIsOff: Boolean): String = buildString {
        append("Proxy import finished\n")
        append("Added: ").append(c.added).append('\n')
        append("Already saved: ").append(c.duplicate).append('\n')
        append("Invalid: ").append(c.invalid)
        if (c.skipped > 0) append("\nSkipped: ").append(c.skipped).append(" (limit reached)")
        if (c.added > 0 && proxyIsOff) append('\n').append(SAVED_BUT_OFF)
    }
}
