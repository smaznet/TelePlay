package com.smaznet.teleplay.mtproto.session

import com.smaznet.teleplay.mtproto.crypto.AesIge
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** MTProto 2.0 packet protection: key derivation, padding, msg_key. */
object Mtproto2 {
    private val random = SecureRandom()

    const val FROM_CLIENT = 0
    const val FROM_SERVER = 8

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private class Keys(val key: ByteArray, val iv: ByteArray)

    private fun derive(authKey: ByteArray, msgKey: ByteArray, x: Int): Keys {
        val a = sha256(msgKey, authKey.copyOfRange(x, x + 36))
        val b = sha256(authKey.copyOfRange(40 + x, 40 + x + 36), msgKey)
        val key = a.copyOfRange(0, 8) + b.copyOfRange(8, 24) + a.copyOfRange(24, 32)
        val iv = b.copyOfRange(0, 8) + a.copyOfRange(8, 24) + b.copyOfRange(24, 32)
        return Keys(key, iv)
    }

    private fun msgKey(authKey: ByteArray, x: Int, plain: ByteArray): ByteArray =
        sha256(authKey.copyOfRange(88 + x, 88 + x + 32), plain).copyOfRange(8, 24)

    /** Pads `inner` (salt..body) with 12-1024 random bytes to a multiple of 16 and encrypts it. */
    fun encrypt(authKey: ByteArray, inner: ByteArray, from: Int = FROM_CLIENT): Pair<ByteArray, ByteArray> {
        var pad = 12 + random.nextInt(16)
        while ((inner.size + pad) % 16 != 0) pad++
        val plain = inner + ByteArray(pad).also { random.nextBytes(it) }
        val msgKey = msgKey(authKey, from, plain)
        val k = derive(authKey, msgKey, from)
        return msgKey to AesIge.encrypt(plain, k.key, k.iv)
    }

    /** Decrypts and verifies msg_key; returns the padded plaintext. Throws IOException on tampering. */
    fun decrypt(authKey: ByteArray, msgKey: ByteArray, encrypted: ByteArray, from: Int = FROM_SERVER): ByteArray {
        if (encrypted.isEmpty() || encrypted.size % 16 != 0) throw IOException("bad encrypted length")
        val k = derive(authKey, msgKey, from)
        val plain = AesIge.decrypt(encrypted, k.key, k.iv)
        if (!MessageDigest.isEqual(msgKey(authKey, from, plain), msgKey)) throw IOException("msg_key mismatch")
        return plain
    }
}
