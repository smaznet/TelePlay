package com.smaznet.teleplay.domain.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PlayState { PLAYING, PAUSED, BUFFERING, ENDED }

/** What the TV player is doing right now; null in the hub means no player exists. */
data class PlaybackSnapshot(
    val videoId: Long,
    val title: String,
    val state: PlayState,
    val positionMs: Long,
    val durationMs: Long,
    val hasPrev: Boolean,
    val hasNext: Boolean,
    val capturedAt: Long,
)

sealed class PlaybackCommand {
    object Play : PlaybackCommand()
    object Pause : PlaybackCommand()
    data class SeekBy(val deltaMs: Long) : PlaybackCommand()
    data class SkipTo(val videoId: Long) : PlaybackCommand()
}

/**
 * App-scoped bridge between the UI-owned player and the background remote: the player publishes
 * snapshots, the remote sends commands that the player (and the nav host, for skip) collect.
 */
class PlaybackHub {
    private val _snapshot = MutableStateFlow<PlaybackSnapshot?>(null)
    private val _commands = MutableSharedFlow<PlaybackCommand>(extraBufferCapacity = 16)

    @Volatile private var order: List<Long> = emptyList()

    val snapshot: StateFlow<PlaybackSnapshot?> = _snapshot.asStateFlow()
    val commands: SharedFlow<PlaybackCommand> = _commands.asSharedFlow()

    fun publish(s: PlaybackSnapshot?) { _snapshot.value = s }

    /** Clears the snapshot only if it still belongs to [videoId] (a newer player may already have published). */
    fun retire(videoId: Long) { _snapshot.update { if (it?.videoId == videoId) null else it } }

    suspend fun send(cmd: PlaybackCommand) { _commands.emit(cmd) }

    /** Playable video ids in library order (newest first). */
    fun setLibraryOrder(playableIds: List<Long>) { order = playableIds }

    /**
     * The video after ([forward] = next) or before the given one in library order; null at either end
     * or when the video is not in the list. Never wraps around.
     */
    fun neighborOf(videoId: Long, forward: Boolean): Long? {
        val list = order
        val i = list.indexOf(videoId)
        if (i < 0) return null
        return list.getOrNull(if (forward) i + 1 else i - 1)
    }
}
