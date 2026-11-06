package com.smaznet.teleplay.ui.library

import com.smaznet.teleplay.domain.Availability
import com.smaznet.teleplay.domain.PlaybackProgress
import com.smaznet.teleplay.domain.Video
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LibrarySections(val continueWatching: List<Video>, val all: List<Video>)

val Video.isPlayable: Boolean get() = availability == Availability.PLAYABLE

/** Library newest-received first; Continue watching keeps the repository order but only playable items. */
fun buildSections(videos: List<Video>, continueWatching: List<Video>): LibrarySections =
    LibrarySections(
        continueWatching = continueWatching.filter { it.isPlayable },
        all = videos.sortedByDescending { it.receivedAt },
    )

/** The video the hero banner features: what to resume first, else the newest. */
fun LibrarySections.featured(): Video? = continueWatching.firstOrNull() ?: all.firstOrNull()

sealed interface FocusTarget {
    /** The hero's Play / Resume button. */
    data object Hero : FocusTarget
    data class Continue(val videoId: Long) : FocusTarget
    data class Main(val videoId: Long) : FocusTarget
    data object Refresh : FocusTarget
}

/** Deterministic initial focus: the video returned from the player, else the hero's Play button, else Refresh. */
fun initialFocus(sections: LibrarySections, lastPlayedId: Long?): FocusTarget {
    if (lastPlayedId != null) {
        if (sections.continueWatching.any { it.id == lastPlayedId }) return FocusTarget.Continue(lastPlayedId)
        if (sections.all.any { it.id == lastPlayedId }) return FocusTarget.Main(lastPlayedId)
    }
    if (sections.featured() != null) return FocusTarget.Hero
    return FocusTarget.Refresh
}

/** Index of the lazy list item holding the "All videos" shelf (hero=0, then the optional Continue shelf). */
fun mainShelfItemIndex(sections: LibrarySections): Int = 1 + (if (sections.continueWatching.isNotEmpty()) 1 else 0)

fun progressFraction(p: PlaybackProgress?): Float {
    if (p == null || p.completed || p.durationMs <= 0 || p.positionMs <= 0) return 0f
    return (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f)
}

fun isPartlyWatched(p: PlaybackProgress?): Boolean = progressFraction(p) > 0f

fun formatDuration(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

fun formatSize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0).toDouble()
    return when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / (1L shl 30))
        b >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", b / (1L shl 20))
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / (1L shl 10))
        else -> "${b.toLong()} B"
    }
}

fun formatDate(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

/** mm:ss countdown. */
fun formatCountdown(remainingMs: Long): String {
    val s = (remainingMs.coerceAtLeast(0) + 999) / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** "14 min left" / "1 h 5 min left" for a partly watched video. */
fun formatRemaining(remainingMs: Long): String {
    val totalMin = ((remainingMs.coerceAtLeast(0) + 59_999) / 60_000).toInt().coerceAtLeast(1)
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0 -> "$m min left"
        m == 0 -> "$h h left"
        else -> "$h h $m min left"
    }
}
