package com.smaznet.teleplay.ui.player

import com.smaznet.teleplay.domain.Video
import com.smaznet.teleplay.playback.PlayerController

/** What the player screen needs from the app; implemented by the caller (nav layer). */
interface PlayerDeps {
    suspend fun video(id: Long): Video?
    suspend fun resumePositionMs(id: Long): Long?
    suspend fun startOver(id: Long)
    fun createController(video: Video, startPositionMs: Long): PlayerController
}
