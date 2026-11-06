package com.smaznet.teleplay.domain

import com.smaznet.teleplay.data.repo.ConnectionRepository
import com.smaznet.teleplay.data.repo.VideoRepository

/** Deletes the on-disk chunk cache. */
fun interface CacheWiper { fun wipe() }

/** Closes DC connections and erases sessions and the update state (e.g. TelegramGateway.signOutAndWipe). */
fun interface GatewayWiper { suspend fun wipe() }

/** Erases encrypted secrets: bot token and DC sessions (SecretStore.wipe). */
fun interface SessionWiper { fun wipe() }

/** Ends the Telegram remote (unpins and finalizes its message); runs first, while the bot token still works. */
fun interface RemoteWiper { suspend fun wipe() }

/**
 * Disconnect: erases everything and ends in SIGNED_OUT. Every step runs even if an earlier one fails;
 * the first failure is rethrown at the end.
 */
class DisconnectUseCase(
    private val connections: ConnectionRepository,
    private val videos: VideoRepository,
    private val gatewayWiper: GatewayWiper,
    private val sessionWiper: SessionWiper,
    private val cacheWiper: CacheWiper,
    private val remoteWiper: RemoteWiper = RemoteWiper { },
) {
    suspend operator fun invoke() {
        var failure: Throwable? = null
        suspend fun step(f: suspend () -> Unit) {
            try { f() } catch (e: Throwable) { if (failure == null) failure = e }
        }
        step { remoteWiper.wipe() }
        step { gatewayWiper.wipe() }
        step { sessionWiper.wipe() }
        step { cacheWiper.wipe() }
        step { videos.clearAll() }
        step { connections.signOut() }
        failure?.let { throw it }
    }
}
