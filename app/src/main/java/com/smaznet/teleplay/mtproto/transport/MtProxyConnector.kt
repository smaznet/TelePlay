package com.smaznet.teleplay.mtproto.transport

import com.smaznet.teleplay.domain.proxy.MtSecret
import com.smaznet.teleplay.domain.proxy.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Opens a [Transport] to Telegram through an MTProto proxy: plain, `dd` (padded) or `ee` (fake TLS) secrets. */
object MtProxyConnector {
    suspend fun connect(proxy: Proxy, dcId: Int, timeoutMs: Int = TcpTransport.CONNECT_TIMEOUT_MS): TcpTransport {
        return try {
            open(proxy, dcId, timeoutMs)
        } catch (e: ProxyException) {
            // A fake-TLS proxy that answers with a wrong digest has not accepted our timestamp: learn the real time and retry once.
            val secret = proxy.secret
            if (secret is MtSecret.FakeTls && e.cause is DigestMismatch &&
                withContext(Dispatchers.IO) { ProxyClock.learn(listOf(secret.domain, proxy.server).distinct()) }
            ) open(proxy, dcId, timeoutMs) else throw e
        }
    }

    private suspend fun open(proxy: Proxy, dcId: Int, timeoutMs: Int): TcpTransport = withContext(Dispatchers.IO) {
        val secret = proxy.secret ?: throw ProxyException(ProxyFailure.HANDSHAKE_FAILED)
        val s = try {
            TcpTransport.openSocket(proxy.server, proxy.port, timeoutMs)
        } catch (e: IOException) {
            throw ProxyException(ProxyFailure.UNREACHABLE, e)
        }
        try {
            val rawIn: java.io.InputStream
            val rawOut: java.io.OutputStream
            val tag: Int
            when (secret) {
                is MtSecret.Plain -> { rawIn = s.getInputStream(); rawOut = s.getOutputStream(); tag = Obfuscated2.TAG_INTERMEDIATE }
                is MtSecret.Padded -> { rawIn = s.getInputStream(); rawOut = s.getOutputStream(); tag = Obfuscated2.TAG_PADDED }
                is MtSecret.FakeTls -> {
                    val (i, o) = FakeTls.handshake(s, secret.key, secret.domain)
                    rawIn = i; rawOut = o; tag = Obfuscated2.TAG_PADDED
                }
            }
            val ob = Obfuscated2.create(secret.key, tag, dcId)
            s.soTimeout = TcpTransport.READ_TIMEOUT_MS
            rawOut.write(ob.header)
            rawOut.flush()
            TcpTransport.fromSocket(
                s, ob.wrapInput(rawIn), ob.wrapOutput(rawOut),
                padded = tag == Obfuscated2.TAG_PADDED,
                firstFrameFailure = { ProxyException(ProxyFailure.HANDSHAKE_FAILED) },
            )
        } catch (e: ProxyException) {
            try { s.close() } catch (_: IOException) {}
            throw e
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            throw ProxyException(ProxyFailure.HANDSHAKE_FAILED, e)
        }
    }
}
