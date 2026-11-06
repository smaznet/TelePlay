package com.smaznet.teleplay.mtproto.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

object Rsa {
    private val random = SecureRandom()

    /** Low 64 bits of SHA-1 over the TL-serialized (n, e) as `bytes`, read little-endian. */
    internal fun fingerprint(n: BigInteger, e: BigInteger): Long {
        val sha = MessageDigest.getInstance("SHA-1")
            .digest(tlBytes(n) + tlBytes(e))
        var v = 0L
        for (i in 0 until 8) v = v or ((sha[12 + i].toLong() and 0xff) shl (8 * i))
        return v
    }

    private fun tlBytes(x: BigInteger): ByteArray {
        var b = x.toByteArray()
        if (b.size > 1 && b[0] == 0.toByte()) b = b.copyOfRange(1, b.size)
        val head = if (b.size < 254) byteArrayOf(b.size.toByte())
        else byteArrayOf(254.toByte(), b.size.toByte(), (b.size shr 8).toByte(), (b.size shr 16).toByte())
        val raw = head + b
        return raw + ByteArray((4 - raw.size % 4) % 4)
    }

    /** Fingerprint of the first pinned key the server offers, or null if none is known. */
    fun selectFingerprint(offered: List<Long>): Long? =
        ServerKeys.moduli.map { fingerprint(it, ServerKeys.exponent) }.firstOrNull { it in offered }

    /**
     * RSA_PAD (required by current servers): `data` (at most 144 bytes) is padded to 192 bytes, reversed,
     * hashed with a random temp key, AES-IGE encrypted with a zero IV, the temp key is XORed with
     * SHA-256 of the ciphertext, and the 256-byte result (which must be below the modulus) is RSA-encrypted.
     */
    fun encrypt(fingerprint: Long, data: ByteArray): ByteArray {
        require(data.size <= 144) { "p_q_inner_data too long" }
        val n = ServerKeys.moduli.first { fingerprint(it, ServerKeys.exponent) == fingerprint }
        val sha256 = { b: ByteArray -> MessageDigest.getInstance("SHA-256").digest(b) }
        while (true) {
            val padded = ByteArray(192).also { random.nextBytes(it) }
            System.arraycopy(data, 0, padded, 0, data.size)
            val tempKey = ByteArray(32).also { random.nextBytes(it) }
            val withHash = padded.reversedArray() + sha256(tempKey + padded)
            val aes = AesIge.encrypt(withHash, tempKey, ByteArray(32))
            val shaAes = sha256(aes)
            val keyAes = ByteArray(32) { (tempKey[it].toInt() xor shaAes[it].toInt()).toByte() } + aes
            val m = BigInteger(1, keyAes)
            if (m >= n) continue
            val c = m.modPow(ServerKeys.exponent, n).toByteArray()
            val out = ByteArray(256)
            val src = if (c.size > 256) c.copyOfRange(c.size - 256, c.size) else c
            System.arraycopy(src, 0, out, 256 - src.size, src.size)
            return out
        }
    }
}
