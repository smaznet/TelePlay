package com.smaznet.teleplay.mtproto.session

import com.smaznet.teleplay.mtproto.tl.TlReader
import com.smaznet.teleplay.mtproto.tl.TlWriter
import java.io.IOException
import java.security.SecureRandom

/** One decrypted server message. */
class InboundMessage(val msgId: Long, val seqNo: Int, val body: ByteArray) {
    val contentRelated: Boolean get() = seqNo and 1 == 1
}

/**
 * MTProto session state for one auth key: msg_id/seq_no generation, salt, and packet
 * encryption/decryption. Not thread-safe; callers serialize access.
 */
class Session(
    val authKey: ByteArray,
    val authKeyId: Long,
    var serverSalt: Long,
    timeOffset: Int,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    sessionId: Long = SecureRandom().nextLong(),
    /** Test seam: a server-side session encrypts with the server direction and adopts the client's session id. */
    private val isServer: Boolean = false,
) {
    var sessionId: Long = sessionId
        private set
    private val sendDir = if (isServer) Mtproto2.FROM_SERVER else Mtproto2.FROM_CLIENT
    private val recvDir = if (isServer) Mtproto2.FROM_CLIENT else Mtproto2.FROM_SERVER
    private var sessionKnown = !isServer

    var timeOffset: Int = timeOffset
    private var lastMsgId = 0L
    private var seq = 0

    fun nextMsgId(): Long {
        val now = nowMillis()
        var id = ((now / 1000 + timeOffset) shl 32) or ((now % 1000) * (1L shl 32) / 1000)
        id = id and 3L.inv()
        if (id <= lastMsgId) id = lastMsgId + 4
        lastMsgId = id
        return id
    }

    fun nextSeqNo(contentRelated: Boolean): Int {
        val v = seq * 2 + if (contentRelated) 1 else 0
        if (contentRelated) seq++
        return v
    }

    /** Adjusts the clock from a server msg_id (used on bad_msg_notification 16/17). */
    fun syncTimeFrom(serverMsgId: Long) {
        timeOffset = ((serverMsgId ushr 32) - nowMillis() / 1000).toInt()
        lastMsgId = 0
    }

    fun resetSeq() { seq = 0 }

    /** Builds an encrypted packet; returns (msgId, packet). */
    fun encrypt(body: ByteArray, contentRelated: Boolean, msgId: Long = nextMsgId()): Pair<Long, ByteArray> {
        val w = TlWriter()
        w.int64(serverSalt)
        w.int64(sessionId)
        w.int64(msgId)
        w.int32(nextSeqNo(contentRelated))
        w.int32(body.size)
        w.raw(body)
        val (msgKey, enc) = Mtproto2.encrypt(authKey, w.toByteArray(), sendDir)
        val out = TlWriter()
        out.int64(authKeyId)
        out.raw(msgKey)
        out.raw(enc)
        return msgId to out.toByteArray()
    }

    fun decrypt(packet: ByteArray): InboundMessage {
        if (packet.size < 8 + 16 + 16 + 32) throw IOException("packet too short")
        val r = TlReader(packet)
        if (r.int64() != authKeyId) throw IOException("unknown auth_key_id")
        val msgKey = r.raw(16)
        val plain = Mtproto2.decrypt(authKey, msgKey, r.raw(r.remaining), recvDir)
        val p = TlReader(plain)
        p.int64() // salt
        val sid = p.int64()
        if (!sessionKnown) { sessionId = sid; sessionKnown = true }
        if (sid != sessionId) throw IOException("session_id mismatch")
        val msgId = p.int64()
        val seqNo = p.int32()
        val len = p.int32()
        if (len < 0 || len > p.remaining || len % 4 != 0) throw IOException("bad message length")
        if (plain.size - 32 - len !in 12..1024) throw IOException("bad padding")
        return InboundMessage(msgId, seqNo, p.raw(len))
    }
}
