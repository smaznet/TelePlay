package com.smaznet.teleplay.mtproto.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** A framed, ordered, reliable message pipe to one Telegram data center. */
interface Transport {
    suspend fun send(frame: ByteArray)

    /** Next whole frame; throws IOException when the connection is closed or times out. */
    suspend fun receive(): ByteArray

    fun close()
}

/** [dcId] is the Telegram data center the caller wants; direct and SOCKS5 routes ignore it, MTProto proxies need it. */
fun interface TransportFactory {
    suspend fun connect(host: String, port: Int, dcId: Int): Transport
}

/** Why a proxy route failed. Plain-language mapping lives in the UI; nothing here carries proxy details. */
enum class ProxyFailure { UNREACHABLE, AUTH_REJECTED, REFUSED, HANDSHAKE_FAILED }

/** A failure of the proxy leg itself. An [IOException], so connection backoff and offline handling apply unchanged. */
class ProxyException(val reason: ProxyFailure, cause: Throwable? = null) : IOException("proxy ${reason.name.lowercase()}") {
    init { cause?.let { initCause(it) } }
}

/**
 * MTProto "intermediate" framing: little-endian 4-byte length + payload. With [padded] (the `0xdddddddd`
 * variant) every outgoing frame carries 0-3 random bytes counted in the length, and the reader rounds the
 * length down to a multiple of four so padding is dropped.
 */
class TcpTransport internal constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    private val padded: Boolean = false,
    private val random: SecureRandom = SecureRandom(),
    /** Called on a read error before any frame was received, so a proxy that rejects us is reported as such. */
    private val firstFrameFailure: (() -> IOException?)? = null,
) : Transport {
    private var received = 0

    override suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) {
        val pad = if (padded) random.nextInt(4) else 0
        val n = frame.size + pad
        val buf = ByteArray(4 + n)
        buf[0] = n.toByte(); buf[1] = (n ushr 8).toByte(); buf[2] = (n ushr 16).toByte(); buf[3] = (n ushr 24).toByte()
        System.arraycopy(frame, 0, buf, 4, frame.size)
        if (pad > 0) { val p = ByteArray(pad); random.nextBytes(p); System.arraycopy(p, 0, buf, 4 + frame.size, pad) }
        synchronized(output) { output.write(buf); output.flush() }
    }

    override suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        try {
            val h = ByteArray(4)
            input.readFully(h)
            val n = (h[0].toInt() and 0xff) or ((h[1].toInt() and 0xff) shl 8) or
                ((h[2].toInt() and 0xff) shl 16) or ((h[3].toInt() and 0xff) shl 24)
            if (n < 0 || n > MAX_FRAME) throw IOException("bad frame length")
            // The length counts the whole packet. In padded mode the last 0-3 bytes are padding: they must be read
            // off the stream too, then dropped, or every following frame is misaligned.
            val frame = ByteArray(n).also { input.readFully(it); received++ }
            if (padded && n and 3 != 0) frame.copyOf(n and 3.inv()) else frame
        } catch (e: IOException) {
            if (received == 0) firstFrameFailure?.invoke()?.let { throw it }
            throw e
        }
    }

    /** Overrides how long a read may block (used by the proxy test to enforce its own deadline). */
    fun setReadTimeout(ms: Int) { try { socket.soTimeout = ms } catch (_: IOException) {} }

    override fun close() {
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {
        private const val MAX_FRAME = 4 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 75_000

        /** The direct route: TCP to the data center, `0xeeeeeeee` init, intermediate frames. */
        val factory = TransportFactory { host, port, _ -> connect(host, port) }

        suspend fun connect(host: String, port: Int): TcpTransport = withContext(Dispatchers.IO) {
            val s = openSocket(host, port)
            try {
                s.soTimeout = READ_TIMEOUT_MS
                val out = s.getOutputStream()
                out.write(INTERMEDIATE_INIT)
                out.flush()
                fromSocket(s, s.getInputStream(), out)
            } catch (e: IOException) {
                try { s.close() } catch (_: IOException) {}
                throw e
            }
        }

        internal val INTERMEDIATE_INIT = byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())

        /** Opens and connects a socket. Reads time out after [timeoutMs] until the caller restores [READ_TIMEOUT_MS] (end of a proxy handshake). */
        internal fun openSocket(host: String, port: Int, timeoutMs: Int = CONNECT_TIMEOUT_MS): Socket {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress(host, port), timeoutMs)
                return s
            } catch (e: IOException) {
                try { s.close() } catch (_: IOException) {}
                throw e
            }
        }

        internal fun fromSocket(
            s: Socket, rawIn: InputStream, out: OutputStream, padded: Boolean = false,
            firstFrameFailure: (() -> IOException?)? = null,
        ): TcpTransport =
            TcpTransport(s, DataInputStream(rawIn.buffered(64 * 1024)), out, padded, SecureRandom(), firstFrameFailure)
    }
}
