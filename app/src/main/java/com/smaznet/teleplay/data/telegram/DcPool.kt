package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.mtproto.Rpc
import com.smaznet.teleplay.mtproto.session.DcConnection
import com.smaznet.teleplay.mtproto.tl.generated.F_auth_importBotAuthorization
import com.smaznet.teleplay.mtproto.transport.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns one [DcConnection] per data center. [authorized] guarantees that non-home DCs were given the
 * bot authorization (new auth key + `auth.importBotAuthorization` with the stored token) first.
 */
class DcPool(
    private val factory: TransportFactory,
    private val scope: CoroutineScope,
    private val directory: DcDirectory,
    private val sessions: SessionStore,
    private val tokens: TokenStore,
    private val apiId: Int,
    private val apiHash: String,
    private val onUpdatesPushed: () -> Unit,
    private val onPushedPayload: (ByteArray) -> Unit = {},
) {
    private val conns = HashMap<Int, DcConnection>()
    private val authorizedDcs = HashSet<Int>()
    private val authMutex = Mutex()

    @Volatile
    var homeDc: Int = DcDirectory.DEFAULT_DC
        private set

    /** Used by the authorization calls themselves, so it never triggers authorization. */
    lateinit var authRpc: Rpc

    fun setHome(dcId: Int) {
        homeDc = dcId
        synchronized(this) { authorizedDcs.add(dcId) }
        raw(dcId).start()
    }

    fun raw(dcId: Int): DcConnection = synchronized(this) {
        conns.getOrPut(dcId) {
            val a = directory.address(dcId)
            DcConnection(
                dcId = dcId, host = a.host, port = a.port, factory = factory, scope = scope,
                saved = sessions.load(dcId),
                onSessionChanged = { sessions.save(it) },
                onUpdatesPushed = { if (dcId == homeDc) onUpdatesPushed() },
                onPushedPayload = { if (dcId == homeDc) onPushedPayload(it) },
            )
        }
    }

    suspend fun authorized(dcId: Int): DcConnection {
        val known = synchronized(this) { dcId in authorizedDcs }
        if (!known) {
            authMutex.withLock {
                if (synchronized(this) { dcId in authorizedDcs }) return@withLock
                val token = tokens.load() ?: throw TelegramError.TokenRejected()
                try {
                    authRpc.invoke(F_auth_importBotAuthorization(0, apiId, apiHash, token), dcId)
                } catch (e: TelegramError.AuthKeyLost) {
                    dropKey(dcId)
                    authRpc.invoke(F_auth_importBotAuthorization(0, apiId, apiHash, token), dcId)
                }
                synchronized(this) { authorizedDcs.add(dcId) }
            }
        }
        return raw(dcId)
    }

    /** Forgets the key of [dcId] so the next connect creates a new one. */
    fun dropKey(dcId: Int) {
        sessions.clear(dcId)
        synchronized(this) { authorizedDcs.remove(dcId) }
        raw(dcId).discardKey()
    }

    /** Drops every live transport but keeps keys and authorization, so the next connect takes the current proxy route. */
    fun resetConnections() {
        val all = synchronized(this) { conns.toMap() }
        all.forEach { (dc, c) -> c.reconnect(restart = dc == homeDc) }
    }

    fun closeAll() {
        val all = synchronized(this) { conns.values.toList().also { conns.clear(); authorizedDcs.clear() } }
        all.forEach { it.close() }
        homeDc = DcDirectory.DEFAULT_DC
    }
}
