package com.smaznet.teleplay.mtproto.transport

import com.smaznet.teleplay.domain.proxy.ActiveRoute
import com.smaznet.teleplay.domain.proxy.MtSecret
import com.smaznet.teleplay.domain.proxy.Proxy
import com.smaznet.teleplay.domain.proxy.ProxyKind
import com.smaznet.teleplay.mtproto.transport.fakes.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Video streaming is many large frames, sent back to back and from several coroutines: every route must carry them intact. */
class ProxyStressTest {
    private fun frames(n: Int, seed: Long): List<ByteArray> {
        val r = java.util.Random(seed)
        return List(n) { ByteArray(4 * (1 + r.nextInt(300_000))).also { b -> r.nextBytes(b) } } // up to 1.2 MB
    }

    private fun run(connect: suspend () -> Transport) = runBlocking {
        val t = connect()
        val list = frames(12, 5)
        val sender = async { list.forEachIndexed { _, f -> t.send(f) } }
        val got = list.map { t.receive() }
        sender.await()
        list.forEachIndexed { i, f -> assertArrayEquals("frame $i", f, got[i]) }
        // and concurrent senders
        val senders = (0 until 4).map { k -> async { frames(5, 100L + k).forEach { t.send(it) } } }
        val all = HashMap<Int, Int>()
        repeat(20) { all.merge(t.receive().contentHashCode(), 1, Int::plus) }
        senders.awaitAll()
        val expected = (0 until 4).flatMap { k -> frames(5, 100L + k) }.groupingBy { it.contentHashCode() }.eachCount()
        assertEquals(expected, all)
        t.close()
    }

    @Test fun direct() { FakeDc().use { dc -> run { TcpTransport.connect("127.0.0.1", dc.port) } } }

    @Test fun socks5() {
        FakeDc().use { dc -> FakeSocks5Server().use { s ->
            val p = Proxy(1, ProxyKind.SOCKS5, "127.0.0.1", s.port, null, null, null, null)
            run { RoutingTransportFactory { ActiveRoute.Through(p) }.connect("127.0.0.1", dc.port, 4) }
        } }
    }

    @Test fun mtproto() {
        for (secret in listOf(MtSecret.Plain(KEY16), MtSecret.Padded(KEY16), MtSecret.FakeTls(KEY16, "cover.example.org")))
            FakeMtProxyServer(secret).use { s ->
                val p = Proxy(1, ProxyKind.MTPROTO, "127.0.0.1", s.port, null, null, null, secret)
                run { RoutingTransportFactory { ActiveRoute.Through(p) }.connect("x", 0, 4) }
            }
    }
}
