package com.smaznet.teleplay.mtproto.session

import com.smaznet.teleplay.mtproto.tl.*
import com.smaznet.teleplay.mtproto.transport.Transport
import com.smaznet.teleplay.mtproto.transport.TransportFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/** Everything needed to resume a data-center session without a new key exchange. Secret. */
class DcSessionData(
    val dcId: Int,
    val host: String,
    val port: Int,
    val authKey: ByteArray,
    val authKeyId: Long,
    val serverSalt: Long,
    val timeOffset: Int,
) {
    fun toBytes(): ByteArray {
        val w = TlWriter()
        w.int32(dcId); w.string(host); w.int32(port); w.bytes(authKey)
        w.int64(authKeyId); w.int64(serverSalt); w.int32(timeOffset)
        return w.toByteArray()
    }

    companion object {
        fun fromBytes(b: ByteArray): DcSessionData {
            val r = TlReader(b)
            return DcSessionData(r.int32(), r.string(), r.int32(), r.bytes(), r.int64(), r.int64(), r.int32())
        }
    }
}

/** A Telegram `rpc_error` returned for a request. */
class RpcException(val code: Int, val rpcMessage: String) : IOException("rpc_error $code")

/**
 * One connection to a data center: key exchange (or key restore), the read loop, request/response
 * matching, acks, salt/time fixes, keep-alive and reconnect with capped exponential backoff.
 * Never logs.
 */
class DcConnection(
    val dcId: Int,
    private val host: String,
    private val port: Int,
    private val factory: TransportFactory,
    private val scope: CoroutineScope,
    private val saved: DcSessionData? = null,
    private val onSessionChanged: (DcSessionData) -> Unit = {},
    /** Called when the server pushed something that is not a service message; bodies are never parsed. */
    private val onUpdatesPushed: () -> Unit = {},
    /** Raw body of every pushed non-service message, offered before [onUpdatesPushed]; must not throw or log. */
    private val onPushedPayload: (ByteArray) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val requestTimeoutMs: Long = 30_000,
    private val pingIntervalMs: Long = 30_000,
    private val maxBackoffMs: Long = 30_000,
) {
    private class Pending(val body: ByteArray, val result: CompletableDeferred<ByteArray>)

    private val connectMutex = Mutex()
    private val lock = Any()
    private var transport: Transport? = null
    private var session: Session? = null
    private var readJob: Job? = null
    private var keepAliveJob: Job? = null
    private val pending = HashMap<Long, Pending>()
    private val acks = ArrayList<Long>()
    private var lastKnown: DcSessionData? = saved
    private var failedConnects = 0

    /** Set by [Rpc]; reset on every (re)connect because a new session needs initConnection again. */
    @Volatile
    var initialized = false

    @Volatile
    var connected = false
        private set

    /** Connects (once) and starts the keep-alive; safe to call repeatedly. */
    fun start() {
        if (keepAliveJob?.isActive == true) return
        keepAliveJob = scope.launch {
            var backoff = 1000L
            while (isActive) {
                try {
                    ensureConnected()
                    backoff = 1000L
                    while (isActive && connected) {
                        delay(pingIntervalMs)
                        if (connected) ping()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // fall through to backoff
                }
                delay(backoff)
                backoff = minOf(backoff * 2, maxBackoffMs)
            }
        }
    }

    /**
     * Drops the transport (keeping the auth key and session) so the next connect uses the current route, and,
     * when [restart] is set, reconnects right away instead of waiting for the next keep-alive tick.
     */
    fun reconnect(restart: Boolean) {
        keepAliveJob?.cancel()
        keepAliveJob = null
        dropConnection(IOException("route changed"))
        failedConnects = 0
        if (restart) start()
    }

    fun close() {
        keepAliveJob?.cancel()
        dropConnection(IOException("closed"))
    }

    suspend fun ensureConnected() {
        if (connected) return
        connectMutex.withLock {
            if (connected) return
            val delayMs = if (failedConnects == 0) 0L else minOf(500L shl minOf(failedConnects - 1, 10), maxBackoffMs)
            if (delayMs > 0) delay(delayMs)
            try {
                val t = factory.connect(host, port, dcId)
                try {
                    val s = establishSession(t)
                    synchronized(lock) { session = s; transport = t; connected = true }
                } catch (e: Throwable) {
                    t.close()
                    throw e
                }
                failedConnects = 0
                initialized = false
                readJob = scope.launch { readLoop(t) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                failedConnects++
                throw e
            } catch (e: RuntimeException) {
                failedConnects++
                throw IOException("connect failed", e)
            }
        }
    }

    private suspend fun establishSession(t: Transport): Session {
        val k = lastKnown
        if (k != null) return Session(k.authKey, k.authKeyId, k.serverSalt, k.timeOffset, nowMillis)
        val r = AuthKeyExchange(t, dcId, nowMillis).run()
        val data = DcSessionData(dcId, host, port, r.authKey, r.authKeyId, r.serverSalt, r.timeOffset)
        lastKnown = data
        onSessionChanged(data)
        return Session(r.authKey, r.authKeyId, r.serverSalt, r.timeOffset, nowMillis)
    }

    /** Forgets the stored key (e.g. after AUTH_KEY_UNREGISTERED) so the next connect creates a new one. */
    fun discardKey() {
        lastKnown = null
        dropConnection(IOException("key discarded"))
    }

    /** Sends [fn] and returns the raw result object (constructor id included), gzip already unwrapped. */
    suspend fun call(fn: TlObject): ByteArray {
        ensureConnected()
        val body = fn.toBytes()
        val result = CompletableDeferred<ByteArray>()
        send(Pending(body, result))
        return withTimeoutOrNull(requestTimeoutMs) { result.await() } ?: run {
            synchronized(lock) { pending.entries.removeAll { it.value.result === result } }
            throw IOException("request timed out")
        }
    }

    private suspend fun send(p: Pending) {
        val (t, packet) = synchronized(lock) {
            val s = session ?: throw IOException("not connected")
            val (id, pkt) = s.encrypt(p.body, contentRelated = true)
            pending[id] = p
            transport!! to pkt
        }
        try {
            t.send(packet)
        } catch (e: IOException) {
            dropConnection(e)
            throw e
        }
    }

    private suspend fun ping() {
        try {
            sendRaw(Ping(nowMillis()).toBytes(), contentRelated = false)
        } catch (_: IOException) {
        }
    }

    private suspend fun sendRaw(body: ByteArray, contentRelated: Boolean) {
        val (t, packet) = synchronized(lock) {
            val s = session ?: throw IOException("not connected")
            transport!! to s.encrypt(body, contentRelated).second
        }
        try {
            t.send(packet)
        } catch (e: IOException) {
            dropConnection(e)
            throw e
        }
    }

    private suspend fun readLoop(t: Transport) {
        try {
            while (true) {
                val packet = t.receive()
                val msg = synchronized(lock) { session!!.decrypt(packet) }
                handle(msg)
                flushAcks()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (transport === t) dropConnection(if (e is IOException) e else IOException("protocol error", e))
        }
    }

    private suspend fun flushAcks() {
        val ids = synchronized(lock) { if (acks.isEmpty()) return else acks.toList().also { acks.clear() } }
        sendRaw(MsgsAck(ids).toBytes(), contentRelated = false)
    }

    private fun dropConnection(cause: IOException) {
        val failed: List<Pending>
        val t: Transport?
        synchronized(lock) {
            t = transport
            transport = null
            session = null
            connected = false
            failed = pending.values.toList()
            pending.clear()
            acks.clear()
        }
        t?.close()
        readJob?.cancel()
        failed.forEach { it.result.completeExceptionally(cause) }
    }

    // --- inbound handling -------------------------------------------------------------------

    private suspend fun handle(msg: InboundMessage) {
        if (msg.contentRelated) synchronized(lock) { acks.add(msg.msgId) }
        val r = TlReader(msg.body)
        when (r.peekInt32()) {
            Ids.RPC_RESULT -> {
                r.int32()
                val reqId = r.int64()
                val inner = unwrapGzip(r.raw(r.remaining))
                val p = synchronized(lock) { pending.remove(reqId) } ?: return
                val ir = TlReader(inner)
                if (ir.peekInt32() == Ids.RPC_ERROR) {
                    ir.int32()
                    p.result.completeExceptionally(RpcException(ir.int32(), ir.string()))
                } else {
                    p.result.complete(inner)
                }
            }
            Ids.MSG_CONTAINER -> {
                r.int32()
                val n = r.int32()
                repeat(n) {
                    val id = r.int64()
                    val seq = r.int32()
                    val len = r.int32()
                    handle(InboundMessage(id, seq, r.raw(len)))
                }
            }
            Ids.GZIP_PACKED -> {
                r.int32()
                handle(InboundMessage(msg.msgId, msg.seqNo, unwrapGzip(msg.body)))
            }
            Ids.BAD_SERVER_SALT -> {
                r.int32()
                val badId = r.int64(); r.int32(); r.int32()
                val salt = r.int64()
                synchronized(lock) { session?.serverSalt = salt }
                resend(badId)
            }
            Ids.BAD_MSG_NOTIFICATION -> {
                r.int32()
                val badId = r.int64(); r.int32()
                when (val code = r.int32()) {
                    16, 17 -> { synchronized(lock) { session?.syncTimeFrom(msg.msgId) }; resend(badId) }
                    32, 33 -> { synchronized(lock) { session?.resetSeq() }; resend(badId) }
                    48 -> resend(badId)
                    else -> synchronized(lock) { pending.remove(badId) }
                        ?.result?.completeExceptionally(IOException("bad_msg_notification $code"))
                }
            }
            Ids.NEW_SESSION_CREATED -> {
                r.int32(); r.int64(); r.int64()
                val salt = r.int64()
                synchronized(lock) { session?.serverSalt = salt }
                onUpdatesPushed()
            }
            Ids.MSGS_ACK, Ids.PONG -> {}
            else -> {
                try { onPushedPayload(msg.body) } catch (_: Exception) { }
                onUpdatesPushed()
            }
        }
    }

    private suspend fun resend(oldId: Long) {
        val p = synchronized(lock) { pending.remove(oldId) } ?: return
        try {
            send(p)
        } catch (e: IOException) {
            p.result.completeExceptionally(e)
        }
    }

    private fun unwrapGzip(b: ByteArray): ByteArray {
        if (b.size < 4) return b
        val r = TlReader(b)
        if (r.int32() != Ids.GZIP_PACKED) return b
        val packed = r.bytes()
        val out = GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
        if (out.size > MAX_UNPACKED) throw IOException("gzip_packed too large")
        return out
    }

    private companion object {
        const val MAX_UNPACKED = 32 * 1024 * 1024
    }
}
