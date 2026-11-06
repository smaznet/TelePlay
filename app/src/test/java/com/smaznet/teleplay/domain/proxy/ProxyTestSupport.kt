package com.smaznet.teleplay.domain.proxy

import com.smaznet.teleplay.data.proxy.ProxyBlob
import com.smaznet.teleplay.data.proxy.ProxyFlagPrefs
import com.smaznet.teleplay.data.proxy.ProxyStore
import com.smaznet.teleplay.data.proxy.SecretProxyStore

class MemoryProxyStore(var blob: ProxyBlob = ProxyBlob.EMPTY) : ProxyStore {
    var saves = 0
    override fun load() = blob
    override fun save(blob: ProxyBlob) { this.blob = blob; saves++ }
    override fun snapshot(): ByteArray? = if (blob.items.isEmpty() && blob.selectedId == null) null else SecretProxyStore.encode(blob)
    override fun restore(snapshot: ByteArray?) { if (snapshot != null) blob = SecretProxyStore.decode(snapshot) }
}

class MemoryProxyPrefs(override var enabled: Boolean = false) : ProxyFlagPrefs

val KEY = ByteArray(16) { (it + 1).toByte() }

fun socksDraft(server: String = "1.2.3.4", port: Int = 1080, user: String? = null, pass: String? = null, name: String? = null) =
    ProxyDraft(ProxyKind.SOCKS5, server, port, name, user, pass)

fun mtDraft(server: String = "mt.example.org", port: Int = 443, secret: MtSecret = MtSecret.Plain(KEY), name: String? = null) =
    ProxyDraft(ProxyKind.MTPROTO, server, port, name, secret = secret)

fun newRepo(store: MemoryProxyStore = MemoryProxyStore(), prefs: MemoryProxyPrefs = MemoryProxyPrefs()) = ProxyRepository(store, prefs, clock = { 1_000L })
