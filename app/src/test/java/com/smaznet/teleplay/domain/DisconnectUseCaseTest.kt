package com.smaznet.teleplay.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DisconnectUseCaseTest {
    @Test fun erasesEverythingAndSignsOut() = runTest {
        val e = Env(authorized = 42)
        e.connections.completePairing(42)
        val id = e.videos.ingest(msg(video = vid()))!!
        e.videos.saveProgress(id, 30_000, 60_000)
        val calls = mutableListOf<String>()
        val uc = DisconnectUseCase(
            e.connections, e.videos,
            gatewayWiper = { calls += "gateway" }, // sessions, update state, DC connections
            sessionWiper = { calls += "secrets" },
            cacheWiper = { calls += "cache" },
        )
        uc()
        assertEquals(setOf("gateway", "secrets", "cache"), calls.toSet())
        assertNull(e.tokens.token)
        assertNull(e.connStore.row)
        assertNull(e.connections.connection.value)
        assertEquals(ConnectionState.SIGNED_OUT, e.connections.state.value)
        assertTrue(e.videoStore.all.isEmpty())
        assertTrue(e.progressStore.all.isEmpty())
    }

    @Test fun continuesAfterFailureAndRethrows() = runTest {
        val e = Env(authorized = 42)
        var cache = false
        val uc = DisconnectUseCase(e.connections, e.videos, { error("boom") }, { }, { cache = true })
        try { uc(); fail() } catch (ex: IllegalStateException) { }
        assertTrue(cache)
        assertEquals(ConnectionState.SIGNED_OUT, e.connections.state.value)
        assertNull(e.tokens.token)
    }

    @Test fun remoteIsWipedBeforeTheGatewayWhileTheTokenStillWorks() = runTest {
        val e = Env(authorized = 42)
        val calls = mutableListOf<String>()
        val uc = DisconnectUseCase(
            e.connections, e.videos,
            gatewayWiper = { calls += "gateway:token=${e.tokens.token != null}:conn=${e.connections.connection.value != null}" },
            sessionWiper = { calls += "secrets" }, cacheWiper = { calls += "cache" },
            remoteWiper = { calls += "remote:token=${e.tokens.token != null}:conn=${e.connections.connection.value != null}" },
        )
        uc()
        assertEquals("remote:token=true:conn=true", calls.first())
        assertEquals("gateway:token=true:conn=true", calls[1])
    }
}
