package com.smaznet.teleplay.mtproto.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** AES-IGE (256-bit key in MTProto) over AES/ECB/NoPadding. `iv` is 32 bytes: y0 (16) followed by x0 (16). */
object AesIge {
    fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray = run(data, key, iv, true)

    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray = run(data, key, iv, false)

    private fun run(data: ByteArray, key: ByteArray, iv: ByteArray, encrypt: Boolean): ByteArray {
        require(data.size % 16 == 0) { "IGE data must be a multiple of 16 bytes" }
        require(key.size == 16 || key.size == 24 || key.size == 32)
        require(iv.size == 32)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = ByteArray(data.size)
        // Encrypt: c_i = E(p_i ^ c_{i-1}) ^ p_{i-1}.  Decrypt: p_i = D(c_i ^ p_{i-1}) ^ c_{i-1}.
        // `pre` is XORed before the block cipher, `post` after; iv = c_0 (16) followed by p_0 (16).
        val pre = if (encrypt) iv.copyOfRange(0, 16) else iv.copyOfRange(16, 32)
        val post = if (encrypt) iv.copyOfRange(16, 32) else iv.copyOfRange(0, 16)
        val block = ByteArray(16)
        var off = 0
        while (off < data.size) {
            for (i in 0 until 16) block[i] = (data[off + i].toInt() xor pre[i].toInt()).toByte()
            cipher.update(block, 0, 16, block, 0)
            for (i in 0 until 16) block[i] = (block[i].toInt() xor post[i].toInt()).toByte()
            System.arraycopy(block, 0, out, off, 16)
            if (encrypt) {
                System.arraycopy(block, 0, pre, 0, 16)           // c_i
                System.arraycopy(data, off, post, 0, 16)         // p_i
            } else {
                System.arraycopy(block, 0, pre, 0, 16)           // p_i
                System.arraycopy(data, off, post, 0, 16)         // c_i
            }
            off += 16
        }
        return out
    }
}
