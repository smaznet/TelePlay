package com.smaznet.teleplay.ui.player

import com.smaznet.teleplay.R
import com.smaznet.teleplay.playback.PlayerError

/** Pure helpers for the player screen (JVM-testable). */
object PlayerLogic {
    const val OVERLAY_HIDE_MS = 4_000L
    /** Key repeats between seeks while a seek key is held. */
    const val REPEATS_PER_SEEK = 5

    /**
     * Seek delta in ms (always positive) for a seek key event with the given Android repeatCount, or 0 if
     * this repeat should not seek. First press 10 s; held: 10 s, then 30 s, then 60 s.
     */
    fun seekStepMs(repeatCount: Int): Long = when {
        repeatCount <= 0 -> 10_000L
        repeatCount % REPEATS_PER_SEEK != 0 -> 0L
        repeatCount < 10 -> 10_000L
        repeatCount < 30 -> 30_000L
        else -> 60_000L
    }

    /** m:ss, or h:mm:ss from one hour; negative clamps to 0. */
    fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0)) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun progressFraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** Strings and actions for an error panel. */
data class ErrorSpec(val titleRes: Int, val bodyRes: Int, val canRetry: Boolean, val countdownSec: Int? = null)

fun PlayerError.toSpec(): ErrorSpec = when (this) {
    PlayerError.Offline -> ErrorSpec(R.string.player_error_offline_title, R.string.player_error_offline_body, true)
    PlayerError.ProxyFailed -> ErrorSpec(R.string.player_error_proxy_title, R.string.player_error_proxy_body, true)
    is PlayerError.RateLimited ->
        ErrorSpec(R.string.player_error_rate_title, R.string.player_error_rate_body, true, retryAfterSec.coerceAtLeast(1))
    PlayerError.LinkExpired -> ErrorSpec(R.string.player_error_expired_title, R.string.player_error_expired_body, true)
    PlayerError.UnplayableFormat ->
        ErrorSpec(R.string.player_error_format_title, R.string.player_error_format_body, false)
    PlayerError.Unknown -> ErrorSpec(R.string.player_error_unknown_title, R.string.player_error_unknown_body, true)
}
