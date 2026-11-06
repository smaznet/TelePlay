package com.smaznet.teleplay.data.proxy

import android.content.Context
import com.smaznet.teleplay.data.secure.SecretStore
import com.smaznet.teleplay.domain.proxy.MtSecret
import com.smaznet.teleplay.domain.proxy.Proxy
import com.smaznet.teleplay.domain.proxy.ProxyKind
import com.smaznet.teleplay.domain.proxy.ProxyTestResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Everything persisted about saved proxies. */
class ProxyBlob(val items: List<Proxy>, val selectedId: Long?, val nextId: Long) {
    override fun toString() = "ProxyBlob(count=${items.size})"

    companion object { val EMPTY = ProxyBlob(emptyList(), null, 1L) }
}

interface ProxyStore {
    fun load(): ProxyBlob
    fun save(blob: ProxyBlob)

    /** The encrypted-at-rest form's plaintext bytes, to carry proxies across a [SecretStore.wipe] (disconnect keeps proxies). */
    fun snapshot(): ByteArray?
    fun restore(snapshot: ByteArray?)
}

/** [ProxyStore] backed by the Keystore-encrypted [SecretStore]: one versioned binary blob, never logged. */
class SecretProxyStore(private val secrets: SecretStore) : ProxyStore {
    override fun load(): ProxyBlob = secrets.get(NAME)?.let { runCatching { decode(it) }.getOrNull() } ?: ProxyBlob.EMPTY

    override fun save(blob: ProxyBlob) { secrets.put(NAME, encode(blob)) }

    override fun snapshot(): ByteArray? = secrets.get(NAME)

    override fun restore(snapshot: ByteArray?) { if (snapshot != null) secrets.put(NAME, snapshot) }

    companion object {
        const val NAME = "proxies"
        private const val VERSION = 1

        fun encode(blob: ProxyBlob): ByteArray {
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { o ->
                o.writeByte(VERSION)
                o.writeLong(blob.nextId)
                o.writeLong(blob.selectedId ?: -1L)
                o.writeInt(blob.items.size)
                for (p in blob.items) {
                    o.writeLong(p.id)
                    o.writeByte(p.kind.ordinal)
                    o.writeUTF(p.server)
                    o.writeInt(p.port)
                    o.writeUTF(p.name.orEmpty())
                    o.writeUTF(p.username.orEmpty()); o.writeBoolean(p.username != null)
                    o.writeUTF(p.password.orEmpty()); o.writeBoolean(p.password != null)
                    when (val s = p.secret) {
                        null -> o.writeByte(0)
                        is MtSecret.Plain -> { o.writeByte(1); o.write(s.key) }
                        is MtSecret.Padded -> { o.writeByte(2); o.write(s.key) }
                        is MtSecret.FakeTls -> { o.writeByte(3); o.write(s.key); o.writeUTF(s.domain) }
                    }
                    o.writeByte(testCode(p.lastTest))
                    o.writeLong((p.lastTest as? ProxyTestResult.Working)?.latencyMs ?: 0L)
                    o.writeLong(p.lastTestAt)
                }
            }
            return bos.toByteArray()
        }

        fun decode(bytes: ByteArray): ProxyBlob {
            DataInputStream(ByteArrayInputStream(bytes)).use { i ->
                require(i.readUnsignedByte() == VERSION)
                val nextId = i.readLong()
                val selected = i.readLong().takeIf { it >= 0 }
                val n = i.readInt()
                require(n in 0..10_000)
                val items = ArrayList<Proxy>(n)
                repeat(n) {
                    val id = i.readLong()
                    val kind = ProxyKind.entries[i.readUnsignedByte()]
                    val server = i.readUTF()
                    val port = i.readInt()
                    val name = i.readUTF().takeIf { it.isNotEmpty() }
                    val userText = i.readUTF(); val user = userText.takeIf { i.readBoolean() }
                    val passText = i.readUTF(); val pass = passText.takeIf { i.readBoolean() }
                    val secret: MtSecret? = when (i.readUnsignedByte()) {
                        0 -> null
                        1 -> MtSecret.Plain(ByteArray(16).also { i.readFully(it) })
                        2 -> MtSecret.Padded(ByteArray(16).also { i.readFully(it) })
                        3 -> { val k = ByteArray(16).also { i.readFully(it) }; MtSecret.FakeTls(k, i.readUTF()) }
                        else -> error("bad secret")
                    }
                    val code = i.readUnsignedByte()
                    val latency = i.readLong()
                    val at = i.readLong()
                    items.add(Proxy(id, kind, server, port, name, user, pass, secret, testFrom(code, latency), at))
                }
                return ProxyBlob(items, selected, nextId)
            }
        }

        private fun testCode(r: ProxyTestResult?) = when (r) {
            null -> 0
            is ProxyTestResult.Working -> 1
            ProxyTestResult.AuthRejected -> 2
            ProxyTestResult.SecretRejected -> 3
            ProxyTestResult.NotReachable -> 4
            ProxyTestResult.TimedOut -> 5
        }

        private fun testFrom(code: Int, latency: Long): ProxyTestResult? = when (code) {
            1 -> ProxyTestResult.Working(latency)
            2 -> ProxyTestResult.AuthRejected
            3 -> ProxyTestResult.SecretRejected
            4 -> ProxyTestResult.NotReachable
            5 -> ProxyTestResult.TimedOut
            else -> null
        }
    }
}

/** The proxy on/off switch, kept outside the secret store (it is not a secret). */
interface ProxyFlagPrefs {
    var enabled: Boolean
}

class SharedProxyPrefs(context: Context) : ProxyFlagPrefs {
    private val prefs = context.getSharedPreferences("teleplay_proxy", Context.MODE_PRIVATE)
    override var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
}
