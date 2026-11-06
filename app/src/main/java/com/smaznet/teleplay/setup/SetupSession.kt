package com.smaznet.teleplay.setup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom

enum class SetupState { WAITING, PHONE_CONNECTED, SUBMITTING, REJECTED, USED, EXPIRED }

enum class SetupError { FORMAT, REJECTED, OFFLINE, RATE_LIMITED, BUSY, OTHER }

/**
 * One single-use, short-lived setup secret and its lifecycle. In memory only. The secret is never part of
 * [toString] and must never be logged.
 */
class SetupSession(
    private val clock: () -> Long = System::currentTimeMillis,
    validityMs: Long = DEFAULT_VALIDITY_MS,
    attempts: Int = DEFAULT_ATTEMPTS,
    random: SecureRandom = SecureRandom(),
) {
    val secret: String = newSecret(random)
    val createdAt: Long = clock()
    val expiresAt: Long = createdAt + validityMs

    @Volatile
    var attemptsLeft: Int = attempts
        private set

    private val _state = MutableStateFlow(SetupState.WAITING)
    val state: StateFlow<SetupState> = _state

    private val _lastError = MutableStateFlow<SetupError?>(null)
    val lastError: StateFlow<SetupError?> = _lastError

    private val secretBytes = secret.toByteArray(Charsets.US_ASCII)

    /** Constant-time comparison with the secret. */
    fun matches(candidate: String): Boolean =
        MessageDigest.isEqual(secretBytes, candidate.toByteArray(Charsets.US_ASCII))

    /** Moves to EXPIRED when past [expiresAt] (unless already USED). Returns true if the session is expired. */
    @Synchronized
    fun checkExpiry(): Boolean {
        val s = _state.value
        if (s != SetupState.USED && s != SetupState.EXPIRED && clock() >= expiresAt) _state.value = SetupState.EXPIRED
        return _state.value == SetupState.EXPIRED
    }

    /** True while the secret may still be used. */
    val isUsable: Boolean
        get() { checkExpiry(); return _state.value != SetupState.USED && _state.value != SetupState.EXPIRED }

    @Synchronized
    fun onPageViewed() {
        checkExpiry()
        if (_state.value == SetupState.WAITING || _state.value == SetupState.REJECTED) _state.value = SetupState.PHONE_CONNECTED
    }

    /** WAITING/PHONE_CONNECTED/REJECTED to SUBMITTING. Returns false if a submission is already in flight or the session is over. */
    @Synchronized
    fun beginSubmit(): Boolean {
        checkExpiry()
        return when (_state.value) {
            SetupState.WAITING, SetupState.PHONE_CONNECTED, SetupState.REJECTED -> {
                _lastError.value = null
                _state.value = SetupState.SUBMITTING
                true
            }
            else -> false
        }
    }

    @Synchronized
    fun onAccepted() {
        if (_state.value == SetupState.SUBMITTING) _state.value = SetupState.USED
    }

    /** A submission failed. Only a token rejection consumes an attempt; running out of attempts expires the session. */
    @Synchronized
    fun onFailed(error: SetupError) {
        if (_state.value != SetupState.SUBMITTING) return
        _lastError.value = error
        if (error == SetupError.REJECTED) attemptsLeft--
        _state.value = if (attemptsLeft <= 0) SetupState.EXPIRED else SetupState.REJECTED
    }

    override fun toString(): String = "SetupSession(state=${_state.value}, attemptsLeft=$attemptsLeft)"

    companion object {
        const val DEFAULT_VALIDITY_MS = 10 * 60 * 1000L
        const val DEFAULT_ATTEMPTS = 5

        fun newSecret(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return urlSafeBase64(bytes) // 22 chars
        }

        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /** Unpadded URL-safe base64 (java.util.Base64 needs API 26; minSdk is 21). */
        internal fun urlSafeBase64(bytes: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
                val n = (b0 shl 16) or (b1 shl 8) or b2
                sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
                if (i + 1 < bytes.size) sb.append(ALPHABET[(n shr 6) and 63])
                if (i + 2 < bytes.size) sb.append(ALPHABET[n and 63])
                i += 3
            }
            return sb.toString()        }
    }
}
