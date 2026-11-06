package com.smaznet.teleplay.domain.proxy

import com.smaznet.teleplay.data.telegram.DcDirectory
import com.smaznet.teleplay.mtproto.tl.ReqPqMulti
import com.smaznet.teleplay.mtproto.tl.ResPq
import com.smaznet.teleplay.mtproto.tl.TlReader
import com.smaznet.teleplay.mtproto.tl.toBytes
import com.smaznet.teleplay.mtproto.tl.TlWriter
import com.smaznet.teleplay.mtproto.transport.ProxyException
import com.smaznet.teleplay.mtproto.transport.ProxyFailure
import com.smaznet.teleplay.mtproto.transport.RoutingTransportFactory
import com.smaznet.teleplay.mtproto.transport.TcpTransport
import com.smaznet.teleplay.mtproto.transport.Transport
import com.smaznet.teleplay.mtproto.transport.TransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.security.SecureRandom

fun interface ProxyTesting { suspend fun test(proxy: Proxy): ProxyTestResult }

/**
 * Tests a proxy by going through it to a Telegram data center and completing the unauthenticated
 * `req_pq_multi` round trip. No token, key or chat content is involved. Never logs.
 */
class ProxyTester(
    private val directory: DcDirectory = DcDirectory(),
    private val timeoutMs: Long = 10_000,
    private val factoryFor: (Proxy, Int) -> TransportFactory = { p, handshakeMs -> RoutingTransportFactory(handshakeMs) { ActiveRoute.Through(p) } },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val dcId: Int = DcDirectory.DEFAULT_DC,
) : ProxyTesting {
    override suspend fun test(proxy: Proxy): ProxyTestResult {
        val started = nowMillis()
        var transport: Transport? = null
        return try {
            withTimeout(timeoutMs) {
                val dc = dcId
                val a = directory.address(dc)
                val t = factoryFor(proxy, timeoutMs.toInt()).connect(a.host, a.port, dc)
                transport = t
                // Blocking reads ignore coroutine cancellation, so give the socket the remaining time itself.
                (t as? TcpTransport)?.setReadTimeout((timeoutMs - (nowMillis() - started)).coerceAtLeast(1).toInt())
                val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                t.send(unencrypted(ReqPqMulti(nonce).toBytes()))
                val r = TlReader(t.receive())
                if (r.int64() != 0L) throw IOException("not a handshake reply")
                r.int64()
                val len = r.int32()
                if (len > r.remaining) throw IOException("bad length")
                val res = ResPq.read(r)
                if (!res.nonce.contentEquals(nonce)) throw IOException("nonce mismatch")
                ProxyTestResult.Working((nowMillis() - started).coerceAtLeast(0))
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) ProxyTestResult.TimedOut else throw e
        } catch (e: ProxyException) {
            if (e.cause is java.net.SocketTimeoutException) return ProxyTestResult.TimedOut
            when (e.reason) {
                ProxyFailure.AUTH_REJECTED -> ProxyTestResult.AuthRejected
                ProxyFailure.HANDSHAKE_FAILED ->
                    if (proxy.kind == ProxyKind.MTPROTO) ProxyTestResult.SecretRejected else ProxyTestResult.NotReachable
                else -> ProxyTestResult.NotReachable
            }
        } catch (_: java.net.SocketTimeoutException) {
            ProxyTestResult.TimedOut
        } catch (_: Exception) {
            // A silent close right after the first bytes means the proxy did not accept the secret.
            ProxyTestResult.NotReachable
        } finally {
            transport?.close()
        }
    }

    private fun unencrypted(payload: ByteArray): ByteArray {
        val w = TlWriter()
        w.int64(0L)
        w.int64(((nowMillis() / 1000) shl 32) and 3L.inv())
        w.int32(payload.size)
        w.raw(payload)
        return w.toByteArray()
    }
}
