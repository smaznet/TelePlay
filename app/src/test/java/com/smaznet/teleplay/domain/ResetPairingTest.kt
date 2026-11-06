package com.smaznet.teleplay.domain

import org.junit.Assert.*
import org.junit.Test

class ResetPairingTest {
    @Test fun resetClearsUserDataAndIssuesNewCode() {
        val e = Env(authorized = 42)
        e.connections.completePairing(42)
        val id = e.videos.ingest(msg(text = "a", video = vid()))!!
        e.videos.saveProgress(id, 30_000, 60_000)
        var cursorReset = false
        val pairing = PairingService(e.connections, e.videos, { e.time }, updateStateResetter = { cursorReset = true })

        val c = pairing.reset()

        assertNull(e.connections.connection.value!!.authorizedUserId)
        assertEquals(ConnectionState.AWAITING_PAIRING, e.connections.state.value)
        assertTrue(e.videoStore.all.isEmpty())
        assertTrue(e.progressStore.all.isEmpty())
        assertTrue(cursorReset)
        assertEquals(8, c.code.length)
        assertNotNull(e.connStore.row!!.pairingCodeHash)
        // old user can no longer add videos; a different person can pair
        assertNull(e.videos.ingest(msg(id = 2, text = "b", video = vid(doc = 101))))
        assertEquals(PairingResult.Paired(99), pairing.onMessage(msg(sender = 99, text = c.code)))
        assertNull(e.videos.ingest(msg(id = 3, sender = 42, video = vid(doc = 102))))
    }

    @Test fun remoteEndsBeforeTheUserIsForgottenAndTheAccessHashIsCleared() {
        val e = Env(authorized = 42)
        e.connections.completePairing(42)
        e.connections.saveAuthorizedAccessHash(555)
        assertEquals(555L, e.connections.connection.value!!.authorizedUserAccessHash)
        var seenUser: Long? = null
        val pairing = PairingService(e.connections, e.videos, { e.time }, beforeReset = {
            seenUser = e.connections.connection.value?.authorizedUserId
        })
        pairing.reset()
        assertEquals(42L, seenUser)
        assertNull(e.connections.connection.value!!.authorizedUserAccessHash)
        assertNull(e.connStore.row!!.authorizedUserAccessHash)
    }

    @Test fun accessHashIsKeptForTheSameUserAndDroppedForANewOne() {
        val e = Env(authorized = 42)
        e.connections.completePairing(42)
        e.connections.saveAuthorizedAccessHash(555)
        e.connections.completePairing(42)
        assertEquals(555L, e.connections.connection.value!!.authorizedUserAccessHash)
        e.connections.completePairing(7)
        assertNull(e.connections.connection.value!!.authorizedUserAccessHash)
    }
}
