package com.smaznet.teleplay.mtproto.transport

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The "obfuscated2" stream layer MTProto proxies speak (research R3): a 64-byte random-looking header carrying
 * the protocol tag and the target data center, then every byte in both directions through AES-256-CTR. The
 * proxy secret is mixed into both keys. Secrets and key material are never logged.
 */
class Obfuscated2 private constructor(
    /** The 64 bytes to send first (first 56 plain, last 8 encrypted). */
    val header: ByteArray,
    private val encrypt: Cipher,
    private val decrypt: Cipher,
) {
    fun wrapInput(raw: InputStream): InputStream = object : FilterInputStream(raw) {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = `in`.read(b, off, len)
            if (n > 0) decrypt.update(b, off, n, b, off)
            return n
        }
    }

    fun wrapOutput(raw: OutputStream): OutputStream = object : FilterOutputStream(raw) {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(encrypt.update(b, off, len)) }
    }

    companion object {
        const val TAG_INTERMEDIATE = 0xeeeeeeee.toInt()
        const val TAG_PADDED = 0xdddddddd.toInt()

        private val FORBIDDEN_FIRST = setOf(
            0x44414548, // HEAD
            0x54534f50, // POST
            0x20544547, // GET
            0x4954504f, // OPTI
            0xeeeeeeee.toInt(), 0xdddddddd.toInt(),
            0x02010316,
        )

        /** [dcId] is positive for ordinary data centers and negative for media ones. */
        fun create(secret: ByteArray, tag: Int, dcId: Int, random: SecureRandom = SecureRandom()): Obfuscated2 {
            val init = ByteArray(64)
            do { random.nextBytes(init) } while (!validInit(init))
            writeLe32(init, 56, tag)
            init[60] = dcId.toByte(); init[61] = (dcId shr 8).toByte()

            val reversed = init.copyOfRange(8, 56).reversedArray()
            val enc = ctr(sha256(init.copyOfRange(8, 40), secret), init.copyOfRange(40, 56))
            val dec = ctr(sha256(reversed.copyOfRange(0, 32), secret), reversed.copyOfRange(32, 48))
            val encryptedInit = enc.update(init)
            val header = init.copyOf()
            System.arraycopy(encryptedInit, 56, header, 56, 8)
            return Obfuscated2(header, enc, dec)
        }

        /** Rules from the transport description: no first byte 0xef, no first dword that looks like HTTP or another transport, second dword non-zero. */
        internal fun validInit(init: ByteArray): Boolean {
            if ((init[0].toInt() and 0xff) == 0xef) return false
            if (readLe32(init, 0) in FORBIDDEN_FIRST) return false
            if (readLe32(init, 4) == 0) return false
            return true
        }

        /** Server side of the same handshake, used by tests' fake proxy and to read a client header. Returns null if the header is garbage. */
        internal fun accept(header: ByteArray, secret: ByteArray): Pair<Obfuscated2, Pair<Int, Int>>? {
            val dec = ctr(sha256(header.copyOfRange(8, 40), secret), header.copyOfRange(40, 56))
            val plain = dec.update(header)
            val reversed = header.copyOfRange(8, 56).reversedArray()
            val enc = ctr(sha256(reversed.copyOfRange(0, 32), secret), reversed.copyOfRange(32, 48))
            val tag = readLe32(plain, 56)
            if (tag != TAG_INTERMEDIATE && tag != TAG_PADDED) return null
            val dc = ((plain[61].toInt() shl 8) or (plain[60].toInt() and 0xff)).toShort().toInt()
            return Obfuscated2(header, enc, dec) to (tag to dc)
        }

        private fun sha256(a: ByteArray, b: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").run { update(a); update(b); digest() }

        private fun ctr(key: ByteArray, iv: ByteArray): Cipher =
            Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }

        private fun readLe32(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

        private fun writeLe32(b: ByteArray, o: Int, v: Int) {
            b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
        }
    }
}
