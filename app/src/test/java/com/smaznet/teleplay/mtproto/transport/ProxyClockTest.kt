package com.smaznet.teleplay.mtproto.transport

import com.smaznet.teleplay.domain.proxy.ActiveRoute
import com.smaznet.teleplay.domain.proxy.MtSecret
import com.smaznet.teleplay.domain.proxy.Proxy
import com.smaznet.teleplay.domain.proxy.ProxyKind
import com.smaznet.teleplay.mtproto.transport.fakes.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** TV clocks drift. A fake-TLS proxy only accepts a timestamp within a few minutes, so the device learns the real time. */
class ProxyClockTest {
    private val domain = "cover.example.org"
    private val secret = MtSecret.FakeTls(KEY16, domain)
    private val defaultEstimator = ProxyClock.estimator

    @Before fun reset() { ProxyClock.restore(0); ProxyClock.onChanged = {}; ProxyClock.estimator = defaultEstimator }
    @After fun cleanup() = reset()

    private fun proxyOf(port: Int) = Proxy(1, ProxyKind.MTPROTO, "127.0.0.1", port, null, null, null, secret)
    private fun open(p: Proxy) = runBlocking { RoutingTransportFactory { ActiveRoute.Through(p) }.connect("x", 0, 2) }
    private fun real() = System.currentTimeMillis() / 1000

    @Test fun aWrongClockIsLearnedFromTheCoverSiteAndTheConnectionRetried() {
        // The proxy's clock is 2 hours ahead of this device's.
        FakeMtProxyServer(secret, serverNowSeconds = { real() + 7200 }, frontOnBadClock = true).use { s ->
            var asked: List<String> = emptyList()
            var saved = 0L
            ProxyClock.onChanged = { saved = it }
            ProxyClock.estimator = { h -> asked = asked + h; real() + 7200 }
            val t = open(proxyOf(s.port))
            runBlocking { t.send(ByteArray(8)); assertEquals(8, t.receive().size) }
            t.close()
            assertEquals(listOf(domain), asked)
            assertTrue(Math.abs(ProxyClock.offsetSeconds - 7200) <= 2)
            assertEquals(ProxyClock.offsetSeconds, saved)
            assertEquals(1, s.rejected)
        }
    }

    @Test fun onceLearnedLaterConnectionsAreRightFirstTime() {
        FakeMtProxyServer(secret, serverNowSeconds = { real() - 4000 }, frontOnBadClock = true).use { s ->
            ProxyClock.estimator = { real() - 4000 }
            open(proxyOf(s.port)).close()
            assertEquals(1, s.rejected)
            ProxyClock.estimator = { error("must not be asked again") }
            open(proxyOf(s.port)).close()
            assertEquals(1, s.rejected)
        }
    }

    @Test fun anAccurateClockNeverTriggersALookup() {
        FakeMtProxyServer(secret).use { s ->
            ProxyClock.estimator = { error("must not be asked") }
            open(proxyOf(s.port)).close()
            assertEquals(0L, ProxyClock.offsetSeconds)
        }
    }

    @Test fun ifTheTimeCannotBeLearnedTheFailureIsReported() {
        FakeMtProxyServer(secret, serverNowSeconds = { real() + 7200 }, frontOnBadClock = true).use { s ->
            ProxyClock.estimator = { null }
            try { open(proxyOf(s.port)); fail() } catch (e: ProxyException) { assertEquals(ProxyFailure.HANDSHAKE_FAILED, e.reason) }
            assertEquals(1, s.rejected)
        }
    }

    @Test fun aWrongSecretIsNotMistakenForAClockProblem() {
        FakeMtProxyServer(secret, frontOnBadClock = true).use { s ->
            var asked = 0
            ProxyClock.estimator = { asked++; real() }
            val wrong = Proxy(1, ProxyKind.MTPROTO, "127.0.0.1", s.port, null, null, null, MtSecret.FakeTls(ByteArray(16) { 9 }, domain))
            try { open(wrong); fail() } catch (e: ProxyException) { assertEquals(ProxyFailure.HANDSHAKE_FAILED, e.reason) }
            assertEquals("asked once, then gave up (offset unchanged)", 1, asked)
            assertEquals(0L, ProxyClock.offsetSeconds)
        }
    }

    @Test fun theDateHeaderIsParsed() {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { c ->
                c.getInputStream().read(ByteArray(512))
                c.getOutputStream().write("HTTP/1.1 301 Moved Permanently\r\nDate: Wed, 04 Nov 2026 10:20:30 GMT\r\nLocation: https://x/\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        }
        val t = ProxyClock.httpDate("127.0.0.1:${server.localPort}")
        assertEquals(java.time.Instant.parse("2026-11-04T10:20:30Z").epochSecond, t)
        server.close()
    }

    @Test fun noAnswerMeansNoTime() {
        val port = ServerSocket(0).use { it.localPort }
        assertNull(ProxyClock.httpDate("127.0.0.1:$port"))
    }
}
