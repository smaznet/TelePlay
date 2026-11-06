package com.smaznet.teleplay.domain.remote

/** The single Now-playing message kept in the authorized user's private chat (data-model.md). */
data class RemoteMessage(
    val chatUserId: Long,
    val messageId: Int,
    /** 8 random chars; embedded in callback data. */
    val sessionId: String,
    val lastTextHash: Int,
    val pinned: Boolean,
    /** Epoch ms. */
    val updatedAt: Long,
)

/** Why a remote send/edit/pin/answer call failed; never carries message text or secrets. */
enum class RemoteFailure { GONE, BLOCKED, FLOOD, OFFLINE, OTHER }

class RemoteSendException(val failure: RemoteFailure) : Exception("remote send failed: $failure")

/** One inline button; [data] is the callback payload (at most 64 bytes). */
data class RemoteButton(val label: String, val data: String)

/** A button press from Telegram. */
data class CallbackQuery(val queryId: Long, val userId: Long, val messageId: Int, val data: String)
