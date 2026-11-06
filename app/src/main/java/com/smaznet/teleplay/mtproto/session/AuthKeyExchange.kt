package com.smaznet.teleplay.mtproto.session

import com.smaznet.teleplay.mtproto.crypto.AesIge
import com.smaznet.teleplay.mtproto.crypto.Dh
import com.smaznet.teleplay.mtproto.crypto.Pq
import com.smaznet.teleplay.mtproto.crypto.Rsa
import com.smaznet.teleplay.mtproto.tl.*
import com.smaznet.teleplay.mtproto.transport.Transport
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

class AuthKeyResult(
    val authKey: ByteArray,
    val authKeyId: Long,
    val serverSalt: Long,
    /** serverTimeSeconds - localTimeSeconds */
    val timeOffset: Int,
)

/** Unencrypted auth-key creation (req_pq_multi -> req_DH_params -> set_client_DH_params). */
class AuthKeyExchange(
    private val transport: Transport,
    private val dcId: Int,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()
    private var lastMsgId = 0L

    private fun sha1(vararg p: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").also { md -> p.forEach { md.update(it) } }.digest()

    private fun rnd(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    private fun unsignedBytes(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    private fun longToTlBytes(v: Long): ByteArray = unsignedBytes(BigInteger.valueOf(v))

    private suspend fun call(body: TlObject): TlReader {
        val payload = body.toBytes()
        val w = TlWriter()
        w.int64(0L)
        var id = ((nowMillis() / 1000) shl 32) and 3L.inv()
        if (id <= lastMsgId) id = lastMsgId + 4
        lastMsgId = id
        w.int64(id)
        w.int32(payload.size)
        w.raw(payload)
        transport.send(w.toByteArray())
        val r = TlReader(transport.receive())
        if (r.int64() != 0L) throw IOException("expected unencrypted message")
        r.int64()
        val len = r.int32()
        if (len > r.remaining) throw IOException("bad length")
        return r
    }

    suspend fun run(): AuthKeyResult {
        val nonce = rnd(16)
        val resPq = ResPq.read(call(ReqPqMulti(nonce)))
        if (!resPq.nonce.contentEquals(nonce)) throw IOException("nonce mismatch")
        val pqLong = BigInteger(1, resPq.pq).also { if (it.bitLength() > 63) throw IOException("pq too large") }.toLong()
        val (p, q) = Pq.factor(pqLong)
        val fingerprint = Rsa.selectFingerprint(resPq.fingerprints) ?: throw IOException("no known server key")
        var retryId = 0L
        // The whole exchange may be retried from the DH step on dh_gen_retry.
        repeat(MAX_DH_ATTEMPTS) {
            val result = dhStep(nonce, resPq, p, q, fingerprint, retryId)
            when (result) {
                is Step.Done -> return result.value
                is Step.Retry -> retryId = result.authKeyAuxHash
            }
        }
        throw IOException("dh_gen_retry limit reached")
    }

    private sealed class Step {
        class Done(val value: AuthKeyResult) : Step()
        class Retry(val authKeyAuxHash: Long) : Step()
    }

    private suspend fun dhStep(nonce: ByteArray, resPq: ResPq, p: Long, q: Long, fingerprint: Long, retryId: Long): Step {
        val serverNonce = resPq.serverNonce
        val newNonce = rnd(32)
        val inner = PQInnerDataDc(resPq.pq, longToTlBytes(p), longToTlBytes(q), nonce, serverNonce, newNonce, dcId).toBytes()
        val encrypted = Rsa.encrypt(fingerprint, inner)
        val params = ServerDhParams.read(
            call(ReqDhParams(nonce, serverNonce, longToTlBytes(p), longToTlBytes(q), fingerprint, encrypted)),
        )
        val ok = when (params) {
            is ServerDhParams.Fail -> throw IOException("server_DH_params_fail")
            is ServerDhParams.Ok -> params
        }
        if (!ok.nonce.contentEquals(nonce) || !ok.serverNonce.contentEquals(serverNonce)) throw IOException("nonce mismatch")

        val tmpKey = sha1(newNonce, serverNonce) + sha1(serverNonce, newNonce).copyOfRange(0, 12)
        val tmpIv = sha1(serverNonce, newNonce).copyOfRange(12, 20) + sha1(newNonce, newNonce) + newNonce.copyOfRange(0, 4)
        val answer = AesIge.decrypt(ok.encryptedAnswer, tmpKey, tmpIv)
        val ar = TlReader(answer, 20)
        val data = ServerDhInnerData.read(ar)
        val consumed = ar.pos - 20
        if (!MessageDigest.isEqual(answer.copyOfRange(0, 20), sha1(answer.copyOfRange(20, 20 + consumed)))) {
            throw IOException("server_DH_inner_data hash mismatch")
        }
        if (!data.nonce.contentEquals(nonce) || !data.serverNonce.contentEquals(serverNonce)) throw IOException("nonce mismatch")

        val dhPrime = BigInteger(1, data.dhPrime)
        try {
            Dh.checkGroup(data.g, dhPrime)
            val gA = BigInteger(1, data.gA)
            Dh.checkPublicValue(gA, dhPrime)
            val g = BigInteger.valueOf(data.g.toLong())
            val b = Dh.randomExponent()
            val gB = g.modPow(b, dhPrime)
            Dh.checkPublicValue(gB, dhPrime)

            val clientInner = ClientDhInnerData(nonce, serverNonce, retryId, unsignedBytes(gB)).toBytes()
            val withHash = sha1(clientInner) + clientInner
            val padded = withHash + ByteArray((16 - withHash.size % 16) % 16).also { random.nextBytes(it) }
            val setAnswer = DhGenAnswer.read(
                call(SetClientDhParams(nonce, serverNonce, AesIge.encrypt(padded, tmpKey, tmpIv))),
            )
            if (!setAnswer.nonce.contentEquals(nonce) || !setAnswer.serverNonce.contentEquals(serverNonce)) {
                throw IOException("nonce mismatch")
            }

            val authKey = Dh.toFixed256(gA.modPow(b, dhPrime))
            val authKeyHash = sha1(authKey)
            val auxHash = authKeyHash.copyOfRange(0, 8)
            val tag = when (setAnswer.kind) {
                DhGenAnswer.Kind.OK -> 1
                DhGenAnswer.Kind.RETRY -> 2
                DhGenAnswer.Kind.FAIL -> 3
            }
            val expected = sha1(newNonce, byteArrayOf(tag.toByte()), auxHash).copyOfRange(4, 20)
            if (!MessageDigest.isEqual(expected, setAnswer.newNonceHash)) throw IOException("new_nonce_hash mismatch")
            return when (setAnswer.kind) {
                DhGenAnswer.Kind.FAIL -> throw IOException("dh_gen_fail")
                DhGenAnswer.Kind.RETRY -> Step.Retry(readLongLe(auxHash))
                DhGenAnswer.Kind.OK -> {
                    val keyId = readLongLe(authKeyHash.copyOfRange(12, 20))
                    var salt = 0L
                    for (i in 0 until 8) {
                        salt = salt or (((newNonce[i].toInt() xor serverNonce[i].toInt()).toLong() and 0xff) shl (8 * i))
                    }
                    val offset = (data.serverTime.toLong() - nowMillis() / 1000).toInt()
                    Step.Done(AuthKeyResult(authKey, keyId, salt, offset))
                }
            }
        } catch (e: IllegalArgumentException) {
            throw IOException("DH validation failed: ${e.message}")
        }
    }

    private fun readLongLe(b: ByteArray): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[i].toLong() and 0xff) shl (8 * i))
        return v
    }

    private companion object {
        const val MAX_DH_ATTEMPTS = 3
    }
}
