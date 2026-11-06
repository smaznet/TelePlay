package com.smaznet.teleplay.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.smaznet.teleplay.data.cache.ChunkCache
import com.smaznet.teleplay.data.telegram.TelegramGateway
import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.domain.Video
import com.smaznet.teleplay.domain.remote.PlayState
import com.smaznet.teleplay.domain.remote.PlaybackCommand
import com.smaznet.teleplay.domain.remote.PlaybackHub
import com.smaznet.teleplay.domain.remote.PlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Playback failures shown to the user as plain-language states. */
sealed class PlayerError {
    object Offline : PlayerError()
    /** Telegram is unreachable through the proxy that is switched on. */
    object ProxyFailed : PlayerError()
    class RateLimited(val retryAfterSec: Int) : PlayerError()
    object LinkExpired : PlayerError()
    object UnplayableFormat : PlayerError()
    object Unknown : PlayerError()
}

/**
 * Owns an ExoPlayer for one [video]. Create and use on the main thread. Saves progress through
 * [progress] every 5 s while playing and on pause/release; calls [onUnplayable] when the format
 * cannot be played.
 */
class PlayerController(
    context: Context,
    private val video: Video,
    private val cache: ChunkCache,
    private val gateway: TelegramGateway,
    private val progress: ProgressSink,
    private val onUnplayable: (Video) -> Unit,
    private val startPositionMs: Long = 0,
    /** When set, the player publishes its state here and obeys play/pause/seek commands from the Telegram remote. */
    private val hub: PlaybackHub? = null,
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val factory = CachedChunkDataSource.Factory(video, cache, gateway, ioScope)

    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _position = MutableStateFlow(startPositionMs)
    private val _duration = MutableStateFlow(video.durationSec * 1000L)
    private val _buffered = MutableStateFlow(startPositionMs)
    private val _buffering = MutableStateFlow(true)
    private val _playing = MutableStateFlow(false)
    private val _error = MutableStateFlow<PlayerError?>(null)
    val positionMs: StateFlow<Long> = _position
    val durationMs: StateFlow<Long> = _duration
    /** How far ahead of the start the video is loaded, in ms (ExoPlayer's buffered position). */
    val bufferedMs: StateFlow<Long> = _buffered
    val isBuffering: StateFlow<Boolean> = _buffering
    val isPlaying: StateFlow<Boolean> = _playing
    val error: StateFlow<PlayerError?> = _error

    /** Display aspect ratio (width / height) of the video, 0 until known. Starts from the stored size. */
    private val _aspect = MutableStateFlow(if (video.width > 0 && video.height > 0) video.width.toFloat() / video.height else 0f)
    val videoAspect: StateFlow<Float> = _aspect

    private var released = false

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _aspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            _buffering.value = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) updateDuration()
            if (state == Player.STATE_ENDED) { _position.value = _duration.value; saveProgress() }
            publishSnapshot()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playing.value = isPlaying
            if (!isPlaying) saveProgress()
            publishSnapshot()
        }

        override fun onPlayerError(e: PlaybackException) {
            val mapped = map(e)
            _error.value = mapped
            if (mapped is PlayerError.UnplayableFormat) onUnplayable(video)
            saveProgress()
        }
    }

    init {
        cache.setInUse(video.documentId)
        player.addListener(listener)
        prepare(startPositionMs)
        if (hub != null) uiScope.launch {
            hub.commands.collect { cmd ->
                when (cmd) {
                    PlaybackCommand.Play -> play()
                    PlaybackCommand.Pause -> pause()
                    is PlaybackCommand.SeekBy -> seekBy(cmd.deltaMs)
                    is PlaybackCommand.SkipTo -> Unit // handled by navigation
                }
            }
        }
        uiScope.launch {
            var sinceSave = 0L
            while (isActive) {
                delay(TICK_MS)
                _position.value = player.currentPosition
                _buffered.value = player.bufferedPosition
                publishSnapshot()
                if (player.isPlaying) {
                    sinceSave += TICK_MS
                    if (sinceSave >= SAVE_INTERVAL_MS) { sinceSave = 0; saveProgress() }
                } else sinceSave = 0
            }
        }
    }

    private fun prepare(positionMs: Long) {
        val source = ProgressiveMediaSource.Factory(factory, DefaultExtractorsFactory())
            .createMediaSource(MediaItem.fromUri(Uri.parse("teleplay://video/${video.id}")))
        player.setMediaSource(source, positionMs)
        player.prepare()
        player.playWhenReady = true
    }

    fun play() { player.play() }
    fun pause() { player.pause() }
    fun togglePlayPause() { if (player.isPlaying) pause() else play() }

    fun seekTo(ms: Long) {
        player.seekTo(ms.coerceIn(0, _duration.value.takeIf { it > 0 } ?: Long.MAX_VALUE))
        _position.value = player.currentPosition
    }

    fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    /** Retry after an error, resuming from the last position. */
    fun retry() {
        _error.value = null
        _buffering.value = true
        prepare(_position.value)
    }

    private var lastPublished: PlaybackSnapshot? = null

    /** Publishes to the hub when the state, video or position (by at least 1 s) changed. */
    private fun publishSnapshot() {
        val h = hub ?: return
        if (released) return
        val ended = player.playbackState == Player.STATE_ENDED
        val state = when {
            ended -> PlayState.ENDED
            _buffering.value -> PlayState.BUFFERING
            player.playWhenReady && player.isPlaying -> PlayState.PLAYING
            else -> PlayState.PAUSED
        }
        val pos = player.currentPosition.coerceAtLeast(0)
        val last = lastPublished
        if (last != null && last.state == state && kotlin.math.abs(last.positionMs - pos) < 1_000) return
        val snap = PlaybackSnapshot(
            videoId = video.id, title = video.title, state = state, positionMs = pos, durationMs = _duration.value,
            hasPrev = h.neighborOf(video.id, forward = false) != null,
            hasNext = h.neighborOf(video.id, forward = true) != null,
            capturedAt = System.currentTimeMillis(),
        )
        lastPublished = snap
        h.publish(snap)
    }

    fun release() {
        if (released) return
        released = true
        hub?.retire(video.id)
        saveProgress()
        uiScope.cancel()
        player.removeListener(listener)
        player.release()
        ioScope.cancel()
        cache.setInUse(null)
    }

    private fun updateDuration() {
        val d = player.duration
        if (d != C.TIME_UNSET && d > 0) _duration.value = d
    }

    private fun saveProgress() {
        val d = _duration.value
        if (d <= 0) return
        progress.save(video.id, player.currentPosition.coerceAtLeast(0), d)
    }

    private fun map(e: PlaybackException): PlayerError {
        var c: Throwable? = e
        while (c != null) {
            when (c) {
                is TelegramError.Offline -> return if (c.cause is com.smaznet.teleplay.mtproto.transport.ProxyException) PlayerError.ProxyFailed else PlayerError.Offline
                is TelegramError.RateLimited -> return PlayerError.RateLimited(c.retryAfterSec)
                is TelegramError.FileReferenceExpired, is TelegramError.FileUnavailable -> return PlayerError.LinkExpired
                is TelegramError.Unsupported -> return PlayerError.UnplayableFormat
            }
            c = c.cause?.takeIf { it !== c }
        }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> PlayerError.UnplayableFormat
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> PlayerError.Offline
            else -> PlayerError.Unknown
        }
    }

    companion object {
        const val TICK_MS = 500L
        const val SAVE_INTERVAL_MS = 5_000L
    }
}
