package com.smaznet.teleplay.domain

import com.smaznet.teleplay.data.repo.ConnectionRepository
import com.smaznet.teleplay.data.repo.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

data class PairingChallenge(val code: String, val expiresAt: Long)

sealed class PairingResult {
    data class Paired(val userId: Long) : PairingResult()
    object Ignored : PairingResult()
}

/** Clears the update cursor (getDifference state) after a pairing reset. */
fun interface UpdateStateResetter { fun reset() }

/**
 * Pairing per contracts/telegram-gateway.md. Only the SHA-256 of the code is persisted; the plain code
 * lives in memory. Blocking persistence; call off the main thread.
 */
class PairingService(
    private val connections: ConnectionRepository,
    private val videos: VideoRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val updateStateResetter: UpdateStateResetter = UpdateStateResetter { },
    /** Runs first in [reset], while the old authorized user is still known (ends the Telegram remote). */
    private val beforeReset: () -> Unit = {},
) {
    private val _challenge = MutableStateFlow<PairingChallenge?>(null)

    /** The code to show the user; null when none is held in memory (e.g. after process restart). */
    val challenge: StateFlow<PairingChallenge?> = _challenge.asStateFlow()

    fun startPairing(): PairingChallenge {
        val code = generateCode()
        val expiresAt = now() + EXPIRY_MS
        connections.savePairingCode(hash(code), expiresAt)
        return PairingChallenge(code, expiresAt).also { _challenge.value = it }
    }

    /** The in-memory code when still valid, else a new one. */
    fun ensureChallenge(): PairingChallenge =
        _challenge.value?.takeIf { it.expiresAt > now() } ?: startPairing()

    fun onMessage(msg: IncomingMessage): PairingResult {
        val c = connections.connection.value ?: return PairingResult.Ignored
        if (c.authorizedUserId != null) return PairingResult.Ignored
        val hash = c.pairingCodeHash ?: return PairingResult.Ignored
        val expiresAt = c.pairingExpiresAt ?: return PairingResult.Ignored
        if (now() >= expiresAt) {
            invalidate()
            return PairingResult.Ignored
        }
        val candidate = extractCode(msg.text) ?: return PairingResult.Ignored
        if (!MessageDigest.isEqual(hash(candidate).toByteArray(), hash.toByteArray())) return PairingResult.Ignored
        connections.completePairing(msg.senderUserId)
        _challenge.value = null
        return PairingResult.Paired(msg.senderUserId)
    }

    /** Reset pairing (T068): drops the user, their videos and progress, issues a new code. */
    fun reset(): PairingChallenge {
        beforeReset()
        videos.clearAll()
        updateStateResetter.reset()
        val code = generateCode()
        val expiresAt = now() + EXPIRY_MS
        connections.resetPairing(hash(code), expiresAt)
        return PairingChallenge(code, expiresAt).also { _challenge.value = it }
    }

    private fun invalidate() {
        connections.clearPairingCode()
        _challenge.value = null
    }

    private fun extractCode(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val m = START.matchEntire(t)
        val code = (m?.groupValues?.get(1) ?: t).trim().uppercase()
        return code.takeIf { it.length == CODE_LENGTH }
    }

    private fun generateCode(): String =
        buildString { repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    private fun hash(code: String): String =
        MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** Unambiguous: no 0/O, 1/I/L, 2. */
        const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ3456789"
        const val CODE_LENGTH = 8
        const val EXPIRY_MS = 15 * 60 * 1000L
        private val START = Regex("^/start(?:@\\w+)?\\s+(\\S+)$", RegexOption.IGNORE_CASE)
    }
}
