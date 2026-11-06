package com.smaznet.teleplay.mtproto

import com.smaznet.teleplay.mtproto.crypto.AesIge
import com.smaznet.teleplay.mtproto.crypto.Dh
import com.smaznet.teleplay.mtproto.crypto.Pq
import com.smaznet.teleplay.mtproto.crypto.Rsa
import com.smaznet.teleplay.mtproto.session.Mtproto2
import com.smaznet.teleplay.mtproto.session.Session
import com.smaznet.teleplay.mtproto.tl.TlReader
import com.smaznet.teleplay.mtproto.tl.TlWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigInteger

private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

class CryptoAndTlTest {
    @Test
    fun aesIge_openSslKnownAnswer_128bitKey() {
        // OpenSSL IGE test vector 1.
        val key = hex("000102030405060708090a0b0c0d0e0f")
        val iv = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val expected = hex("1a8519a6557be652e9da8e43da4ef4453cf456b4ca488aa383c79c98b34797cb")
        assertArrayEquals(expected, AesIge.encrypt(ByteArray(32), key, iv))
        assertArrayEquals(ByteArray(32), AesIge.decrypt(expected, key, iv))
    }

    @Test
    fun aesIge_256bitKey_matchesIndependentReference() {
        // Reference produced by an independent IGE built on `openssl enc -aes-256-ecb`.
        val key = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val iv = hex("030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dc")
        val pt = hex("05121f2c394653606d7a8794a1aebbc8d5e2effc091623303d4a5764717e8b98a5b2bfccd9e6f3000d1a2734414e5b68")
        val ct = hex("c6f8f099f157906bfaaa24eed49b3113397cf4264afde6e1444f986935e6a856ee7e0cf139230f8ab8947f373459e8da")
        assertArrayEquals(ct, AesIge.encrypt(pt, key, iv))
        assertArrayEquals(pt, AesIge.decrypt(ct, key, iv))
    }

    @Test
    fun pq_factorsTheDocumentationExample() {
        // From the MTProto auth-key documentation: 1724114033281923457 = 1229739323 * 1402015859.
        assertEquals(1229739323L to 1402015859L, Pq.factor(1724114033281923457L))
    }

    @Test
    fun pq_factorsLargeSemiprimes() {
        val p = 2147483647L // 2^31-1
        val q = 2147483629L
        assertEquals(minOf(p, q) to maxOf(p, q), Pq.factor(p * q))
    }

    @Test
    fun tlBytes_paddingAndRoundTrip() {
        for (n in listOf(0, 1, 2, 3, 4, 100, 253, 254, 255, 1000)) {
            val data = ByteArray(n) { (it * 7).toByte() }
            val w = TlWriter().also { it.bytes(data) }.toByteArray()
            assertEquals("length for $n", 0, w.size % 4)
            val r = TlReader(w)
            assertArrayEquals(data, r.bytes())
            assertEquals(0, r.remaining)
        }
    }

    @Test
    fun tlPrimitives_roundTrip() {
        val w = TlWriter()
        w.int32(-5); w.int64(Long.MIN_VALUE + 7); w.double(3.25); w.string("héllo"); w.bool(true); w.bool(false)
        w.vector(listOf(1L, 2L, 3L)) { w.int64(it) }
        val r = TlReader(w.toByteArray())
        assertEquals(-5, r.int32())
        assertEquals(Long.MIN_VALUE + 7, r.int64())
        assertEquals(3.25, r.double(), 0.0)
        assertEquals("héllo", r.string())
        assertTrue(r.bool())
        assertTrue(!r.bool())
        assertEquals(listOf(1L, 2L, 3L), r.vector { r.int64() })
    }

    @Test
    fun tlReader_rejectsOverlongVector() {
        val w = TlWriter().apply { int32(0x1cb5c415); int32(1_000_000) }
        assertThrows(Exception::class.java) { TlReader(w.toByteArray()).vector { 0 } }
    }

    @Test
    fun rsa_fingerprintsMatchTheWellKnownProductionKeys() {
        val offered = listOf(-3414540481677951611L, -5595554452916591101L) // 0xd09d1d85de64fd85, 0xb25898df208d2603
        assertEquals(-3414540481677951611L, Rsa.selectFingerprint(listOf(offered[0])))
        assertEquals(-5595554452916591101L, Rsa.selectFingerprint(listOf(offered[1])))
        assertEquals(null, Rsa.selectFingerprint(listOf(1L, 2L)))
    }

    @Test
    fun rsa_encryptProduces256Bytes() {
        val out = Rsa.encrypt(-3414540481677951611L, ByteArray(96) { it.toByte() })
        assertEquals(256, out.size)
    }

    @Test
    fun dh_acceptsKnownGroupAndRejectsOthers() {
        Dh.checkGroup(3, Dh.KNOWN_PRIME)
        assertThrows(IllegalArgumentException::class.java) { Dh.checkGroup(2, Dh.KNOWN_PRIME) }
        assertThrows(IllegalArgumentException::class.java) { Dh.checkGroup(3, Dh.KNOWN_PRIME.add(BigInteger.TWO)) }
        assertThrows(IllegalArgumentException::class.java) { Dh.checkGroup(9, Dh.KNOWN_PRIME) }
    }

    @Test
    fun dh_rangeChecks() {
        val p = Dh.KNOWN_PRIME
        assertThrows(IllegalArgumentException::class.java) { Dh.checkPublicValue(BigInteger.ONE, p) }
        assertThrows(IllegalArgumentException::class.java) { Dh.checkPublicValue(p.subtract(BigInteger.ONE), p) }
        assertThrows(IllegalArgumentException::class.java) { Dh.checkPublicValue(BigInteger.valueOf(1L shl 40), p) }
        Dh.checkPublicValue(p.shiftRight(1), p)
    }

    @Test
    fun mtproto2_roundTripAndTamperDetection() {
        val authKey = ByteArray(256) { (it * 3).toByte() }
        val inner = ByteArray(64) { it.toByte() }
        val (msgKey, enc) = Mtproto2.encrypt(authKey, inner, Mtproto2.FROM_SERVER)
        val plain = Mtproto2.decrypt(authKey, msgKey, enc, Mtproto2.FROM_SERVER)
        assertArrayEquals(inner, plain.copyOf(inner.size))
        assertTrue(plain.size - inner.size in 12..1024)
        val bad = enc.clone().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { Mtproto2.decrypt(authKey, msgKey, bad, Mtproto2.FROM_SERVER) }
        val badKey = msgKey.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { Mtproto2.decrypt(authKey, badKey, enc, Mtproto2.FROM_SERVER) }
    }

    @Test
    fun session_msgIdsAreMonotonicAndDivisibleByFour_seqNoCounts() {
        val s = Session(ByteArray(256), 1L, 0L, 0, nowMillis = { 1_700_000_000_000L })
        var last = 0L
        repeat(50) {
            val id = s.nextMsgId()
            assertEquals(0L, id % 4)
            assertTrue(id > last)
            last = id
        }
        assertEquals(0, s.nextSeqNo(false))
        assertEquals(1, s.nextSeqNo(true))
        assertEquals(2, s.nextSeqNo(false))
        assertEquals(3, s.nextSeqNo(true))
    }
}
