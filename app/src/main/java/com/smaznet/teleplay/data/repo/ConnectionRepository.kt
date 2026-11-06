package com.smaznet.teleplay.data.repo

import com.smaznet.teleplay.data.secure.SecretStore
import com.smaznet.teleplay.data.telegram.TelegramGateway
import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.domain.BotConnection
import com.smaznet.teleplay.domain.ConnectionState
import com.smaznet.teleplay.domain.GatewayState
import com.smaznet.teleplay.domain.TelegramError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

/** Persistence of the single BotConnection row. */
interface ConnectionStore {
    fun load(): BotConnection?
    fun save(connection: BotConnection)
    fun clear()
}

/** Where the bot token lives (encrypted). Never log values. */
interface TokenVault {
    fun get(): String?
    fun put(token: String)
    fun clear()
}

class SecretStoreTokenVault(private val secrets: SecretStore) : TokenVault {
    override fun get(): String? = secrets.get(NAME)?.toString(Charsets.UTF_8)
    override fun put(token: String) = secrets.put(NAME, token.toByteArray(Charsets.UTF_8))
    override fun clear() = secrets.remove(NAME)
    private companion object { const val NAME = "bot_token" }
}

/**
 * Connection state machine (data-model.md). Blocking persistence calls except [connect] and [observeGateway].
 * Typical wiring: `restore()` at startup, then launch `observeGateway()` in an app scope.
 */
class ConnectionRepository(
    private val store: ConnectionStore,
    private val tokens: TokenVault,
    private val gateway: TelegramGateway,
) {
    private val _connection = MutableStateFlow<BotConnection?>(null)
    private val _state = MutableStateFlow(ConnectionState.SIGNED_OUT)

    val connection: StateFlow<BotConnection?> = _connection.asStateFlow()
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    fun storedToken(): String? = tokens.get()

    /** Loads persisted state at cold start. True when a stored token and session exist and the gateway should resume. */
    fun restore(): Boolean {
        val c = store.load()
        val token = tokens.get()
        if (c == null || token == null || c.state == ConnectionState.SIGNED_OUT) {
            _connection.value = null
            _state.value = ConnectionState.SIGNED_OUT
            return false
        }
        _connection.value = c
        return when (c.state) {
            ConnectionState.REJECTED, ConnectionState.REVOKED -> { _state.value = c.state; false }
            else -> { setState(ConnectionState.CONNECTING); true }
        }
    }

    /** Signs in with a new token. On success saves the token and moves to AWAITING_PAIRING or READY. */
    suspend fun connect(token: String, homeDcId: Int = 0): Result<BotInfo> {
        val previous = _state.value
        _state.value = ConnectionState.CONNECTING
        val result = gateway.signInWithBotToken(token)
        result.onSuccess { info ->
            tokens.put(token)
            val old = store.load()?.takeIf { it.botUserId == info.botUserId }
            val authorized = old?.authorizedUserId
            val state = if (authorized != null) ConnectionState.READY else ConnectionState.AWAITING_PAIRING
            val c = BotConnection(
                info.botUserId, info.name, homeDcId.takeIf { it != 0 } ?: old?.homeDcId ?: 0, state,
                authorized, old?.pairingCodeHash, old?.pairingExpiresAt, old?.authorizedUserAccessHash,
            )
            store.save(c)
            _connection.value = c
            _state.value = state
        }.onFailure { e ->
            if (e is TelegramError.TokenRejected) {
                _state.value = ConnectionState.REJECTED
            } else {
                _state.value = if (_connection.value == null) ConnectionState.SIGNED_OUT else previous
            }
        }
        return result
    }

    /** Mirrors gateway state into the persisted state. Never returns; run it in a long-lived scope. */
    suspend fun observeGateway() {
        gateway.connectionState.collect { g ->
            val c = _connection.value ?: return@collect
            if (c.state == ConnectionState.SIGNED_OUT) return@collect
            setState(
                when (g) {
                    GatewayState.CONNECTING -> ConnectionState.CONNECTING
                    GatewayState.OFFLINE -> ConnectionState.OFFLINE
                    GatewayState.REJECTED -> ConnectionState.REJECTED
                    GatewayState.REVOKED -> ConnectionState.REVOKED
                    GatewayState.READY ->
                        if (c.authorizedUserId != null) ConnectionState.READY else ConnectionState.AWAITING_PAIRING
                },
            )
        }
    }

    /** Stores the hash of a freshly issued pairing code. */
    fun savePairingCode(hash: String, expiresAt: Long) = update { it.copy(pairingCodeHash = hash, pairingExpiresAt = expiresAt) }

    fun clearPairingCode() = update { it.copy(pairingCodeHash = null, pairingExpiresAt = null) }

    /** Pairing succeeded: records the user, invalidates the code, state READY. */
    fun completePairing(userId: Long) =
        update {
            it.copy(
                authorizedUserId = userId, authorizedUserAccessHash = if (userId == it.authorizedUserId) it.authorizedUserAccessHash else null,
                pairingCodeHash = null, pairingExpiresAt = null, state = ConnectionState.READY,
            )
        }

    /** Remembers the access hash of the authorized user (needed to message them). No-op when unchanged. */
    fun saveAuthorizedAccessHash(hash: Long) {
        val c = _connection.value ?: return
        if (c.authorizedUserId == null || c.authorizedUserAccessHash == hash) return
        update { it.copy(authorizedUserAccessHash = hash) }
    }

    /** Reset pairing: clears the authorized user, stores a new code hash, state AWAITING_PAIRING. */
    fun resetPairing(hash: String, expiresAt: Long) = update {
        it.copy(authorizedUserId = null, authorizedUserAccessHash = null, pairingCodeHash = hash, pairingExpiresAt = expiresAt, state = ConnectionState.AWAITING_PAIRING)
    }

    /** Disconnect: forgets the token and the connection row, state SIGNED_OUT. */
    fun signOut() {
        _connection.value = null
        _state.value = ConnectionState.SIGNED_OUT
        try { tokens.clear() } finally { store.clear() }
    }

    private fun setState(s: ConnectionState) = update { it.copy(state = s) }

    private fun update(f: (BotConnection) -> BotConnection) {
        val c = _connection.value ?: return
        val n = f(c)
        store.save(n)
        _connection.value = n
        _state.value = n.state
    }
}
