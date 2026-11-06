package com.smaznet.teleplay.domain.proxy

import com.smaznet.teleplay.data.telegram.DcAddress
import com.smaznet.teleplay.data.telegram.DcDirectory
import com.smaznet.teleplay.mtproto.tl.Ids
import com.smaznet.teleplay.mtproto.tl.TlReader
import com.smaznet.teleplay.mtproto.tl.TlWriter
import com.smaznet.teleplay.mtproto.transport.fakes.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket

/** A data center that answers req_pq_multi with a well-formed resPQ echoing the nonce. */
private fun resPqFor(@Suppress("UNUSED_PARAMETER") dc: Int, frame: ByteArray): ByteArray {
    val r = TlReader(frame); r.int64(); r.int64(); r.int32(); r.int32()
    val nonce = r.int128()
    val body = TlWriter().apply {
        int32(Ids.RES_PQ); int128(nonce); int128(ByteArray(16) { 3 }); bytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)); vector(listOf(1L)) { int64(it) }
    }.toByteArray()
    return TlWriter().apply { int64(0); int64(4); int32(body.size); raw(body) }.toByteArray()
}

class ProxyTesterTest {
    private fun tester(dcPort: Int, timeoutMs: Long = 3_000) =
        ProxyTester(DcDirectory(mapOf(2 to DcAddress("127.0.0.1", dcPort))), timeoutMs)

    @Test fun workingSocks5ProxyReportsLatency() = runBlocking {
        FakeDc(::resPqFor).use { dc -> FakeSocks5Server("u", "p").use { s ->
            val p = Proxy(1, ProxyKind.SOCKS5, "127.0.0.1", s.port, null, "u", "p", null)
            val r = tester(dc.port).test(p)
            assertTrue(r.toString(), r is ProxyTestResult.Working)
        } }
    }

    @Test fun workingMtProxyForEverySecretKind() = runBlocking {
        for (secret in listOf(MtSecret.Plain(KEY), MtSecret.Padded(KEY), MtSecret.FakeTls(KEY, "cover.example.org")))
            FakeMtProxyServer(secret, ::resPqFor).use { s ->
                val p = Proxy(1, ProxyKind.MTPROTO, "127.0.0.1", s.port, null, null, null, secret)
                val r = tester(0).test(p)
                assertTrue("$secret: $r", r is ProxyTestResult.Working)
            }
    }

    @Test fun wrongSocksPasswordIsAuthRejected() = runBlocking {
        FakeSocks5Server("u", "p").use { s ->
            val p = Proxy(1, ProxyKind.SOCKS5, "127.0.0.1", s.port, null, "u", "bad", null)
            assertEquals(ProxyTestResult.AuthRejected, tester(1).test(p))
        }
    }

    @Test fun wrongMtSecretIsSecretRejected() = runBlocking {
        for ((right, wrong) in listOf(
            MtSecret.Plain(KEY) to MtSecret.Plain(ByteArray(16) { 9 }),
            MtSecret.FakeTls(KEY, "a.example") to MtSecret.FakeTls(ByteArray(16) { 9 }, "a.example"),
        )) FakeMtProxyServer(right, ::resPqFor).use { s ->
            val p = Proxy(1, ProxyKind.MTPROTO, "127.0.0.1", s.port, null, null, null, wrong)
            assertEquals(ProxyTestResult.SecretRejected, tester(1).test(p))
        }
    }

    @Test fun nothingListeningIsNotReachable() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val p = Proxy(1, ProxyKind.SOCKS5, "127.0.0.1", port, null, null, null, null)
        assertEquals(ProxyTestResult.NotReachable, tester(1).test(p))
    }

    @Test fun aSilentProxyTimesOut() = runBlocking {
        val server = ServerSocket(0)
        val p = Proxy(1, ProxyKind.SOCKS5, "127.0.0.1", server.localPort, null, null, null, null)
        assertEquals(ProxyTestResult.TimedOut, tester(1, timeoutMs = 300).test(p))
        server.close()
    }

    @Test fun aProxyThatDoesNotReachTelegramIsNotWorking() = runBlocking {
        FakeDc { _, _ -> byteArrayOf(1, 2, 3, 4) }.use { dc -> FakeSocks5Server().use { s ->
            val p = Proxy(1, ProxyKind.SOCKS5, "127.0.0.1", s.port, null, null, null, null)
            assertFalse(tester(dc.port).test(p).isWorking)
        } }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyHealthCheckTest {
    private class FakeTester(val results: Map<String, ProxyTestResult>, val delayMs: Long = 0) : ProxyTesting {
        var running = 0; var maxRunning = 0
        override suspend fun test(proxy: Proxy): ProxyTestResult {
            running++; maxRunning = maxOf(maxRunning, running)
            try { if (delayMs > 0) delay(delayMs) } finally { running-- }
            return results[proxy.server] ?: ProxyTestResult.NotReachable
        }
    }

    private fun repoWith(n: Int): Pair<ProxyRepository, List<Proxy>> {
        val r = newRepo()
        val list = (1..n).map { (r.add(socksDraft(server = "10.0.0.$it")) as AddResult.Added).proxy }
        return r to list
    }

    @Test fun mixedResultsAreRecordedAndSummarised() = runTest {
        val (r, ps) = repoWith(5)
        val t = FakeTester(mapOf("10.0.0.1" to ProxyTestResult.Working(10), "10.0.0.3" to ProxyTestResult.Working(30), "10.0.0.2" to ProxyTestResult.AuthRejected))
        val events = ProxyHealthCheck(t, r).run().toList()
        val last = events.last()
        assertTrue(last.done); assertEquals(5, last.total); assertEquals(2, last.working)
        assertEquals(ProxyTestResult.Working(30), r.get(ps[2].id)!!.lastTest)
        assertEquals(ProxyTestResult.AuthRejected, r.get(ps[1].id)!!.lastTest)
        assertEquals(ProxyTestResult.NotReachable, r.get(ps[4].id)!!.lastTest)
        assertEquals("one event per proxy plus the opening one", 6, events.size)
    }

    @Test fun allWorkingAndAllFailing() = runTest {
        val (r, ps) = repoWith(3)
        val ok = ProxyHealthCheck(FakeTester(ps.associate { it.server to ProxyTestResult.Working(1) }), r).run().toList().last()
        assertEquals(3, ok.working)
        val bad = ProxyHealthCheck(FakeTester(emptyMap()), r).run().toList().last()
        assertEquals(0, bad.working); assertTrue(bad.done)
    }

    @Test fun emptyListFinishesImmediately() = runTest {
        val e = ProxyHealthCheck(FakeTester(emptyMap()), newRepo()).run().toList()
        assertEquals(1, e.size); assertTrue(e[0].done)
    }

    @Test fun neverMoreThanFourAtATime() = runTest {
        val (r, _) = repoWith(12)
        val t = FakeTester(emptyMap(), delayMs = 100)
        ProxyHealthCheck(t, r).run().toList()
        assertEquals(4, t.maxRunning)
    }

    @Test fun selectionAndSwitchAreUntouched() = runTest {
        val (r, ps) = repoWith(3)
        r.select(ps[1].id); r.setEnabled(true)
        ProxyHealthCheck(FakeTester(emptyMap()), r).run().toList()
        assertEquals(ProxyState(true, ps[1].id), r.state.value)
    }

    @Test fun aThrowingTesterCountsAsFailed() = runTest {
        val (r, ps) = repoWith(1)
        val boom = ProxyTesting { error("boom") }
        val last = ProxyHealthCheck(boom, r).run().toList().last()
        assertEquals(0, last.working)
        assertEquals(ProxyTestResult.NotReachable, r.get(ps[0].id)!!.lastTest)
    }

    @Test fun cancellingStopsTheRun() = runTest {
        val (r, _) = repoWith(8)
        val t = FakeTester(emptyMap(), delayMs = 10_000)
        val job = async { ProxyHealthCheck(t, r).run().toList() }
        delay(50)
        job.cancel()
        delay(50)
        assertEquals("cancelled tests are not left running", 0, t.running)
    }
}
