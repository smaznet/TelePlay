package com.smaznet.teleplay.setup

import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.domain.proxy.ProxyDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class SetupStatus { IDLE, NO_NETWORK, WAITING, PHONE_CONNECTED, SUBMITTING, REJECTED, BUSY, TOKEN_RECEIVED, EXPIRED_REFRESHING }

/** What the TV panel shows. [url] is null when there is no QR to draw. */
data class SetupUiState(
    val status: SetupStatus = SetupStatus.IDLE,
    val url: String? = null,
    val shortAddress: String? = null,
    val secret: String? = null,
    val error: SetupError? = null,
    /** Display label of a proxy the phone just added (never its address or credentials). */
    val proxyAdded: String? = null,
) {
    /** Redacted: [url] and [secret] must never reach a log. */
    override fun toString(): String = "SetupUiState(status=$status, error=$error)"
}

/**
 * Owns the setup session and server for one token-entry screen. Starts a listener only while an address exists,
 * rotates the secret when it is used up or expires (same port), and restarts on the new address when the
 * network changes. Create a new host per screen.
 */
class SetupHost(
    private val submit: suspend (String) -> Result<BotInfo>,
    private val addresses: LocalAddressProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    private val validityMs: Long = SetupSession.DEFAULT_VALIDITY_MS,
    private val usedHoldMs: Long = 3_000L,
    private val refreshNoticeMs: Long = 2_000L,
    private val serverFactory: (SetupSession, suspend (String) -> Result<BotInfo>, LocalAddressSource) -> SetupServer =
        { session, submit, source -> SetupServer(session, submit, source, clock = clock) },
    /** Stores a proxy from the phone page; null when this host does not take proxies. */
    private val submitProxy: (suspend (ProxyDraft) -> ProxySubmitResult)? = null,
) {
    @Volatile
    private var proxyName: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _ui = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _ui

    private var job: Job? = null

    private fun newSession() = SetupSession(clock = clock, validityMs = validityMs)

    /** Idempotent. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            addresses.changes().collectLatest { address ->
                if (address == null) {
                    _ui.value = SetupUiState(SetupStatus.NO_NETWORK)
                } else {
                    serve(address)
                }
            }
        }
    }

    /** Idempotent. Closes the listener; the host can be started again. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _ui.value = SetupUiState()
    }

    private suspend fun serve(address: String) {
        var session = newSession()
        val server = serverFactory(session, submit) { address }
        submitProxy?.let { sp ->
            server.onProxy = { draft ->
                sp(draft).also { r -> if (r is ProxySubmitResult.Added) { proxyName = r.name; _ui.value = _ui.value.copy(proxyAdded = r.name) } }
            }
        }
        val port = try { server.start() } catch (_: Exception) {
            _ui.value = SetupUiState(SetupStatus.NO_NETWORK)
            return
        }
        try {
            while (scope.isActive) {
                val current = session
                val observer = scope.launch {
                    current.state.collectLatest { st -> publish(address, port, current, st) }
                }
                try {
                    // Wait until the session is used up, runs out of attempts, or reaches its deadline.
                    withTimeoutOrNull((current.expiresAt - clock()).coerceAtLeast(0)) {
                        current.state.first { it == SetupState.USED || it == SetupState.EXPIRED }
                    }
                    current.checkExpiry()
                    if (current.state.value == SetupState.USED) delay(usedHoldMs)
                } finally {
                    observer.cancel()
                }
                // Rotate: new secret, same port, QR redraws.
                val wasExpired = current.state.value == SetupState.EXPIRED
                session = newSession()
                server.rotate(session)
                if (wasExpired) {
                    _ui.value = SetupUiState(SetupStatus.EXPIRED_REFRESHING, urlFor(address, port, session), "$address:$port", session.secret, proxyAdded = proxyName)
                    delay(refreshNoticeMs)
                }
            }
        } finally {
            server.stop()
        }
    }

    private fun urlFor(address: String, port: Int, s: SetupSession) = "http://$address:$port/s/${s.secret}"

    private fun publish(address: String, port: Int, s: SetupSession, st: SetupState) {
        val status = when (st) {
            SetupState.WAITING -> SetupStatus.WAITING
            SetupState.PHONE_CONNECTED -> SetupStatus.PHONE_CONNECTED
            SetupState.SUBMITTING -> SetupStatus.SUBMITTING
            SetupState.REJECTED -> if (s.lastError.value == SetupError.BUSY) SetupStatus.BUSY else SetupStatus.REJECTED
            SetupState.USED -> SetupStatus.TOKEN_RECEIVED
            SetupState.EXPIRED -> SetupStatus.EXPIRED_REFRESHING
        }
        _ui.value = SetupUiState(status, urlFor(address, port, s), "$address:$port", s.secret, s.lastError.value, proxyName)
    }
}
