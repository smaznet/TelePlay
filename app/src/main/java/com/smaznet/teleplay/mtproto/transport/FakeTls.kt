package com.smaznet.teleplay.mtproto.transport

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Client side of the MTProto proxy fake-TLS mode (`ee` secrets, research R5): a TLS-1.3-looking ClientHello
 * towards a cover domain whose random field is an HMAC of the hello under the proxy secret, a check of the
 * server's reply digest, then TLS application-data records carrying the obfuscated stream. No real TLS is spoken.
 */
/** The server's reply did not carry the digest our secret and hello predict. */
class DigestMismatch : IOException("digest mismatch")

object FakeTls {
    const val RECORD_MAX = 16_384
    private const val HELLO_RECORD_SIZE = 517
    private const val RANDOM_OFFSET = 11
    private val CCS = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)

    /** Handshake over [socket]; returns the record-framed streams the obfuscated layer runs over. */
    fun handshake(
        socket: java.net.Socket, key: ByteArray, domain: String,
        nowSeconds: () -> Long = ProxyClock::nowSeconds,
        random: SecureRandom = SecureRandom(),
    ): Pair<InputStream, OutputStream> {
        val out = socket.getOutputStream()
        val raw = socket.getInputStream().buffered(64 * 1024)
        val din = DataInputStream(raw)

        val hello = clientHello(key, domain, nowSeconds(), random)
        out.write(hello); out.flush()
        val clientRandom = hello.copyOfRange(RANDOM_OFFSET, RANDOM_OFFSET + 32)

        val response = ByteArrayOutputStream()
        fun record(expectType: Int): ByteArray {
            val head = ByteArray(5)
            din.readFully(head)
            if ((head[0].toInt() and 0xff) != expectType || head[1].toInt() != 3 || head[2].toInt() != 3) throw ProxyException(ProxyFailure.HANDSHAKE_FAILED)
            val len = ((head[3].toInt() and 0xff) shl 8) or (head[4].toInt() and 0xff)
            if (len > RECORD_MAX + 256) throw ProxyException(ProxyFailure.HANDSHAKE_FAILED)
            val body = ByteArray(len)
            din.readFully(body)
            response.write(head); response.write(body)
            return head + body
        }
        try {
            val serverHello = record(0x16)
            if (serverHello.size < RANDOM_OFFSET + 32 || serverHello[5].toInt() != 2) throw ProxyException(ProxyFailure.HANDSHAKE_FAILED)
            record(0x14)
            record(0x17)
        } catch (e: ProxyException) {
            throw e
        } catch (e: IOException) {
            throw ProxyException(ProxyFailure.HANDSHAKE_FAILED, e)
        }
        val all = response.toByteArray()
        val digest = all.copyOfRange(RANDOM_OFFSET, RANDOM_OFFSET + 32)
        java.util.Arrays.fill(all, RANDOM_OFFSET, RANDOM_OFFSET + 32, 0)
        val expected = hmac(key, clientRandom + all)
        // A wrong digest from something that otherwise talks TLS is what a proxy does when it does not accept our timestamp.
        if (!MessageDigest.isEqual(digest, expected)) throw ProxyException(ProxyFailure.HANDSHAKE_FAILED, DigestMismatch())
        return TlsRecordInput(raw) to TlsRecordOutput(out)
    }

    /** The ClientHello record (517 bytes unless the domain is very long). Exposed for tests and the fake proxy. */
    fun clientHello(key: ByteArray, domain: String, nowSeconds: Long, random: SecureRandom): ByteArray {
        fun grease(@Suppress("UNUSED_PARAMETER") i: Int): Int { val g = 0x0a + 0x10 * (random.nextInt(16)); return (g shl 8) or g }
        val hs = ByteArrayOutputStream()
        fun u8(v: Int) = hs.write(v)
        fun u16(v: Int) { hs.write(v ushr 8); hs.write(v) }

        u16(0x0303)
        hs.write(ByteArray(32)) // digest placeholder
        u8(32); hs.write(ByteArray(32).also { random.nextBytes(it) })
        u16(32)
        u16(grease(0))
        hs.write(byteArrayOf(0x13, 0x01, 0x13, 0x02, 0x13, 0x03, 0xc0.toByte(), 0x2b, 0xc0.toByte(), 0x2f, 0xc0.toByte(), 0x2c, 0xc0.toByte(), 0x30,
            0xcc.toByte(), 0xa9.toByte(), 0xcc.toByte(), 0xa8.toByte(), 0xc0.toByte(), 0x13, 0xc0.toByte(), 0x14, 0x00, 0x9c.toByte(), 0x00, 0x9d.toByte(),
            0x00, 0x2f, 0x00, 0x35))
        u8(1); u8(0)

        val ext = ByteArrayOutputStream()
        fun e16(v: Int) { ext.write(v ushr 8); ext.write(v) }
        fun extension(type: Int, body: ByteArray) { e16(type); e16(body.size); ext.write(body) }
        val domainBytes = domain.toByteArray(Charsets.US_ASCII)
        extension(grease(2), ByteArray(0))
        run {
            val list = ByteArrayOutputStream()
            list.write(0); list.write(domainBytes.size ushr 8); list.write(domainBytes.size); list.write(domainBytes)
            val l = list.toByteArray()
            extension(0x0000, byteArrayOf((l.size ushr 8).toByte(), l.size.toByte()) + l)
        }
        extension(0x0017, ByteArray(0))
        extension(0xff01, byteArrayOf(0))
        run { val g = grease(4); extension(0x000a, byteArrayOf(0, 8, (g ushr 8).toByte(), g.toByte(), 0, 0x1d, 0, 0x17, 0, 0x18)) }
        extension(0x000b, byteArrayOf(1, 0))
        extension(0x0023, ByteArray(0))
        extension(0x0010, byteArrayOf(0, 0x0c, 2, 0x68, 0x32, 8, 0x68, 0x74, 0x74, 0x70, 0x2f, 0x31, 0x2e, 0x31))
        extension(0x0005, byteArrayOf(1, 0, 0, 0, 0))
        extension(0x000d, byteArrayOf(0, 0x12, 4, 3, 8, 4, 4, 1, 5, 3, 8, 5, 5, 1, 8, 6, 6, 1, 2, 1))
        extension(0x0012, ByteArray(0))
        run {
            val g = grease(4)
            val share = ByteArrayOutputStream()
            share.write(g ushr 8); share.write(g); share.write(0); share.write(1); share.write(0)
            share.write(0); share.write(0x1d); share.write(0); share.write(0x20); share.write(ByteArray(32).also { random.nextBytes(it) })
            val s = share.toByteArray()
            extension(0x0033, byteArrayOf((s.size ushr 8).toByte(), s.size.toByte()) + s)
        }
        extension(0x002d, byteArrayOf(1, 1))
        run { val g = grease(6); extension(0x002b, byteArrayOf(0x0a, (g ushr 8).toByte(), g.toByte(), 3, 4, 3, 3, 3, 2, 3, 1)) }
        extension(0x001b, byteArrayOf(2, 0, 2))
        extension(grease(3), byteArrayOf(0))

        // handshake header(4) + fixed part so far + extensions length(2) + extensions; pad to the usual record size
        val fixed = hs.size()
        var extBytes = ext.toByteArray()
        val total = 5 + 4 + fixed + 2 + extBytes.size
        val room = HELLO_RECORD_SIZE - total
        if (room >= 4) {
            val padLen = room - 4
            val padded = ByteArrayOutputStream()
            padded.write(extBytes)
            padded.write(0x00); padded.write(0x15); padded.write(padLen ushr 8); padded.write(padLen); padded.write(ByteArray(padLen))
            extBytes = padded.toByteArray()
        }

        val body = ByteArrayOutputStream()
        body.write(hs.toByteArray())
        body.write(extBytes.size ushr 8); body.write(extBytes.size); body.write(extBytes)
        val b = body.toByteArray()

        val rec = ByteArrayOutputStream()
        rec.write(0x16); rec.write(0x03); rec.write(0x01)
        rec.write((b.size + 4) ushr 8); rec.write(b.size + 4)
        rec.write(0x01); rec.write(b.size ushr 16); rec.write(b.size ushr 8); rec.write(b.size)
        rec.write(b)
        val hello = rec.toByteArray()

        val digest = hmac(key, hello)
        for (i in 0 until 4) digest[28 + i] = (digest[28 + i].toInt() xor (nowSeconds ushr (8 * i)).toInt()).toByte()
        System.arraycopy(digest, 0, hello, RANDOM_OFFSET, 32)
        return hello
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** Unwraps TLS application-data records (type 0x17); change-cipher-spec records (0x14) are skipped. */
    internal class TlsRecordInput(private val src: InputStream) : InputStream() {
        private var left = 0

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (left == 0) {
                val h = ByteArray(5)
                var got = 0
                while (got < 5) { val n = src.read(h, got, 5 - got); if (n < 0) return if (got == 0) -1 else throw IOException("truncated record"); got += n }
                val type = h[0].toInt() and 0xff
                val n = ((h[3].toInt() and 0xff) shl 8) or (h[4].toInt() and 0xff)
                when (type) {
                    0x17 -> left = n
                    0x14 -> { var s = n; while (s > 0) { val k = src.skip(s.toLong()).toInt(); if (k <= 0) { if (src.read() < 0) throw IOException("truncated record"); s-- } else s -= k } }
                    else -> throw IOException("unexpected record")
                }
            }
            val r = src.read(b, off, minOf(len, left))
            if (r < 0) throw IOException("truncated record")
            left -= r
            return r
        }
    }

    /** Wraps every write in application-data records of at most [RECORD_MAX] bytes; the first write is preceded by a ChangeCipherSpec. */
    internal class TlsRecordOutput(private val dst: OutputStream) : OutputStream() {
        private var first = true

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val buf = ByteArrayOutputStream(len + 16)
            if (first) { buf.write(CCS); first = false }
            var o = off
            var rest = len
            while (rest > 0) {
                val n = minOf(rest, RECORD_MAX)
                buf.write(0x17); buf.write(0x03); buf.write(0x03); buf.write(n ushr 8); buf.write(n)
                buf.write(b, o, n)
                o += n; rest -= n
            }
            dst.write(buf.toByteArray())
        }

        override fun flush() = dst.flush()
    }
}
