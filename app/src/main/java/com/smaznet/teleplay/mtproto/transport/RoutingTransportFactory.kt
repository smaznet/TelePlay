package com.smaznet.teleplay.mtproto.transport

import com.smaznet.teleplay.domain.proxy.ActiveRoute
import com.smaznet.teleplay.domain.proxy.ProxyKind
import java.io.IOException

/**
 * The only way the app reaches Telegram. The route is read on every connect: direct, SOCKS5, or an MTProto proxy.
 * While a proxy is active there is no fallback to a direct connection on any failure (FR-002).
 */
class RoutingTransportFactory(
    private val handshakeTimeoutMs: Int = TcpTransport.CONNECT_TIMEOUT_MS,
    private val route: () -> ActiveRoute,
) : TransportFactory {
    override suspend fun connect(host: String, port: Int, dcId: Int): Transport =
        when (val r = route()) {
            ActiveRoute.Direct -> TcpTransport.connect(host, port)
            is ActiveRoute.Through -> when (r.proxy.kind) {
                ProxyKind.SOCKS5 -> viaSocks5(r, host, port)
                ProxyKind.MTPROTO -> MtProxyConnector.connect(r.proxy, dcId, handshakeTimeoutMs)
            }
        }

    private suspend fun viaSocks5(r: ActiveRoute.Through, host: String, port: Int): Transport {
        val s = Socks5Connector.connect(r.proxy, host, port, handshakeTimeoutMs)
        try {
            val out = s.getOutputStream()
            out.write(TcpTransport.INTERMEDIATE_INIT)
            out.flush()
            return TcpTransport.fromSocket(s, s.getInputStream(), out)
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            throw ProxyException(ProxyFailure.HANDSHAKE_FAILED, e)
        }
    }
}
