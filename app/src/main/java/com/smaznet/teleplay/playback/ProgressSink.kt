package com.smaznet.teleplay.playback

/** Receives playback progress; implemented by the storage layer. */
interface ProgressSink {
    fun save(videoId: Long, positionMs: Long, durationMs: Long)
}
