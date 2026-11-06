package com.smaznet.teleplay.setup

import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.domain.proxy.MtSecret
import com.smaznet.teleplay.domain.proxy.ProxyDraft
import com.smaznet.teleplay.domain.proxy.ProxyKind
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

/** The phone page's proxy route (contracts/setup-http.md): same protections as the token route, but the session survives. */
class SetupServerProxyTest {
    private class Response(val status: Int, val body: String)

    private val goodToken = "123456789:AAH_fake-Token_value_0123456789abcdef"
    private var now = 1_000_000L
    private val session = SetupSession(clock = { now }, validityMs = 600_000)
    private val proxies = mutableListOf<ProxyDraft>()
    private var result: ProxySubmitResult = ProxySubmitResult.Added("1.2.3.4:1080")
    private var peerAllowed: (InetAddress) -> Boolean = { true }
    private var server: SetupServer? = null
    private val hex16 = "0102030405060708090a0b0c0d0e0f10"

    private fun start(): Int {
        val s = SetupServer(session, { Result.success(BotInfo(1, "bot")) }, { "127.0.0.1" }, clock = { now }, peerAllowed = { peerAllowed(it) })
        s.onProxy = { d -> proxies.add(d); result }
        server = s
        return s.start()
    }

    @After fun tearDown() { server?.stop() }

    private fun post(port: Int, path: String, body: String): Response {
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 5_000
            val req = "POST $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
            sock.getOutputStream().write(req.toByteArray()); sock.getOutputStream().flush()
            val s = sock.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            return Response(s.substringBefore("\r\n").split(' ')[1].toInt(), s.substringAfter("\r\n\r\n", ""))
        }
    }

    private fun proxyPath() = "/s/${session.secret}/proxy"

    @Test fun aPastedLinkIsSaved() {
        val port = start()
        val r = post(port, proxyPath(), "{\"link\":\"https://t.me/socks?server=1.2.3.4&port=1080&user=bob&pass=pw\"}")
        assertEquals(200, r.status)
        assertEquals("{\"ok\":true,\"duplicate\":false}", r.body)
        val d = proxies.single()
        assertEquals(ProxyKind.SOCKS5, d.kind); assertEquals("bob", d.username); assertEquals("pw", d.password)
    }

    @Test fun aLinkInsideOtherTextIsFound() {
        val port = start()
        val r = post(port, proxyPath(), "{\"link\":\"use this: tg://proxy?server=a.b&port=443&secret=$hex16 thanks\"}")
        assertEquals(200, r.status)
        assertTrue(proxies.single().secret is MtSecret.Plain)
    }

    @Test fun fieldFormsForBothKinds() {
        val port = start()
        assertEquals(200, post(port, proxyPath(), "{\"kind\":\"socks5\",\"server\":\"1.2.3.4\",\"port\":1080,\"user\":\"u\",\"pass\":\"p\"}").status)
        assertEquals(200, post(port, proxyPath(), "{\"kind\":\"mtproto\",\"server\":\"mt.example.org\",\"port\":\"443\",\"secret\":\"dd$hex16\"}").status)
        assertEquals(2, proxies.size)
        assertTrue(proxies[1].secret is MtSecret.Padded)
        assertEquals(1080, proxies[0].port)
    }

    @Test fun invalidInputIs422FormatAndSavesNothing() {
        val port = start()
        for (body in listOf(
            "{\"link\":\"https://example.com\"}", "{\"link\":\"tg://proxy?server=a.b&port=1\"}", "{\"kind\":\"socks5\",\"server\":\"\",\"port\":1}",
            "{\"kind\":\"socks5\",\"server\":\"a.b\",\"port\":70000}", "{\"kind\":\"mtproto\",\"server\":\"a.b\",\"port\":1,\"secret\":\"nope\"}",
            "{\"kind\":\"http\",\"server\":\"a.b\",\"port\":1}",
        )) {
            val r = post(port, proxyPath(), body)
            assertEquals(body, 422, r.status)
            assertEquals("{\"ok\":false,\"reason\":\"format\"}", r.body)
        }
        assertTrue(proxies.isEmpty())
    }

    @Test fun malformedJsonIs422() {
        val port = start()
        assertEquals(422, post(port, proxyPath(), "not json").status)
        assertTrue(proxies.isEmpty())
    }

    @Test fun limitAndFailureAreReported() {
        val port = start()
        result = ProxySubmitResult.LimitReached
        assertEquals("{\"ok\":false,\"reason\":\"limit\"}", post(port, proxyPath(), "{\"link\":\"tg://socks?server=1.1.1.1&port=1\"}").body)
        result = ProxySubmitResult.Failed
        assertEquals("{\"ok\":false,\"reason\":\"error\"}", post(port, proxyPath(), "{\"link\":\"tg://socks?server=1.1.1.1&port=1\"}").body)
    }

    @Test fun duplicateIsASuccessThatSaysSo() {
        val port = start()
        result = ProxySubmitResult.Added("x", duplicate = true)
        assertEquals("{\"ok\":true,\"duplicate\":true}", post(port, proxyPath(), "{\"link\":\"tg://socks?server=1.1.1.1&port=1\"}").body)
    }

    @Test fun theResponseNeverEchoesProxyDetails() {
        val port = start()
        result = ProxySubmitResult.Added("secret-name.example:1080")
        val r = post(port, proxyPath(), "{\"link\":\"https://t.me/socks?server=secret-name.example&port=1080&user=bob&pass=hunter2\"}")
        for (leak in listOf("secret-name", "hunter2", "bob", "1080")) assertFalse(leak, r.body.contains(leak))
    }

    @Test fun aProxyDoesNotUseUpTheSessionAndTheTokenStillWorks() {
        val port = start()
        assertEquals(200, post(port, proxyPath(), "{\"link\":\"tg://socks?server=1.1.1.1&port=1\"}").status)
        assertTrue(session.isUsable)
        assertEquals(5, session.attemptsLeft)
        assertEquals(200, post(port, proxyPath(), "{\"link\":\"tg://socks?server=2.2.2.2&port=1\"}").status)
        assertEquals(200, post(port, "/s/${session.secret}/token", "{\"token\":\"$goodToken\"}").status)
        assertEquals(SetupState.USED, session.state.value)
    }

    @Test fun aWrongSecretIs404AndCountsTowardsTheThrottle() {
        val port = start()
        repeat(5) { assertEquals(404, post(port, "/s/wrong-secret-$it/proxy", "{\"link\":\"tg://socks?server=1.1.1.1&port=1\"}").status) }
        assertEquals(429, post(port, proxyPath(), "{\"link\":\"tg://socks?server=1.1.1.1&port=1\"}").status)
        assertTrue(proxies.isEmpty())
    }

    @Test fun anExpiredOrUsedSessionIs410() {
        val port = start()
        now += 700_000
        assertEquals(410, post(port, proxyPath(), "{\"link\":\"tg://socks?server=1.1.1.1&port=1\"}").status)
        assertTrue(proxies.isEmpty())
    }

    @Test fun nonLanPeersAreDroppedSilently() {
        peerAllowed = { false }
        val port = start()
        val s = Socket("127.0.0.1", port)
        s.soTimeout = 3_000
        s.getOutputStream().write("POST ${proxyPath()} HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
        assertEquals(0, s.getInputStream().readBytes().size)
        s.close()
        assertTrue(proxies.isEmpty())
    }

    @Test fun bodyOver4KbIs413() {
        val port = start()
        val r = post(port, proxyPath(), "{\"link\":\"" + "x".repeat(5000) + "\"}")
        assertEquals(413, r.status)
        assertTrue(proxies.isEmpty())
    }

    @Test fun serverWithoutAProxyHandlerRefusesPolitely() {
        val s = SetupServer(session, { Result.success(BotInfo(1, "bot")) }, { "127.0.0.1" }, clock = { now })
        server = s
        val port = s.start()
        assertEquals("{\"ok\":false,\"reason\":\"error\"}", post(port, proxyPath(), "{\"link\":\"tg://socks?server=1.1.1.1&port=1\"}").body)
    }
}
