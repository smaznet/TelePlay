package com.smaznet.teleplay.domain.remote

import com.smaznet.teleplay.data.telegram.BotMessenger
import kotlinx.coroutines.CancellationException

/**
 * Turns a Telegram button press into a player command (contracts/remote-message.md, "Press handling").
 * Only the authorized user's presses on the current session's message act; everything is answered once
 * so the button spinner stops.
 */
class RemoteCommandHandler(
    private val hub: PlaybackHub,
    private val messenger: BotMessenger,
    private val authorizedUserId: () -> Long?,
    private val currentSessionId: () -> String?,
    /** Called after an accepted command so the message can be refreshed shortly after. */
    private val onAccepted: () -> Unit,
    /** Called for a press on an out-of-date message (the session may need to finalize it). */
    private val onStale: suspend (CallbackQuery) -> Unit = {},
) {
    suspend fun handle(q: CallbackQuery) {
        val authorized = authorizedUserId()
        if (authorized == null || q.userId != authorized) return // never answered, never acted on

        val parsed = MessageFormatter.parse(q.data)
        if (parsed == null) { answer(q, null); return }
        val (sessionId, action) = parsed
        if (sessionId != currentSessionId()) {
            answer(q, STALE)
            onStale(q)
            return
        }
        val snap = hub.snapshot.value
        if (snap == null) { answer(q, NOTHING_PLAYING); return }

        val cmd: PlaybackCommand = when (action) {
            MessageFormatter.TOGGLE ->
                if (snap.state == PlayState.PLAYING || snap.state == PlayState.BUFFERING) PlaybackCommand.Pause else PlaybackCommand.Play
            MessageFormatter.BACK -> PlaybackCommand.SeekBy(clampDelta(snap, -MessageFormatter.SEEK_STEP_MS))
            MessageFormatter.FORWARD -> PlaybackCommand.SeekBy(clampDelta(snap, MessageFormatter.SEEK_STEP_MS))
            MessageFormatter.NEXT -> hub.neighborOf(snap.videoId, forward = true)?.let { PlaybackCommand.SkipTo(it) }
                ?: run { answer(q, NO_NEXT); return }
            MessageFormatter.PREV -> hub.neighborOf(snap.videoId, forward = false)?.let { PlaybackCommand.SkipTo(it) }
                ?: run { answer(q, NO_PREVIOUS); return }
            else -> { answer(q, null); return }
        }
        hub.send(cmd)
        answer(q, null)
        onAccepted()
    }

    /** The delta that keeps the position inside `[0, duration]`. */
    private fun clampDelta(s: PlaybackSnapshot, delta: Long): Long {
        if (s.durationMs <= 0) return delta
        val target = (s.positionMs + delta).coerceIn(0, s.durationMs)
        return target - s.positionMs
    }

    private suspend fun answer(q: CallbackQuery, toast: String?) {
        try {
            messenger.answerCallback(q.queryId, toast)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    companion object {
        const val STALE = "This remote is out of date"
        const val NO_NEXT = "No next video"
        const val NO_PREVIOUS = "No previous video"
        const val NOTHING_PLAYING = "Nothing is playing"
    }
}
