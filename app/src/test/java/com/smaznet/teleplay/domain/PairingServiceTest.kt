package com.smaznet.teleplay.domain

import org.junit.Assert.*
import org.junit.Test

class PairingServiceTest {
    @Test fun codeIs8CharsFromAlphabet() {
        val e = Env()
        repeat(50) {
            val c = e.pairing.startPairing()
            assertEquals(8, c.code.length)
            assertTrue(c.code.all { it in PairingService.ALPHABET })
        }
    }

    @Test fun onlyHashIsPersisted() {
        val e = Env()
        val c = e.pairing.startPairing()
        val stored = e.connStore.row!!
        assertNotNull(stored.pairingCodeHash)
        assertFalse(stored.pairingCodeHash!!.contains(c.code))
        assertEquals(e.time + 15 * 60 * 1000, stored.pairingExpiresAt)
    }

    @Test fun correctCodePairsAndInvalidates() {
        val e = Env()
        val c = e.pairing.startPairing()
        assertEquals(PairingResult.Paired(42), e.pairing.onMessage(msg(text = c.code)))
        assertEquals(42L, e.connections.connection.value!!.authorizedUserId)
        assertEquals(ConnectionState.READY, e.connections.state.value)
        assertNull(e.connStore.row!!.pairingCodeHash)
        assertEquals(PairingResult.Ignored, e.pairing.onMessage(msg(sender = 43, text = c.code)))
        assertEquals(42L, e.connections.connection.value!!.authorizedUserId)
    }

    @Test fun startCommandAccepted() {
        val e = Env()
        val c = e.pairing.startPairing()
        assertEquals(PairingResult.Paired(42), e.pairing.onMessage(msg(text = "/start ${c.code}")))
    }

    @Test fun wrongCodeIgnored() {
        val e = Env()
        val c = e.pairing.startPairing()
        val wrong = (if (c.code[0] == 'A') "B" else "A") + c.code.substring(1)
        assertEquals(PairingResult.Ignored, e.pairing.onMessage(msg(text = wrong)))
        assertEquals(PairingResult.Ignored, e.pairing.onMessage(msg(text = null)))
        assertEquals(PairingResult.Ignored, e.pairing.onMessage(msg(text = "/start")))
        assertNull(e.connections.connection.value!!.authorizedUserId)
        assertEquals(PairingResult.Paired(42), e.pairing.onMessage(msg(text = c.code)))
    }

    @Test fun expiredCodeIgnored() {
        val e = Env()
        val c = e.pairing.startPairing()
        e.time += 15 * 60 * 1000
        assertEquals(PairingResult.Ignored, e.pairing.onMessage(msg(text = c.code)))
        assertNull(e.connections.connection.value!!.authorizedUserId)
        assertNull(e.connStore.row!!.pairingCodeHash)
    }

    @Test fun justBeforeExpiryAccepted() {
        val e = Env()
        val c = e.pairing.startPairing()
        e.time += 15 * 60 * 1000 - 1
        assertEquals(PairingResult.Paired(42), e.pairing.onMessage(msg(text = c.code)))
    }

    @Test fun firstExactSenderWins() {
        val e = Env()
        val c = e.pairing.startPairing()
        assertEquals(PairingResult.Paired(5), e.pairing.onMessage(msg(sender = 5, text = c.code)))
        assertEquals(PairingResult.Ignored, e.pairing.onMessage(msg(sender = 6, text = c.code)))
        assertEquals(5L, e.connections.connection.value!!.authorizedUserId)
    }
}
