package com.smaznet.teleplay.data

import com.smaznet.teleplay.domain.*
import com.smaznet.teleplay.fakes.InMemoryConnectionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ColdStartTest {
    private fun populated(): Env {
        val e = Env(authorized = 42)
        e.connections.completePairing(42)
        e.videos.ingest(msg(id = 1, date = 10, video = vid(doc = 1, name = "one")))
        e.videos.ingest(msg(id = 2, date = 20, video = vid(doc = 2, name = "two")))
        return e
    }

    @Test fun startsConnectingWithHistoryThenReady() = runTest(UnconfinedTestDispatcher()) {
        val old = populated()
        // "restart": new repository over the same persisted store, token and videos
        val connections = com.smaznet.teleplay.data.repo.ConnectionRepository(old.connStore, old.tokens, old.gateway)
        assertTrue(connections.restore())
        assertEquals(ConnectionState.CONNECTING, connections.state.value)
        assertEquals(42L, connections.connection.value!!.authorizedUserId)
        assertEquals("bot", connections.connection.value!!.botName)
        assertEquals(listOf("two", "one"), old.videos.videos.first().map { it.title })

        val job = launch { connections.observeGateway() }
        old.gateway.state.value = GatewayState.READY
        assertEquals(ConnectionState.READY, connections.state.value)
        old.gateway.state.value = GatewayState.OFFLINE
        assertEquals(ConnectionState.OFFLINE, connections.state.value)
        old.gateway.state.value = GatewayState.READY
        assertEquals(ConnectionState.READY, connections.state.value)
        assertEquals(ConnectionState.READY, old.connStore.row!!.state)
        job.cancel()
    }

    @Test fun withoutTokenStaysSignedOut() {
        val e = populated()
        e.tokens.clear()
        val c = com.smaznet.teleplay.data.repo.ConnectionRepository(e.connStore, e.tokens, e.gateway)
        assertFalse(c.restore())
        assertEquals(ConnectionState.SIGNED_OUT, c.state.value)
    }

    @Test fun unpairedStartsAwaitingPairingWhenGatewayReady() = runTest(UnconfinedTestDispatcher()) {
        val e = Env(authorized = null)
        val c = com.smaznet.teleplay.data.repo.ConnectionRepository(e.connStore, e.tokens, e.gateway)
        c.restore()
        val job = launch { c.observeGateway() }
        e.gateway.state.value = GatewayState.READY
        assertEquals(ConnectionState.AWAITING_PAIRING, c.state.value)
        job.cancel()
    }

    @Test fun connectSavesTokenAndRejectedTokenDoesNot() = runTest {
        val store = InMemoryConnectionStore()
        val tokens = com.smaznet.teleplay.fakes.InMemoryTokenVault()
        val gw = com.smaznet.teleplay.fakes.FakeTelegramGateway()
        val c = com.smaznet.teleplay.data.repo.ConnectionRepository(store, tokens, gw)
        assertTrue(c.connect("bad").isFailure)
        assertEquals(ConnectionState.REJECTED, c.state.value)
        assertNull(tokens.token)
        assertTrue(c.connect("123:valid").isSuccess)
        assertEquals("123:valid", tokens.token)
        assertEquals(ConnectionState.AWAITING_PAIRING, c.state.value)
        assertEquals(ConnectionState.AWAITING_PAIRING, store.row!!.state)
    }
}
