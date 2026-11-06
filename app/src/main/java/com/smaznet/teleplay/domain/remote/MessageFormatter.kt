package com.smaznet.teleplay.domain.remote

/**
 * Text and keyboard layouts of everything the bot sends (contracts/remote-message.md). Pure; takes only
 * titles and playback numbers, never tokens or pairing codes.
 */
object MessageFormatter {
    const val MAX_TITLE = 80
    const val MAX_LISTED = 10
    const val SEEK_STEP_MS = 30_000L
    private const val BAR_CELLS = 10

    fun addedSingle(title: String) = "Added to TelePlay: ${clip(title)}"

    fun duplicateSingle(title: String) = "Already in your library: ${clip(title)}"

    /** Grouped notice for a burst; [added] and [duplicates] are titles. */
    fun addedBatch(added: List<String>, duplicates: List<String>): String = buildString {
        if (added.isNotEmpty()) {
            append("Added ${added.size} video${if (added.size == 1) "" else "s"}:")
            added.take(MAX_LISTED).forEach { append("\n• ").append(clip(it)) }
            if (added.size > MAX_LISTED) append("\n…and ${added.size - MAX_LISTED} more")
        }
        if (duplicates.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append("${duplicates.size} already in the library")
        }
    }

    fun nowPlaying(s: PlaybackSnapshot): String {
        val head = when (s.state) {
            PlayState.PLAYING -> "▶ Now playing"
            PlayState.PAUSED -> "⏸ Paused"
            PlayState.BUFFERING -> "⏳ Buffering"
            PlayState.ENDED -> "✔ Finished"
        }
        return "$head\n${clip(s.title)}\n${bar(s.positionMs, s.durationMs)}  ${time(s.positionMs)} / ${time(s.durationMs)}"
    }

    /** Final state; a blank [title] (e.g. a leftover message from an earlier run) gives just the state line. */
    fun finalText(title: String, finished: Boolean): String {
        val head = if (finished) "✔ Finished" else "⏹ Stopped"
        return if (title.isBlank()) head else "$head\n${clip(title)}"
    }

    /** One row: previous, back, play/pause, forward, next. */
    fun keyboard(sessionId: String, s: PlaybackSnapshot): List<List<RemoteButton>> = listOf(
        listOf(
            RemoteButton("⏮", data(sessionId, PREV)),
            RemoteButton("⏪ 30s", data(sessionId, BACK)),
            RemoteButton(if (s.state == PlayState.PLAYING || s.state == PlayState.BUFFERING) "⏸" else "▶", data(sessionId, TOGGLE)),
            RemoteButton("30s ⏩", data(sessionId, FORWARD)),
            RemoteButton("⏭", data(sessionId, NEXT)),
        ),
    )

    const val TOGGLE = "pp"
    const val BACK = "sb"
    const val FORWARD = "sf"
    const val PREV = "pv"
    const val NEXT = "nx"

    fun data(sessionId: String, action: String) = "v1:$sessionId:$action"

    /** (sessionId, action) of a callback payload, or null when malformed. */
    fun parse(data: String): Pair<String, String>? {
        val m = DATA.matchEntire(data) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    fun bar(positionMs: Long, durationMs: Long): String {
        val filled = if (durationMs <= 0) 0 else (positionMs.coerceAtLeast(0).toDouble() / durationMs * BAR_CELLS).toInt().coerceIn(0, BAR_CELLS)
        return "▰".repeat(filled) + "▱".repeat(BAR_CELLS - filled)
    }

    /** m:ss, or h:mm:ss from one hour; negative clamps to 0 (same style as the player). */
    fun time(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun clip(title: String): String {
        val t = title.trim().replace('\n', ' ')
        return if (t.length <= MAX_TITLE) t else t.take(MAX_TITLE - 1) + "…"
    }

    private val DATA = Regex("^v1:([A-Za-z0-9]{8}):(pp|sb|sf|pv|nx)$")
}
