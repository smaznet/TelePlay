package com.smaznet.teleplay.mtproto

import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.mtproto.session.DcConnection
import com.smaznet.teleplay.mtproto.session.DcSessionData
import com.smaznet.teleplay.mtproto.tl.Ids
import com.smaznet.teleplay.mtproto.session.InboundMessage
import com.smaznet.teleplay.mtproto.session.RpcException
import com.smaznet.teleplay.mtproto.session.Session
import com.smaznet.teleplay.mtproto.tl.TlFunction
import com.smaznet.teleplay.mtproto.tl.TlReader
import com.smaznet.teleplay.mtproto.tl.TlWriter
import com.smaznet.teleplay.mtproto.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

private class FakeTransport : Transport {
    val toServer = Channel<ByteArray>(Channel.UNLIMITED)
    val toClient = Channel<ByteArray>(Channel.UNLIMITED)
    override suspend fun send(frame: ByteArray) { toServer.send(frame) }
    override suspend fun receive(): ByteArray = toClient.receive()
    override fun close() { toClient.close() }
}

private class Echo(val v: Int) : TlFunction<Int> {
    override fun serialize(w: TlWriter) { w.int32(0x11111111); w.int32(v) }
    override fun readResult(r: TlReader): Int = r.int32()
}

private fun rpcResult(reqMsgId: Long, value: Int): ByteArray = TlWriter().apply {
    int32(Ids.RPC_RESULT); int64(reqMsgId); int32(value)
}.toByteArray()

private fun rpcError(reqMsgId: Long, code: Int, text: String): ByteArray = TlWriter().apply {
    int32(Ids.RPC_RESULT); int64(reqMsgId); int32(Ids.RPC_ERROR); int32(code); string(text)
}.toByteArray()

private fun gzip(body: ByteArray): ByteArray {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(body) }
    return TlWriter().apply { int32(Ids.GZIP_PACKED); bytes(bos.toByteArray()) }.toByteArray()
}

private fun container(vararg items: Pair<Long, ByteArray>): ByteArray = TlWriter().apply {
    int32(Ids.MSG_CONTAINER); int32(items.size)
    items.forEachIndexed { i, (id, body) -> int64(id); int32(2 * i + 1); int32(body.size); raw(body) }
}.toByteArray()

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    private val authKey = ByteArray(256) { (it * 5 + 1).toByte() }
    private val saved = DcSessionData(2, "h", 443, authKey, 0x1234L, 42L, 0)

    private class Harness(val transport: FakeTransport, val conn: DcConnection, val received: MutableList<InboundMessage>, val pushed: IntArray)

    private fun TestScope.harness(
        handler: suspend (InboundMessage, Int) -> List<ByteArray>,
    ): Harness {
        val t = FakeTransport()
        val received = mutableListOf<InboundMessage>()
        val pushed = IntArray(1)
        val server = Session(authKey, saved.authKeyId, saved.serverSalt, 0, isServer = true)
        backgroundScope.launch {
            var n = 0
            while (true) {
                val msg = server.decrypt(t.toServer.receive())
                received += msg
                val id = TlReader(msg.body).peekInt32()
                if (id == Ids.MSGS_ACK || id == Ids.PING) continue
                for (b in handler(msg, n++)) t.toClient.send(server.encrypt(b, true).second)
            }
        }
        val conn = DcConnection(
            dcId = 2, host = "h", port = 443, factory = { _, _, _ -> t }, scope = backgroundScope as CoroutineScope,
            saved = saved, onUpdatesPushed = { pushed[0]++ },
        )
        return Harness(t, conn, received, pushed)
    }

    @Test
    fun call_returnsRpcResultBody() = runTest {
        val h = harness { m, _ -> listOf(rpcResult(m.msgId, 77)) }
        val bytes = h.conn.call(Echo(1))
        assertEquals(77, TlReader(bytes).int32())
    }

    @Test
    fun call_mapsRpcErrorToException() = runTest {
        val h = harness { m, _ -> listOf(rpcError(m.msgId, 420, "FLOOD_WAIT_3")) }
        val e = runCatching { h.conn.call(Echo(1)) }.exceptionOrNull() as RpcException
        assertEquals("FLOOD_WAIT_3", e.rpcMessage)
        assertEquals(420, e.code)
    }

    @Test
    fun badServerSalt_resendsTheRequest() = runTest {
        val h = harness { m, n ->
            if (n == 0) listOf(TlWriter().apply {
                int32(Ids.BAD_SERVER_SALT); int64(m.msgId); int32(1); int32(48); int64(999L)
            }.toByteArray())
            else listOf(rpcResult(m.msgId, 5))
        }
        assertEquals(5, TlReader(h.conn.call(Echo(1))).int32())
        val echoes = h.received.filter { TlReader(it.body).peekInt32() == 0x11111111 }
        assertEquals(2, echoes.size)
        assertTrue(echoes[1].msgId > echoes[0].msgId)
    }

    @Test
    fun badMsgNotification_timeIssueResends() = runTest {
        val h = harness { m, n ->
            if (n == 0) listOf(TlWriter().apply {
                int32(Ids.BAD_MSG_NOTIFICATION); int64(m.msgId); int32(1); int32(17)
            }.toByteArray())
            else listOf(rpcResult(m.msgId, 6))
        }
        assertEquals(6, TlReader(h.conn.call(Echo(1))).int32())
    }

    @Test
    fun container_deliversEveryResult_andGzipIsUnwrapped() = runTest {
        val h = harness { m, _ ->
            listOf(container(1001L to gzip(rpcResult(m.msgId, 9)), 1002L to TlWriter().apply { int32(Ids.PONG); int64(1); int64(2) }.toByteArray()))
        }
        assertEquals(9, TlReader(h.conn.call(Echo(1))).int32())
    }

    @Test
    fun contentRelatedServerMessages_areAcked_andUpdatesPushNotifies() = runTest {
        val h = harness { m, _ -> listOf(TlWriter().apply { int32(0x74ae4240); int32(0) }.toByteArray(), rpcResult(m.msgId, 1)) }
        h.conn.call(Echo(1))
        testScheduler.advanceUntilIdle()
        assertTrue("updates push callback fired", h.pushed[0] >= 1)
        assertTrue("an ack was sent", h.received.any { TlReader(it.body).peekInt32() == Ids.MSGS_ACK })
    }

    @Test
    fun concurrentRequests_getTheirOwnResults() = runTest {
        val h = harness { m, _ ->
            val v = TlReader(m.body).also { it.int32() }.int32()
            listOf(rpcResult(m.msgId, v * 10))
        }
        val results = (1..5).map { i -> async { TlReader(h.conn.call(Echo(i))).int32() } }
        assertEquals(listOf(10, 20, 30, 40, 50), results.map { it.await() })
    }

    @Test
    fun tamperedServerPacket_isRejected() {
        val server = Session(authKey, saved.authKeyId, 1L, 0, isServer = true)
        val client = Session(authKey, saved.authKeyId, 1L, 0)
        // Teach the server side the client's session id, then send the other way with the same ids.
        server.decrypt(client.encrypt(ByteArray(8), true).second)
        val packet = server.encrypt(ByteArray(16) { it.toByte() }, true).second
        assertArrayEquals(ByteArray(16) { it.toByte() }, client.decrypt(packet).body)
        val bad = packet.clone().also { it[packet.size - 3] = (it[packet.size - 3].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { client.decrypt(bad) }
    }

    // --- Rpc layer --------------------------------------------------------------------------

    private fun TestScope.rpcWith(vararg conns: Harness, sleeps: MutableList<Long>): Rpc =
        Rpc(
            connectionFor = { dc -> conns[if (dc == 2) 0 else 1].conn },
            homeDc = 2,
            client = ClientInfo(apiId = 1),
            sleep = { sleeps += it },
        )

    @Test
    fun rpc_wrapsFirstCallOnly() = runTest {
        val h = harness { m, _ -> listOf(rpcResult(m.msgId, 3)) }
        val rpc = rpcWith(h, sleeps = mutableListOf())
        assertEquals(3, rpc.invoke(Echo(1)))
        assertEquals(3, rpc.invoke(Echo(2)))
        val calls = h.received.filter { m -> TlReader(m.body).peekInt32().let { it != Ids.MSGS_ACK && it != Ids.PING } }
        assertEquals(Integer.toHexString(0xda9b0d0d.toInt()), Integer.toHexString(TlReader(calls[0].body).peekInt32()))
        assertEquals(0x11111111, TlReader(calls[1].body).peekInt32())
    }

    @Test
    fun rpc_floodWaitWaitsThenRetries() = runTest {
        val sleeps = mutableListOf<Long>()
        val h = harness { m, n -> if (n == 0) listOf(rpcError(m.msgId, 420, "FLOOD_WAIT_2")) else listOf(rpcResult(m.msgId, 8)) }
        assertEquals(8, rpcWith(h, sleeps = sleeps).invoke(Echo(1)))
        assertEquals(listOf(2000L), sleeps)
    }

    @Test
    fun rpc_floodWaitGivesUpAsRateLimited() = runTest {
        val h = harness { m, _ -> listOf(rpcError(m.msgId, 420, "FLOOD_WAIT_7")) }
        val e = runCatching { rpcWith(h, sleeps = mutableListOf()).invoke(Echo(1)) }.exceptionOrNull()
        assertTrue(e is TelegramError.RateLimited && e.retryAfterSec == 7)
    }

    @Test
    fun rpc_migrateErrorSwitchesDc() = runTest {
        val h1 = harness { m, _ -> listOf(rpcError(m.msgId, 303, "FILE_MIGRATE_4")) }
        val h2 = harness { m, _ -> listOf(rpcResult(m.msgId, 21)) }
        val rpc = rpcWith(h1, h2, sleeps = mutableListOf())
        assertEquals(21, rpc.invoke(Echo(1)))
        assertEquals("FILE migrate must not change the home DC", 2, rpc.homeDc)
    }

    @Test
    fun rpc_userMigrateChangesHomeDc() = runTest {
        val h1 = harness { m, _ -> listOf(rpcError(m.msgId, 303, "USER_MIGRATE_4")) }
        val h2 = harness { m, _ -> listOf(rpcResult(m.msgId, 21)) }
        val rpc = rpcWith(h1, h2, sleeps = mutableListOf())
        assertEquals(21, rpc.invoke(Echo(1)))
        assertEquals(4, rpc.homeDc)
    }

    @Test
    fun rpc_errorMapping() = runTest {
        val cases = mapOf(
            "ACCESS_TOKEN_INVALID" to TelegramError.TokenRejected::class,
            "AUTH_KEY_UNREGISTERED" to TelegramError.AuthKeyLost::class,
            "FILE_REFERENCE_EXPIRED" to TelegramError.FileReferenceExpired::class,
            "SOMETHING_ELSE" to TelegramError.Unknown::class,
        )
        for ((text, cls) in cases) {
            val h = harness { m, _ -> listOf(rpcError(m.msgId, 400, text)) }
            val e = runCatching { rpcWith(h, sleeps = mutableListOf()).invoke(Echo(1)) }.exceptionOrNull()
            assertEquals(text, cls, e!!::class)
        }
    }
}
