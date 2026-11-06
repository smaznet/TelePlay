package com.smaznet.teleplay.setup

import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.domain.TelegramError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket

class SetupServerTest {
    private class Response(val status: Int, val headers: Map<String, String>, val body: String)

    private val goodToken = "123456789:AAH_fake-Token_value_0123456789abcdef"
    private var now = 1_000_000L
    private var session = SetupSession(clock = { now }, validityMs = 600_000)
    private var submit: suspend (String) -> Result<BotInfo> = { Result.success(BotInfo(1, "bot")) }
    private val submitted = mutableListOf<String>()
    private var peerAllowed: (InetAddress) -> Boolean = { true }
    private var server: SetupServer? = null

    private fun start(): Int {
        val s = SetupServer(
            session = session,
            submit = { t -> submitted.add(t); submit(t) },
            addressSource = { "127.0.0.1" },
            clock = { now },
            peerAllowed = { peerAllowed(it) },
            submitTimeoutMs = 2_000,
        )
        server = s
        return s.start()
    }

    @After fun tearDown() { server?.stop() }

    private fun raw(port: Int, request: String, host: String? = "127.0.0.1:$port"): Response? {
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 5_000
            val text = request.replace("{host}", host ?: "")
            sock.getOutputStream().write(text.toByteArray())
            sock.getOutputStream().flush()
            val bytes = sock.getInputStream().readBytes()
            if (bytes.isEmpty()) return null
            val s = bytes.toString(Charsets.ISO_8859_1)
            val head = s.substringBefore("\r\n\r\n")
            val lines = head.split("\r\n")
            val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Response(lines[0].split(' ')[1].toInt(), headers, s.substringAfter("\r\n\r\n", ""))
        }
    }

    private fun get(port: Int, path: String, host: String? = "127.0.0.1:$port") =
        raw(port, "GET $path HTTP/1.1\r\nHost: {host}\r\n\r\n", host)

    private fun post(port: Int, path: String, body: String, host: String? = "127.0.0.1:$port") =
        raw(port, "POST $path HTTP/1.1\r\nHost: {host}\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body", host)

    private fun tokenBody(t: String = goodToken) = "{\"token\":\"$t\"}"

    @Test fun servesPageForValidSecretWithSecurityHeaders() {
        val port = start()
        val r = get(port, "/s/${session.secret}")!!
        assertEquals(200, r.status)
        assertTrue(r.body.contains("<form"))
        assertEquals("no-store", r.headers["cache-control"])
        assertEquals("no-referrer", r.headers["referrer-policy"])
        assertEquals("nosniff", r.headers["x-content-type-options"])
        assertEquals("close", r.headers["connection"])
        assertEquals(SetupState.PHONE_CONNECTED, session.state.value)
        assertFalse("secret not echoed in the page", r.body.contains(session.secret))
    }

    @Test fun acceptedTokenReturns200AndMarksUsed() {
        val port = start()
        val r = post(port, "/s/${session.secret}/token", tokenBody())!!
        assertEquals(200, r.status)
        assertEquals("{\"ok\":true}", r.body)
        assertEquals(SetupState.USED, session.state.value)
        assertEquals(listOf(goodToken), submitted)
    }

    @Test fun rejectedTokenAllowsRetry() {
        var first = true
        submit = {
            if (first) { first = false; Result.failure(TelegramError.TokenRejected()) } else Result.success(BotInfo(1, "bot"))
        }
        val port = start()
        val a = post(port, "/s/${session.secret}/token", tokenBody())!!
        assertEquals(422, a.status)
        assertEquals("{\"ok\":false,\"reason\":\"rejected\"}", a.body)
        assertEquals(4, session.attemptsLeft)
        val b = post(port, "/s/${session.secret}/token", tokenBody())!!
        assertEquals(200, b.status)
        assertEquals(SetupState.USED, session.state.value)
    }

    @Test fun offlineAndRateLimitedReasons() {
        submit = { Result.failure(TelegramError.Offline()) }
        val port = start()
        assertEquals("{\"ok\":false,\"reason\":\"offline\"}", post(port, "/s/${session.secret}/token", tokenBody())!!.body)
        submit = { Result.failure(TelegramError.RateLimited(7)) }
        assertEquals("{\"ok\":false,\"reason\":\"rate_limited\"}", post(port, "/s/${session.secret}/token", tokenBody())!!.body)
        assertEquals(5, session.attemptsLeft)
    }

    @Test fun malformedJsonIs400() {
        val port = start()
        assertEquals(400, post(port, "/s/${session.secret}/token", "not json")!!.status)
        assertEquals(400, post(port, "/s/${session.secret}/token", "{\"nope\":1}")!!.status)
        assertTrue(submitted.isEmpty())
    }

    @Test fun badTokenFormatIs422WithoutCallingSubmit() {
        val port = start()
        val r = post(port, "/s/${session.secret}/token", tokenBody("hello world"))!!
        assertEquals(422, r.status)
        assertEquals("{\"ok\":false,\"reason\":\"format\"}", r.body)
        assertTrue(submitted.isEmpty())
        assertEquals(5, session.attemptsLeft)
    }

    @Test fun oversizeBodyIs413() {
        val port = start()
        val r = raw(port, "POST /s/${session.secret}/token HTTP/1.1\r\nHost: {host}\r\nContent-Length: 5000\r\n\r\n")!!
        assertEquals(413, r.status)
        val big = post(port, "/s/${session.secret}/token", "{\"token\":\"" + "1".repeat(5000) + "\"}")!!
        assertEquals(413, big.status)
    }

    @Test fun unknownPathsAre404WithEmptyBody() {
        val port = start()
        for (p in listOf("/", "/favicon.ico", "/admin", "/s", "/s/${session.secret}/other/deep")) {
            val r = get(port, p)!!
            assertEquals(p, 404, r.status)
            assertEquals(p, "", r.body)
        }
        assertEquals(404, raw(port, "DELETE /s/${session.secret} HTTP/1.1\r\nHost: {host}\r\n\r\n")!!.status)
    }

    @Test fun concurrentSecondPostGets409() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        submit = { gate.await(); Result.success(BotInfo(1, "bot")) }
        val port = start()
        val first = async(Dispatchers.IO) { post(port, "/s/${session.secret}/token", tokenBody())!! }
        while (session.state.value != SetupState.SUBMITTING) withContext(Dispatchers.IO) { Thread.sleep(10) }
        val second = post(port, "/s/${session.secret}/token", tokenBody())!!
        assertEquals(409, second.status)
        assertEquals("{\"ok\":false,\"reason\":\"busy\"}", second.body)
        gate.complete(Unit)
        assertEquals(200, first.await().status)
        // The loser of a race for the same secret now sees it as used.
        assertEquals(410, post(port, "/s/${session.secret}/token", tokenBody())!!.status)
    }

    @Test fun manualEntryInProgressMapsToBusy() {
        submit = { Result.failure(SubmitBusyException()) }
        val port = start()
        val r = post(port, "/s/${session.secret}/token", tokenBody())!!
        assertEquals(409, r.status)
        assertEquals("{\"ok\":false,\"reason\":\"busy\"}", r.body)
        assertEquals(5, session.attemptsLeft)
        assertTrue(session.isUsable)
    }

    @Test fun wrongSecretIs404AndSixthAttemptIs429() {
        val port = start()
        repeat(5) { assertEquals(404, get(port, "/s/wrong$it")!!.status) }
        val blocked = get(port, "/s/${session.secret}")!!
        assertEquals(429, blocked.status)
        assertNotNull(blocked.headers["retry-after"])
        // Even a correct secret is refused while blocked, and the page was not served.
        assertEquals(SetupState.WAITING, session.state.value)
        now += 61_000
        assertEquals(200, get(port, "/s/${session.secret}")!!.status)
    }

    @Test fun usedSecretIs410() {
        val port = start()
        assertEquals(200, post(port, "/s/${session.secret}/token", tokenBody())!!.status)
        assertEquals(410, get(port, "/s/${session.secret}")!!.status)
        assertEquals(410, post(port, "/s/${session.secret}/token", tokenBody())!!.status)
    }

    @Test fun expiredSecretIs410() {
        val port = start()
        now += 600_001
        val page = get(port, "/s/${session.secret}")!!
        assertEquals(410, page.status)
        assertTrue(page.body.contains("expired"))
        assertEquals(410, post(port, "/s/${session.secret}/token", tokenBody())!!.status)
        assertTrue(submitted.isEmpty())
    }

    @Test fun nonLanPeerIsClosedWithoutResponse() {
        peerAllowed = { false }
        val port = start()
        assertEquals(null, get(port, "/s/${session.secret}"))
        assertEquals(SetupState.WAITING, session.state.value)
    }

    @Test fun lanPeerPredicate() {
        fun ok(ip: String) = SetupServer.isLanPeer(InetAddress.getByName(ip))
        for (ip in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.5", "169.254.9.9")) assertTrue(ip, ok(ip))
        for (ip in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "100.64.0.1", "203.0.113.5")) assertFalse(ip, ok(ip))
    }

    @Test fun wrongHostHeaderIs400() {
        val port = start()
        assertEquals(400, get(port, "/s/${session.secret}", host = "evil.example:$port")!!.status)
        assertEquals(400, get(port, "/s/${session.secret}", host = "127.0.0.1:1")!!.status)
        assertEquals(SetupState.WAITING, session.state.value)
    }

    @Test fun stopRefusesNewConnectionsQuickly() {
        val port = start()
        val t0 = System.currentTimeMillis()
        server!!.stop()
        try {
            Socket("127.0.0.1", port).close()
            fail("connection should be refused")
        } catch (_: ConnectException) {
        }
        assertTrue(System.currentTimeMillis() - t0 < 5_000)
    }

    @Test fun rotationKeepsPortAndRetiresOldSecret() {
        val port = start()
        val old = session.secret
        val next = SetupSession(clock = { now }, validityMs = 600_000)
        server!!.rotate(next)
        assertNotEquals(old, next.secret)
        assertEquals(port, server!!.boundPort)
        assertEquals(410, get(port, "/s/$old")!!.status)
        assertEquals(200, get(port, "/s/${next.secret}")!!.status)
    }

    @Test fun parseTokenHandlesEscapesAndRejectsGarbage() {
        assertEquals("a\"b\\c", SetupServer.parseToken("{ \"token\" : \"a\\\"b\\\\c\" }".toByteArray()))
        assertEquals("x", SetupServer.parseToken("{\"other\":\"1\",\"token\":\"x\"}".toByteArray()))
        assertEquals(null, SetupServer.parseToken("{\"token\":\"x\"} trailing".toByteArray()))
        assertEquals(null, SetupServer.parseToken("[]".toByteArray()))
        assertEquals(null, SetupServer.parseToken("{\"token\":5}".toByteArray()))
    }
}
