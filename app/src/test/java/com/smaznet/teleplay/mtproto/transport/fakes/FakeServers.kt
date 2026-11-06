package com.smaznet.teleplay.mtproto.transport.fakes

import com.smaznet.teleplay.domain.proxy.MtSecret
import com.smaznet.teleplay.mtproto.transport.FakeTls
import com.smaznet.teleplay.mtproto.transport.Obfuscated2
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** Reads and writes intermediate frames; [stripPadding] rounds lengths down to a multiple of four (padded mode). */
internal class FrameIo(private val input: DataInputStream, private val output: OutputStream, private val stripPadding: Boolean) {
    var lastLength = 0

    fun read(): ByteArray? = try {
        val h = ByteArray(4); input.readFully(h)
        var n = (h[0].toInt() and 0xff) or ((h[1].toInt() and 0xff) shl 8) or ((h[2].toInt() and 0xff) shl 16) or ((h[3].toInt() and 0xff) shl 24)
        lastLength = n
        if (stripPadding) n = n and 3.inv()
        ByteArray(n).also { input.readFully(it) }.also { if (stripPadding) input.readFully(ByteArray(lastLength - n)) }
    } catch (_: java.io.IOException) { null }

    private val random = java.util.Random(11)

    /** In padded mode a real proxy appends 0-3 random bytes that the length field counts. */
    fun write(frame: ByteArray) {
        val pad = if (stripPadding) random.nextInt(4) else 0
        val n = frame.size + pad
        output.write(byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()) + frame + ByteArray(pad) { random.nextInt().toByte() })
        output.flush()
    }
}

/** What a fake data center answers for one frame (null = no answer). */
typealias FrameHandler = (dcId: Int, frame: ByteArray) -> ByteArray?

private fun serve(name: String, server: ServerSocket, handle: (Socket) -> Unit) {
    thread(isDaemon = true, name = name) {
        while (!server.isClosed) {
            val s = try { server.accept() } catch (_: Exception) { return@thread }
            thread(isDaemon = true) { try { handle(s) } catch (_: Exception) { } finally { try { s.close() } catch (_: Exception) {} } }
        }
    }
}

/** A data center speaking plain intermediate transport. */
class FakeDc(private val handler: FrameHandler = { _, f -> f }) : Closeable {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile var connections = 0

    init {
        serve("fake-dc", server) { s ->
            connections++
            val din = DataInputStream(s.getInputStream().buffered())
            din.readFully(ByteArray(4))
            val io = FrameIo(din, s.getOutputStream(), false)
            while (true) { val f = io.read() ?: break; handler(2, f)?.let(io::write) }
        }
    }

    override fun close() { server.close() }
}

/** SOCKS5 server (RFC 1928/1929) relaying to whatever the client asks for. */
class FakeSocks5Server(
    private val user: String? = null,
    private val pass: String? = null,
    private val connectReply: Int = 0,
) : Closeable {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile var lastTarget: Pair<String, Int>? = null
    @Volatile var connects = 0

    init {
        serve("fake-socks5", server) { c ->
            connects++
            val i = DataInputStream(c.getInputStream()); val o = c.getOutputStream()
            check(i.readUnsignedByte() == 5)
            val methods = ByteArray(i.readUnsignedByte()).also { i.readFully(it) }
            if (user != null) {
                if (2 !in methods.map { it.toInt() }) { o.write(byteArrayOf(5.toByte(), (-1).toByte())); return@serve }
                o.write(byteArrayOf(5, 2)); o.flush()
                i.readUnsignedByte()
                val u = String(ByteArray(i.readUnsignedByte()).also { i.readFully(it) })
                val p = String(ByteArray(i.readUnsignedByte()).also { i.readFully(it) })
                if (u != user || p != pass) { o.write(byteArrayOf(1, 1)); o.flush(); return@serve }
                o.write(byteArrayOf(1, 0)); o.flush()
            } else {
                o.write(byteArrayOf(5, 0)); o.flush()
            }
            i.readFully(ByteArray(3))
            val host = when (i.readUnsignedByte()) {
                1 -> ByteArray(4).also { i.readFully(it) }.joinToString(".") { (it.toInt() and 0xff).toString() }
                3 -> String(ByteArray(i.readUnsignedByte()).also { i.readFully(it) })
                else -> ByteArray(16).also { i.readFully(it) }.let { "::" }
            }
            val port = (i.readUnsignedByte() shl 8) or i.readUnsignedByte()
            lastTarget = host to port
            if (connectReply != 0) { o.write(byteArrayOf(5, connectReply.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); o.flush(); return@serve }
            val up = Socket(host, port)
            o.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); o.flush()
            thread(isDaemon = true) { try { up.getInputStream().copyTo(o) } catch (_: Exception) {} finally { try { c.close() } catch (_: Exception) {} } }
            try { c.getInputStream().copyTo(up.getOutputStream()) } finally { up.close() }
        }
    }

    override fun close() { server.close() }
}

/**
 * An MTProto proxy accepting the three secret kinds. It answers frames itself through [handler] (standing in for the
 * data center behind it) and records the DC id and tag the client asked for.
 */
class FakeMtProxyServer(
    private val secret: MtSecret,
    private val handler: FrameHandler = { _, f -> f },
    private val serverNowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /** A bad timestamp is answered like an ordinary TLS website would (wrong digest), as real fake-TLS proxies do, instead of a close. */
    private val frontOnBadClock: Boolean = false,
) : Closeable {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile var lastDc: Int? = null
    @Volatile var lastTag: Int? = null
    @Volatile var rejected = 0
    @Volatile var lastHelloLength = 0
    @Volatile var lastSni: String? = null

    init {
        serve("fake-mtproxy", server) { s ->
            var input: InputStream = s.getInputStream().buffered()
            var output: OutputStream = s.getOutputStream()
            if (secret is MtSecret.FakeTls) {
                val din = DataInputStream(input)
                val head = ByteArray(5); din.readFully(head)
                val body = ByteArray(((head[3].toInt() and 0xff) shl 8) or (head[4].toInt() and 0xff)); din.readFully(body)
                val hello = head + body
                lastHelloLength = hello.size
                lastSni = parseSni(hello)
                val clientRandom = hello.copyOfRange(11, 43)
                val zeroed = hello.copyOf().also { java.util.Arrays.fill(it, 11, 43, 0) }
                val expect = hmac(secret.key, zeroed)
                val ts = ((clientRandom[28].toInt() xor expect[28].toInt()) and 0xff) or
                    (((clientRandom[29].toInt() xor expect[29].toInt()) and 0xff) shl 8) or
                    (((clientRandom[30].toInt() xor expect[30].toInt()) and 0xff) shl 16) or
                    (((clientRandom[31].toInt() xor expect[31].toInt()) and 0xff) shl 24)
                val ok = MessageDigest.isEqual(clientRandom.copyOfRange(0, 28), expect.copyOfRange(0, 28)) &&
                    Math.abs(serverNowSeconds() - (ts.toLong() and 0xffffffffL)) < 120
                if (!ok && frontOnBadClock) {
                    rejected++
                    val front = byteArrayOf(0x16, 3, 3, 0, 0x7a, 2, 0, 0, 0x76, 3, 3) + ByteArray(116) { 9 } +
                        byteArrayOf(0x14, 3, 3, 0, 1, 1) + byteArrayOf(0x17, 3, 3, 0, 4, 1, 2, 3, 4)
                    s.getOutputStream().write(front); s.getOutputStream().flush()
                    Thread.sleep(300)
                    return@serve
                }
                if (!ok) { rejected++; return@serve }
                // ServerHello (127 bytes), ChangeCipherSpec, one application record
                val sh = ByteArray(127)
                byteArrayOf(0x16, 3, 3, 0, 0x7a, 2, 0, 0, 0x76, 3, 3).copyInto(sh)
                sh[43] = 0x20; hello.copyOfRange(44, 76).copyInto(sh, 44)
                val tail = byteArrayOf(0x13, 1, 0, 0, 0x2e, 0, 0x33, 0, 0x24, 0, 0x1d, 0, 0x20)
                tail.copyInto(sh, 76)
                val ccs = byteArrayOf(0x14, 3, 3, 0, 1, 1)
                val app = byteArrayOf(0x17, 3, 3, 0, 8) + ByteArray(8) { 7 }
                val resp = sh + ccs + app
                val digest = hmac(secret.key, clientRandom + resp)
                digest.copyInto(resp, 11)
                s.getOutputStream().write(resp); s.getOutputStream().flush()
                val rec = DataInputStream(FakeTls.TlsRecordInput(din))
                // the client's ChangeCipherSpec is skipped by the record reader
                input = rec
                output = FakeTls.TlsRecordOutput(s.getOutputStream())
            }
            val din = DataInputStream(input)
            val header = ByteArray(64)
            din.readFully(header)
            val (ob, info) = Obfuscated2.accept(header, secret.key) ?: run { rejected++; return@serve }
            lastTag = info.first; lastDc = info.second
            val io = FrameIo(DataInputStream(ob.wrapInput(din)), ob.wrapOutput(output), stripPadding = info.first == Obfuscated2.TAG_PADDED)
            while (true) { val f = io.read() ?: break; handler(info.second, f)?.let(io::write) }
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** Finds the server_name extension in a ClientHello record. */
    private fun parseSni(hello: ByteArray): String? {
        var p = 5 + 4 + 2 + 32
        p += 1 + (hello[p].toInt() and 0xff)
        p += 2 + (((hello[p].toInt() and 0xff) shl 8) or (hello[p + 1].toInt() and 0xff))
        p += 1 + (hello[p].toInt() and 0xff)
        val end = p + 2 + (((hello[p].toInt() and 0xff) shl 8) or (hello[p + 1].toInt() and 0xff))
        p += 2
        while (p + 4 <= end) {
            val type = ((hello[p].toInt() and 0xff) shl 8) or (hello[p + 1].toInt() and 0xff)
            val len = ((hello[p + 2].toInt() and 0xff) shl 8) or (hello[p + 3].toInt() and 0xff)
            if (type == 0) {
                val n = ((hello[p + 7].toInt() and 0xff) shl 8) or (hello[p + 8].toInt() and 0xff)
                return String(hello, p + 9, n, Charsets.US_ASCII)
            }
            p += 4 + len
        }
        return null
    }

    override fun close() { server.close() }
}

fun hexBytes(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

val KEY16: ByteArray = hexBytes("00112233445566778899aabbccddeeff")
