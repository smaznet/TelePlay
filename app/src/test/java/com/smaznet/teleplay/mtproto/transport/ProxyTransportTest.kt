package com.smaznet.teleplay.mtproto.transport

import com.smaznet.teleplay.domain.proxy.ActiveRoute
import com.smaznet.teleplay.domain.proxy.MtSecret
import com.smaznet.teleplay.domain.proxy.Proxy
import com.smaznet.teleplay.domain.proxy.ProxyKind
import com.smaznet.teleplay.mtproto.transport.fakes.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class Socks5ConnectorTest {
    private fun socks(port: Int, user: String? = null, pass: String? = null) =
        Proxy(1, ProxyKind.SOCKS5, "127.0.0.1", port, null, user, pass, null)

    private fun factory(p: Proxy) = RoutingTransportFactory { ActiveRoute.Through(p) }

    private fun failure(block: suspend () -> Unit): ProxyFailure = try {
        runBlocking { block() }; fail("expected a ProxyException"); error("unreachable")
    } catch (e: ProxyException) { e.reason }

    @Test fun noAuthRoundTripThroughTheProxy() = runBlocking {
        FakeDc().use { dc -> FakeSocks5Server().use { s ->
            val t = factory(socks(s.port)).connect("127.0.0.1", dc.port, 2)
            t.send(byteArrayOf(1, 2, 3, 4)); assertArrayEquals(byteArrayOf(1, 2, 3, 4), t.receive()); t.close()
            assertEquals("127.0.0.1" to dc.port, s.lastTarget)
        } }
    }

    @Test fun usernamePasswordAccepted() = runBlocking {
        FakeDc().use { dc -> FakeSocks5Server("bob", "pw").use { s ->
            val t = factory(socks(s.port, "bob", "pw")).connect("127.0.0.1", dc.port, 2)
            t.send(ByteArray(8) { it.toByte() }); assertEquals(8, t.receive().size); t.close()
        } }
    }

    @Test fun wrongPasswordIsAuthRejected() {
        FakeSocks5Server("bob", "pw").use { s ->
            assertEquals(ProxyFailure.AUTH_REJECTED, failure { factory(socks(s.port, "bob", "nope")).connect("127.0.0.1", 9, 2) })
        }
    }

    @Test fun credentialsRequiredButNoneGiven() {
        FakeSocks5Server("bob", "pw").use { s ->
            assertEquals(ProxyFailure.AUTH_REJECTED, failure { factory(socks(s.port)).connect("127.0.0.1", 9, 2) })
        }
    }

    @Test fun connectionNotAllowedIsRefused() {
        FakeSocks5Server(connectReply = 2).use { s ->
            assertEquals(ProxyFailure.REFUSED, failure { factory(socks(s.port)).connect("127.0.0.1", 9, 2) })
        }
    }

    @Test fun hostUnreachableReply() {
        FakeSocks5Server(connectReply = 4).use { s ->
            assertEquals(ProxyFailure.UNREACHABLE, failure { factory(socks(s.port)).connect("127.0.0.1", 9, 2) })
        }
    }

    @Test fun proxyNotListeningIsUnreachable() {
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(ProxyFailure.UNREACHABLE, failure { factory(socks(port)).connect("127.0.0.1", 9, 2) })
    }

    @Test fun truncatedReplyIsHandshakeFailure() {
        val server = ServerSocket(0)
        thread(isDaemon = true) { server.accept().use { it.getInputStream().read(); it.getOutputStream().write(byteArrayOf(5)) } }
        assertEquals(ProxyFailure.HANDSHAKE_FAILED, failure { factory(socks(server.localPort)).connect("127.0.0.1", 9, 2) })
        server.close()
    }

    @Test fun domainTargetsUseTheDomainAddressType() = runBlocking {
        FakeDc().use { dc -> FakeSocks5Server().use { s ->
            factory(socks(s.port)).connect("localhost", dc.port, 2).close()
            assertEquals("localhost" to dc.port, s.lastTarget)
        } }
    }

    @Test fun connectRequestEncodesIpv4AndDomain() {
        assertArrayEquals(byteArrayOf(5, 1, 0, 1, 10, 0, 0, 1, 0x01, 0xbb.toByte()), Socks5Connector.connectRequest("10.0.0.1", 443))
        val d = Socks5Connector.connectRequest("a.io", 80)
        assertArrayEquals(byteArrayOf(5, 1, 0, 3, 4, 'a'.code.toByte(), '.'.code.toByte(), 'i'.code.toByte(), 'o'.code.toByte(), 0, 80), d)
    }
}

class Obfuscated2Test {
    @Test fun headerRulesHold() {
        val rnd = java.security.SecureRandom()
        repeat(200) {
            val o = Obfuscated2.create(KEY16, Obfuscated2.TAG_INTERMEDIATE, 2, rnd)
            val h = o.header
            assertEquals(64, h.size)
            assertNotEquals(0xef, h[0].toInt() and 0xff)
            val first = (h[0].toInt() and 0xff) or ((h[1].toInt() and 0xff) shl 8) or ((h[2].toInt() and 0xff) shl 16) or ((h[3].toInt() and 0xff) shl 24)
            assertFalse(first in listOf(0x44414548, 0x54534f50, 0x20544547, 0x4954504f, 0xeeeeeeee.toInt(), 0xdddddddd.toInt(), 0x02010316))
            assertFalse(h[4] == 0.toByte() && h[5] == 0.toByte() && h[6] == 0.toByte() && h[7] == 0.toByte())
        }
    }

    @Test fun forbiddenPatternsAreRejected() {
        fun init(vararg b: Int) = ByteArray(64) { 1 }.also { a -> b.forEachIndexed { i, v -> a[i] = v.toByte() } }
        assertFalse(Obfuscated2.validInit(init(0xef)))
        assertFalse(Obfuscated2.validInit(init('H'.code, 'E'.code, 'A'.code, 'D'.code)))
        assertFalse(Obfuscated2.validInit(init('P'.code, 'O'.code, 'S'.code, 'T'.code)))
        assertFalse(Obfuscated2.validInit(init('G'.code, 'E'.code, 'T'.code, ' '.code)))
        assertFalse(Obfuscated2.validInit(init(0xee, 0xee, 0xee, 0xee)))
        assertFalse(Obfuscated2.validInit(init(1, 1, 1, 1, 0, 0, 0, 0)))
        assertTrue(Obfuscated2.validInit(init(1, 2, 3, 4, 5, 6, 7, 8)))
    }

    @Test fun serverRecoversTagAndDcAndStreamsRoundTrip() {
        for ((tag, dc) in listOf(Obfuscated2.TAG_INTERMEDIATE to 2, Obfuscated2.TAG_PADDED to -4, Obfuscated2.TAG_PADDED to 203)) {
            val client = Obfuscated2.create(KEY16, tag, dc)
            val (server, info) = Obfuscated2.accept(client.header, KEY16)!!
            assertEquals(tag to dc, info)
            val toServer = java.io.ByteArrayOutputStream()
            client.wrapOutput(toServer).write("hello server".toByteArray())
            val seen = ByteArray(12).also { server.wrapInput(java.io.ByteArrayInputStream(toServer.toByteArray())).read(it) }
            assertEquals("hello server", String(seen))
            val toClient = java.io.ByteArrayOutputStream()
            server.wrapOutput(toClient).write("hi client".toByteArray())
            val back = ByteArray(9).also { client.wrapInput(java.io.ByteArrayInputStream(toClient.toByteArray())).read(it) }
            assertEquals("hi client", String(back))
        }
    }

    @Test fun aWrongSecretDoesNotYieldAHeader() {
        val client = Obfuscated2.create(KEY16, Obfuscated2.TAG_INTERMEDIATE, 2)
        assertNull(Obfuscated2.accept(client.header, hexBytes("ffeeddccbbaa99887766554433221100")))
    }

    @Test fun theSecretIsMixedIntoTheKey() {
        // Two clients with identical randomness but different secrets produce different ciphertext after the header.
        val a = Obfuscated2.create(KEY16, Obfuscated2.TAG_INTERMEDIATE, 2, java.security.SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1) })
        val b = Obfuscated2.create(hexBytes("ffeeddccbbaa99887766554433221100"), Obfuscated2.TAG_INTERMEDIATE, 2, java.security.SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1) })
        assertArrayEquals(a.header.copyOfRange(0, 56), b.header.copyOfRange(0, 56))
        assertFalse(a.header.copyOfRange(56, 64).contentEquals(b.header.copyOfRange(56, 64)))
    }
}

class MtProxyTransportTest {
    private val domain = "cover.example.org"
    private val secrets: List<Pair<String, MtSecret>> = listOf(
        "plain" to MtSecret.Plain(KEY16),
        "dd" to MtSecret.Padded(KEY16),
        "ee" to MtSecret.FakeTls(KEY16, domain),
    )

    private fun proxyOf(port: Int, secret: MtSecret) = Proxy(1, ProxyKind.MTPROTO, "127.0.0.1", port, null, null, null, secret)
    private fun open(p: Proxy, dc: Int = 2) = runBlocking { RoutingTransportFactory { ActiveRoute.Through(p) }.connect("ignored", 0, dc) }

    @Test fun framesRoundTripForEverySecretKind() {
        for ((name, secret) in secrets) FakeMtProxyServer(secret).use { s ->
            val t = open(proxyOf(s.port, secret))
            runBlocking {
                val rnd = java.util.Random(7)
                repeat(40) { i ->
                    val frame = ByteArray(4 * (1 + rnd.nextInt(64))).also { rnd.nextBytes(it) }
                    t.send(frame)
                    assertArrayEquals("$name frame $i", frame, t.receive())
                }
            }
            t.close()
        }
    }

    @Test fun theDcIdAndTagReachTheProxy() {
        for ((name, secret) in secrets) FakeMtProxyServer(secret).use { s ->
            val t = open(proxyOf(s.port, secret), dc = -4)
            runBlocking { t.send(ByteArray(8)); t.receive() }
            assertEquals(name, -4, s.lastDc)
            assertEquals(name, if (secret is MtSecret.Plain) Obfuscated2.TAG_INTERMEDIATE else Obfuscated2.TAG_PADDED, s.lastTag)
            t.close()
        }
    }

    @Test fun largeFramesAreSplitIntoRecordsForFakeTls() {
        val secret = MtSecret.FakeTls(KEY16, domain)
        FakeMtProxyServer(secret).use { s ->
            val t = open(proxyOf(s.port, secret))
            val big = ByteArray(300_000) { (it % 251).toByte() }
            runBlocking { t.send(big); assertArrayEquals(big, t.receive()) }
            t.close()
        }
    }

    @Test fun clientHelloLooksLikeATlsHelloToTheCoverDomain() {
        val secret = MtSecret.FakeTls(KEY16, domain)
        FakeMtProxyServer(secret).use { s ->
            open(proxyOf(s.port, secret)).close()
            assertEquals(517, s.lastHelloLength)
            assertEquals(domain, s.lastSni)
        }
    }

    @Test fun clientHelloRandomIsTheSecretHmacWithTheTimeXoredIn() {
        val now = 1_793_700_000L
        val hello = FakeTls.clientHello(KEY16, domain, now, java.security.SecureRandom())
        val random = hello.copyOfRange(11, 43)
        val zeroed = hello.copyOf().also { java.util.Arrays.fill(it, 11, 43, 0) }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(KEY16, "HmacSHA256")) }.doFinal(zeroed)
        assertArrayEquals(mac.copyOfRange(0, 28), random.copyOfRange(0, 28))
        for (i in 0 until 4) assertEquals((mac[28 + i].toInt() xor (now ushr (8 * i)).toInt()).toByte(), random[28 + i])
        assertEquals(0x16, hello[0].toInt()); assertEquals(0x01, hello[5].toInt())
    }

    @Test fun aWrongSecretIsAHandshakeFailureOnFakeTls() {
        FakeMtProxyServer(MtSecret.FakeTls(KEY16, domain)).use { s ->
            val bad = MtSecret.FakeTls(hexBytes("ffeeddccbbaa99887766554433221100"), domain)
            try { open(proxyOf(s.port, bad)); fail() } catch (e: ProxyException) { assertEquals(ProxyFailure.HANDSHAKE_FAILED, e.reason) }
            assertEquals(1, s.rejected)
        }
    }

    @Test fun aWrongSecretIsAHandshakeFailureOnTheFirstFrame() {
        for (right in listOf(MtSecret.Plain(KEY16), MtSecret.Padded(KEY16))) FakeMtProxyServer(right).use { s ->
            val wrong = if (right is MtSecret.Plain) MtSecret.Plain(hexBytes("ffeeddccbbaa99887766554433221100")) else MtSecret.Padded(hexBytes("ffeeddccbbaa99887766554433221100"))
            val t = open(proxyOf(s.port, wrong))
            try { runBlocking { t.send(ByteArray(8)); t.receive() }; fail() } catch (e: ProxyException) { assertEquals(ProxyFailure.HANDSHAKE_FAILED, e.reason) }
            t.close()
        }
    }

    @Test fun aBadServerDigestIsRejectedByTheClient() {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { c ->
                val din = java.io.DataInputStream(c.getInputStream())
                val head = ByteArray(5); din.readFully(head)
                din.readFully(ByteArray(((head[3].toInt() and 0xff) shl 8) or (head[4].toInt() and 0xff)))
                val sh = ByteArray(127); byteArrayOf(0x16, 3, 3, 0, 0x7a, 2, 0, 0, 0x76, 3, 3).copyInto(sh)
                c.getOutputStream().write(sh + byteArrayOf(0x14, 3, 3, 0, 1, 1) + byteArrayOf(0x17, 3, 3, 0, 4, 1, 2, 3, 4))
                Thread.sleep(200)
            }
        }
        try { open(proxyOf(server.localPort, MtSecret.FakeTls(KEY16, domain))); fail() } catch (e: ProxyException) { assertEquals(ProxyFailure.HANDSHAKE_FAILED, e.reason) }
        server.close()
    }

    @Test fun aNonTlsReplyIsRejected() {
        val server = ServerSocket(0)
        thread(isDaemon = true) { server.accept().use { c -> c.getInputStream().read(ByteArray(600)); c.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()); Thread.sleep(200) } }
        try { open(proxyOf(server.localPort, MtSecret.FakeTls(KEY16, domain))); fail() } catch (e: ProxyException) { assertEquals(ProxyFailure.HANDSHAKE_FAILED, e.reason) }
        server.close()
    }

    @Test fun anUnreachableProxyIsUnreachable() {
        val port = ServerSocket(0).use { it.localPort }
        try { open(proxyOf(port, MtSecret.Plain(KEY16))); fail() } catch (e: ProxyException) { assertEquals(ProxyFailure.UNREACHABLE, e.reason) }
    }

    @Test fun anOldClockIsRejectedByTheProxyForFakeTls() {
        val secret = MtSecret.FakeTls(KEY16, domain)
        FakeMtProxyServer(secret, serverNowSeconds = { System.currentTimeMillis() / 1000 + 10_000 }).use { s ->
            try { open(proxyOf(s.port, secret)); fail() } catch (e: ProxyException) { assertEquals(ProxyFailure.HANDSHAKE_FAILED, e.reason) }
        }
    }
}

class RoutingTransportTest {
    @Test fun directRouteIsUnchanged() = runBlocking {
        FakeDc().use { dc ->
            val t = RoutingTransportFactory { ActiveRoute.Direct }.connect("127.0.0.1", dc.port, 2)
            t.send(byteArrayOf(9, 9, 9, 9)); assertArrayEquals(byteArrayOf(9, 9, 9, 9), t.receive()); t.close()
        }
    }

    @Test fun anActiveProxyNeverFallsBackToDirect() {
        FakeDc().use { dc ->
            val deadPort = ServerSocket(0).use { it.localPort }
            val p = Proxy(1, ProxyKind.SOCKS5, "127.0.0.1", deadPort, null, null, null, null)
            try { runBlocking { RoutingTransportFactory { ActiveRoute.Through(p) }.connect("127.0.0.1", dc.port, 2) }; fail() } catch (_: ProxyException) { }
            assertEquals(0, dc.connections)
        }
    }

    @Test fun theRouteIsReadOnEveryConnect() = runBlocking {
        FakeDc().use { dc -> FakeSocks5Server().use { s ->
            var route: ActiveRoute = ActiveRoute.Direct
            val f = RoutingTransportFactory { route }
            f.connect("127.0.0.1", dc.port, 2).close()
            assertEquals(0, s.connects)
            route = ActiveRoute.Through(Proxy(1, ProxyKind.SOCKS5, "127.0.0.1", s.port, null, null, null, null))
            f.connect("127.0.0.1", dc.port, 2).close()
            assertEquals(1, s.connects)
        } }
    }
}
