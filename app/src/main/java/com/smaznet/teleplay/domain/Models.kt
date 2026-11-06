package com.smaznet.teleplay.domain

/** Persisted state of the single bot connection (see data-model.md transitions). */
enum class ConnectionState { SIGNED_OUT, CONNECTING, AWAITING_PAIRING, READY, REJECTED, REVOKED, OFFLINE }

/** Live state reported by the Telegram gateway. */
enum class GatewayState { CONNECTING, READY, OFFLINE, REJECTED, REVOKED }

enum class Availability { PLAYABLE, UNPLAYABLE_FORMAT, REMOTE_GONE }

data class BotInfo(val botUserId: Long, val name: String)

data class BotConnection(
    val botUserId: Long,
    val botName: String,
    val homeDcId: Int,
    val state: ConnectionState,
    val authorizedUserId: Long?,
    val pairingCodeHash: String?,
    /** Epoch millis, 15 minutes after the code was generated. */
    val pairingExpiresAt: Long?,
    /** Needed to address the authorized user's private chat; null until first seen. */
    val authorizedUserAccessHash: Long? = null,
)

class Video(
    val id: Long,
    val chatUserId: Long,
    val messageId: Int,
    val documentId: Long,
    val accessHash: Long,
    val fileReference: ByteArray,
    val dcId: Int,
    val title: String,
    val durationSec: Int,
    val sizeBytes: Long,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val thumbSize: String?,
    val receivedAt: Long,
    val availability: Availability,
)

data class PlaybackProgress(
    val videoId: Long,
    val positionMs: Long,
    val durationMs: Long,
    /** True once at least 95% has been watched. */
    val completed: Boolean,
    val updatedAt: Long,
)

class IncomingVideo(
    val documentId: Long,
    val accessHash: Long,
    val fileReference: ByteArray,
    val dcId: Int,
    val name: String?,
    val mime: String,
    val size: Long,
    val duration: Int,
    val width: Int,
    val height: Int,
    val thumbSize: String?,
)

/** A non-video document (feature 005: playlist backup); only kept when its caption is a command. */
class IncomingDocument(
    val id: Long,
    val accessHash: Long,
    val fileReference: ByteArray,
    val dcId: Int,
    val name: String?,
    val mime: String,
    val size: Long,
)

class IncomingMessage(
    val messageId: Int,
    val senderUserId: Long,
    val date: Long,
    val text: String?,
    val video: IncomingVideo?,
    /** Access hash of the sender when the update carried the user object. */
    val senderAccessHash: Long? = null,
    /** Id of the message this one replies to, if any. */
    val replyToMessageId: Int? = null,
    val document: IncomingDocument? = null,
)

data class CacheStats(val usedBytes: Long, val limitBytes: Long)

/** Errors surfaced by the Telegram layer; never carries secrets. */
sealed class TelegramError(message: String) : Exception(message) {
    class TokenRejected : TelegramError("token rejected")
    class Offline(cause: Throwable? = null) : TelegramError("offline") { init { cause?.let { initCause(it) } } }
    class RateLimited(val retryAfterSec: Int) : TelegramError("rate limited")
    /** The stored key was rejected; sign in again once before giving up. */
    class AuthKeyLost : TelegramError("auth key lost")
    class FileUnavailable : TelegramError("file unavailable")
    class FileReferenceExpired : TelegramError("file reference expired")
    class Unsupported(what: String) : TelegramError("unsupported: $what")
    class Unknown(val code: Int, val rpcMessage: String) : TelegramError("rpc error $code")
}
