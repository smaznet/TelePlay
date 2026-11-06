package com.smaznet.teleplay.data.secure

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/**
 * Stores small secrets (bot token, DC sessions) in private files encrypted with AES-GCM under an
 * Android Keystore key. API 23+ uses a Keystore AES key directly; API 21-22 wrap a random AES key
 * with a Keystore RSA key. Never logs.
 */
class SecretStore(private val context: Context) {
    private val dir = File(context.filesDir, "secrets")
    private val wrappedKeyFile = File(dir, "k.bin")

    fun put(name: String, value: ByteArray) {
        dir.mkdirs()
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        val enc = cipher.doFinal(value)
        File(dir, file(name)).writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + enc)
    }

    fun get(name: String): ByteArray? {
        val f = File(dir, file(name))
        if (!f.exists()) return null
        return try {
            val b = f.readBytes()
            val ivLen = b[0].toInt()
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, b, 1, ivLen))
            cipher.doFinal(b, 1 + ivLen, b.size - 1 - ivLen)
        } catch (e: Exception) {
            null
        }
    }

    fun remove(name: String) {
        File(dir, file(name)).delete()
    }

    /** Deletes every stored secret and the Keystore keys. */
    fun wipe() {
        dir.deleteRecursively()
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            ks.deleteEntry(AES_ALIAS)
            ks.deleteEntry(RSA_ALIAS)
        } catch (_: Exception) {
        }
    }

    private fun file(name: String): String {
        require(name.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        return "$name.bin"
    }

    private fun aesKey(): SecretKey =
        if (Build.VERSION.SDK_INT >= 23) keystoreAesKey() else wrappedAesKey()

    private fun keystoreAesKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(AES_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Suppress("DEPRECATION")
    private fun wrappedAesKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(RSA_ALIAS)) {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            val spec = KeyPairGeneratorSpec.Builder(context)
                .setAlias(RSA_ALIAS)
                .setSubject(X500Principal("CN=TelePlay"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.time)
                .setEndDate(end.time)
                .build()
            KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        }
        val entry = ks.getEntry(RSA_ALIAS, null) as KeyStore.PrivateKeyEntry
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        if (!wrappedKeyFile.exists()) {
            dir.mkdirs()
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            rsa.init(Cipher.ENCRYPT_MODE, entry.certificate.publicKey)
            wrappedKeyFile.writeBytes(rsa.doFinal(raw))
        }
        rsa.init(Cipher.DECRYPT_MODE, entry.privateKey)
        return SecretKeySpec(rsa.doFinal(wrappedKeyFile.readBytes()), "AES")
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_ALIAS = "teleplay_secret_aes"
        const val RSA_ALIAS = "teleplay_secret_rsa"
        const val AES_GCM = "AES/GCM/NoPadding"
    }
}
