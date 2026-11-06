package com.smaznet.teleplay.setup

import com.smaznet.teleplay.domain.BotInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException
import java.net.Socket

class SetupHostTest {
    private class FakeAddresses(initial: String?) : LocalAddressProvider {
        val flow = MutableStateFlow(initial)
        override fun current() = flow.value
        override fun changes(): Flow<String?> = flow
    }

    private val goodToken = "123456789:AAH_fake-Token_value_0123456789abcdef"
    private val hosts = mutableListOf<SetupHost>()
    private var serversCreated = 0

    @After fun tearDown() { hosts.forEach { it.stop() } }

    private fun host(
        addresses: LocalAddressProvider,
        validityMs: Long = 60_000,
        usedHoldMs: Long = 100,
        submit: suspend (String) -> Result<BotInfo> = { Result.success(BotInfo(1, "bot")) },
    ): SetupHost {
        val h = SetupHost(
            submit = submit,
            addresses = addresses,
            validityMs = validityMs,
            usedHoldMs = usedHoldMs,
            refreshNoticeMs = 50,
            serverFactory = { session, sub, source -> serversCreated++; SetupServer(session, sub, source) },
        )
        hosts.add(h)
        return h
    }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun portOf(h: SetupHost) = h.uiState.value.shortAddress!!.substringAfter(':').toInt()

    private fun http(port: Int, address: String, method: String, path: String, body: String = ""): Int =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            val extra = if (method == "POST") "Content-Length: ${body.length}\r\n" else ""
            s.getOutputStream().write("$method $path HTTP/1.1\r\nHost: $address:$port\r\n$extra\r\n$body".toByteArray())
            val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            text.substringBefore("\r\n").split(' ')[1].toInt()
        }

    private fun pathOf(h: SetupHost) = "/s/" + h.uiState.value.secret!!

    @Test fun noAddressMeansNoServerAndNoNetworkStatus() {
        val h = host(FakeAddresses(null))
        h.start()
        waitFor("no network status") { h.uiState.value.status == SetupStatus.NO_NETWORK }
        assertNull(h.uiState.value.url)
        assertEquals(0, serversCreated)
    }

    @Test fun serverStartsWhenAnAddressAppears() {
        val addr = FakeAddresses(null)
        val h = host(addr)
        h.start()
        waitFor("no network") { h.uiState.value.status == SetupStatus.NO_NETWORK }
        addr.flow.value = "127.0.0.1"
        waitFor("waiting") { h.uiState.value.status == SetupStatus.WAITING }
        val ui = h.uiState.value
        assertEquals("http://127.0.0.1:${portOf(h)}/s/${ui.secret}", ui.url)
        assertEquals(200, http(portOf(h), "127.0.0.1", "GET", pathOf(h)))
    }

    @Test fun addressChangeRestartsListenerAndRedrawsUrl() {
        val addr = FakeAddresses("127.0.0.1")
        val h = host(addr)
        h.start()
        waitFor("waiting") { h.uiState.value.status == SetupStatus.WAITING }
        val oldUrl = h.uiState.value.url
        val oldPort = portOf(h)
        addr.flow.value = "127.0.0.2"
        waitFor("new url") { h.uiState.value.url != oldUrl && h.uiState.value.url?.contains("127.0.0.2") == true }
        try {
            Socket("127.0.0.1", oldPort).close()
            fail("old listener should be closed")
        } catch (_: ConnectException) {
        }
        assertEquals(200, http(portOf(h), "127.0.0.2", "GET", pathOf(h)))
    }

    @Test fun statusSequenceOnSuccessfulFlow() {
        val h = host(FakeAddresses("127.0.0.1"), usedHoldMs = 60_000)
        h.start()
        waitFor("waiting") { h.uiState.value.status == SetupStatus.WAITING }
        val seen = mutableListOf<SetupStatus>()
        val t = Thread { while (!Thread.currentThread().isInterrupted) { val s = h.uiState.value.status; if (seen.lastOrNull() != s) seen.add(s); Thread.sleep(2) } }
        t.start()
        val port = portOf(h)
        val path = pathOf(h)
        assertEquals(200, http(port, "127.0.0.1", "GET", path))
        waitFor("phone connected") { h.uiState.value.status == SetupStatus.PHONE_CONNECTED }
        assertEquals(200, http(port, "127.0.0.1", "POST", "$path/token", "{\"token\":\"$goodToken\"}"))
        waitFor("token received") { h.uiState.value.status == SetupStatus.TOKEN_RECEIVED }
        Thread.sleep(50) // let the sampler record the final state
        t.interrupt()
        t.join()
        assertTrue("sequence was $seen", seen.indexOf(SetupStatus.WAITING) < seen.indexOf(SetupStatus.PHONE_CONNECTED))
        assertTrue("sequence was $seen", seen.indexOf(SetupStatus.PHONE_CONNECTED) < seen.indexOf(SetupStatus.TOKEN_RECEIVED))
    }

    @Test fun usedSecretRotatesOnTheSamePort() {
        val h = host(FakeAddresses("127.0.0.1"), usedHoldMs = 100)
        h.start()
        waitFor("waiting") { h.uiState.value.status == SetupStatus.WAITING }
        val port = portOf(h)
        val first = h.uiState.value.secret
        val path = pathOf(h)
        assertEquals(200, http(port, "127.0.0.1", "POST", "$path/token", "{\"token\":\"$goodToken\"}"))
        waitFor("rotation") { h.uiState.value.secret != first && h.uiState.value.status != SetupStatus.TOKEN_RECEIVED }
        assertEquals(port, portOf(h))
        assertEquals(410, http(port, "127.0.0.1", "GET", path))
        assertEquals(200, http(port, "127.0.0.1", "GET", pathOf(h)))
    }

    @Test fun expiryRotatesWithRefreshNotice() {
        val h = host(FakeAddresses("127.0.0.1"), validityMs = 300)
        h.start()
        waitFor("waiting") { h.uiState.value.status == SetupStatus.WAITING }
        val port = portOf(h)
        val first = h.uiState.value.secret
        waitFor("rotated") { h.uiState.value.secret != first }
        assertEquals(port, portOf(h))
        assertNotNull(h.uiState.value.url)
    }

    @Test fun successiveHostsNeverShareSecretOrPort() {
        val a = host(FakeAddresses("127.0.0.1"))
        a.start()
        waitFor("a") { a.uiState.value.status == SetupStatus.WAITING }
        val secretA = a.uiState.value.secret
        val portA = portOf(a)
        a.stop()
        val b = host(FakeAddresses("127.0.0.1"))
        b.start()
        waitFor("b") { b.uiState.value.status == SetupStatus.WAITING }
        assertNotEquals(secretA, b.uiState.value.secret)
        try {
            Socket("127.0.0.1", portA).close()
            // Port may have been reused by b's ephemeral bind; the old secret must not work on it either.
            assertEquals(404, http(portA, "127.0.0.1", "GET", "/s/$secretA"))
        } catch (_: ConnectException) {
        }
    }

    @Test fun stopClosesListenerAndIsIdempotent() {
        val h = host(FakeAddresses("127.0.0.1"))
        h.start()
        h.start()
        waitFor("waiting") { h.uiState.value.status == SetupStatus.WAITING }
        val port = portOf(h)
        h.stop()
        h.stop()
        waitFor("closed") {
            try { Socket("127.0.0.1", port).close(); false } catch (_: ConnectException) { true }
        }
        assertEquals(SetupStatus.IDLE, h.uiState.value.status)
        assertEquals(1, serversCreated)
    }

    @Test fun rejectedSubmissionShowsRejectedAndAllowsRetry() = runBlocking {
        var first = true
        val h = host(FakeAddresses("127.0.0.1"), submit = {
            if (first) { first = false; Result.failure(com.smaznet.teleplay.domain.TelegramError.TokenRejected()) } else Result.success(BotInfo(1, "bot"))
        })
        h.start()
        waitFor("waiting") { h.uiState.value.status == SetupStatus.WAITING }
        val port = portOf(h)
        val path = pathOf(h)
        assertEquals(422, http(port, "127.0.0.1", "POST", "$path/token", "{\"token\":\"$goodToken\"}"))
        waitFor("rejected") { h.uiState.value.status == SetupStatus.REJECTED }
        assertEquals(SetupError.REJECTED, h.uiState.value.error)
        assertEquals(200, http(port, "127.0.0.1", "POST", "$path/token", "{\"token\":\"$goodToken\"}"))
    }
}
