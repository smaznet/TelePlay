package com.smaznet.teleplay.ui.nav

import androidx.compose.ui.graphics.ImageBitmap
import com.smaznet.teleplay.data.repo.ConnectionRepository
import com.smaznet.teleplay.data.repo.VideoRepository
import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.domain.CacheStats
import com.smaznet.teleplay.domain.DisconnectUseCase
import com.smaznet.teleplay.domain.PairingService
import com.smaznet.teleplay.data.update.ApkInstaller
import com.smaznet.teleplay.domain.remote.PlaybackHub
import com.smaznet.teleplay.domain.update.AppUpdater
import com.smaznet.teleplay.domain.remote.RemoteSettings
import com.smaznet.teleplay.domain.proxy.ProxyHealthCheck
import com.smaznet.teleplay.domain.proxy.ProxyRepository
import com.smaznet.teleplay.domain.proxy.ProxyTesting
import com.smaznet.teleplay.setup.SetupHost
import com.smaznet.teleplay.domain.Video

/** Chunk cache operations the Settings screen needs. Blocking; called off the main thread. */
interface CacheControl {
    fun usage(): CacheStats
    fun clear()
}

/** Everything the UI needs from the rest of the app. Implemented by the ServiceLocator. */
interface AppContainer {
    val connections: ConnectionRepository
    val pairing: PairingService
    val videos: VideoRepository
    val disconnect: DisconnectUseCase
    val cacheControl: CacheControl
    val remoteSettings: RemoteSettings

    /** Saved proxies, the Telegram on/off switch, and the selected proxy. */
    val proxies: ProxyRepository

    /** Tests one proxy against Telegram. */
    val proxyTester: ProxyTesting

    /** Tests every saved proxy and records which work. */
    val proxyHealth: ProxyHealthCheck

    /** Viewer-started app update from the Telegram update channel (Settings). */
    val updater: AppUpdater

    /** Hands a downloaded update to the system installer. */
    val installer: ApkInstaller

    /** Bridge to the Telegram remote; the nav host applies its skip commands. */
    val playbackHub: PlaybackHub

    /** Loads a thumbnail for a video, or null when none is available. */
    val thumbnails: suspend (Video) -> ImageBitmap?

    /** Polls for new messages now (UpdateLoop.pollNow()). */
    val refresh: suspend () -> Unit

    /** Signs in with a bot token (ConnectionRepository.connect plus starting the gateway). Never log the token. */
    suspend fun submitToken(token: String): Result<BotInfo>

    /** A new phone-setup host (own session and server) for one token-entry screen; null when unsupported. */
    fun createSetupHost(): SetupHost?
}

/** Implemented by the Application class so MainActivity can find the container. */
interface AppContainerProvider {
    val appContainer: AppContainer
}
