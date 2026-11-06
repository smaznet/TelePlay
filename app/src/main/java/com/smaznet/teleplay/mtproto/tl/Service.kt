package com.smaznet.teleplay.mtproto.tl

/** Hand-written MTProto service constructors (auth-key exchange and session layer). */
object Ids {
    const val REQ_PQ_MULTI = 0xbe7e8ef1.toInt()
    const val RES_PQ = 0x05162463
    const val P_Q_INNER_DATA_DC = 0xa9f55f95.toInt()
    const val REQ_DH_PARAMS = 0xd712e4be.toInt()
    const val SERVER_DH_PARAMS_FAIL = 0x79cb045d
    const val SERVER_DH_PARAMS_OK = 0xd0e8075c.toInt()
    const val SERVER_DH_INNER_DATA = 0xb5890dba.toInt()
    const val CLIENT_DH_INNER_DATA = 0x6643b654
    const val SET_CLIENT_DH_PARAMS = 0xf5045f1f.toInt()
    const val DH_GEN_OK = 0x3bcbf734
    const val DH_GEN_RETRY = 0x46dc1fb9
    const val DH_GEN_FAIL = 0xa69dae02.toInt()
    const val RPC_RESULT = 0xf35c6d01.toInt()
    const val RPC_ERROR = 0x2144ca19
    const val MSGS_ACK = 0x62d6b459
    const val BAD_MSG_NOTIFICATION = 0xa7eff811.toInt()
    const val BAD_SERVER_SALT = 0xedab447b.toInt()
    const val NEW_SESSION_CREATED = 0x9ec20908.toInt()
    const val PING = 0x7abe77ec
    const val PONG = 0x347773c5
    const val MSG_CONTAINER = 0x73f1f8dc
    const val GZIP_PACKED = 0x3072cfa1
    const val UPDATES_TOO_LONG = 0xe317af7e.toInt()
}

class ReqPqMulti(val nonce: ByteArray) : TlObject {
    override fun serialize(w: TlWriter) { w.int32(Ids.REQ_PQ_MULTI); w.int128(nonce) }
}

class ResPq(val nonce: ByteArray, val serverNonce: ByteArray, val pq: ByteArray, val fingerprints: List<Long>) {
    companion object {
        fun read(r: TlReader): ResPq {
            val id = r.int32()
            if (id != Ids.RES_PQ) throw TlException("expected resPQ, got 0x${Integer.toHexString(id)}")
            return ResPq(r.int128(), r.int128(), r.bytes(), r.vector { r.int64() })
        }
    }
}

class PQInnerDataDc(
    val pq: ByteArray, val p: ByteArray, val q: ByteArray,
    val nonce: ByteArray, val serverNonce: ByteArray, val newNonce: ByteArray, val dc: Int,
) : TlObject {
    override fun serialize(w: TlWriter) {
        w.int32(Ids.P_Q_INNER_DATA_DC)
        w.bytes(pq); w.bytes(p); w.bytes(q)
        w.int128(nonce); w.int128(serverNonce); w.int256(newNonce); w.int32(dc)
    }
}

class ReqDhParams(
    val nonce: ByteArray, val serverNonce: ByteArray, val p: ByteArray, val q: ByteArray,
    val fingerprint: Long, val encryptedData: ByteArray,
) : TlObject {
    override fun serialize(w: TlWriter) {
        w.int32(Ids.REQ_DH_PARAMS)
        w.int128(nonce); w.int128(serverNonce); w.bytes(p); w.bytes(q)
        w.int64(fingerprint); w.bytes(encryptedData)
    }
}

sealed class ServerDhParams {
    class Ok(val nonce: ByteArray, val serverNonce: ByteArray, val encryptedAnswer: ByteArray) : ServerDhParams()
    class Fail(val nonce: ByteArray, val serverNonce: ByteArray, val newNonceHash: ByteArray) : ServerDhParams()

    companion object {
        fun read(r: TlReader): ServerDhParams = when (val id = r.int32()) {
            Ids.SERVER_DH_PARAMS_OK -> Ok(r.int128(), r.int128(), r.bytes())
            Ids.SERVER_DH_PARAMS_FAIL -> Fail(r.int128(), r.int128(), r.int128())
            else -> throw TlException("bad server_DH_params 0x${Integer.toHexString(id)}")
        }
    }
}

class ServerDhInnerData(
    val nonce: ByteArray, val serverNonce: ByteArray, val g: Int,
    val dhPrime: ByteArray, val gA: ByteArray, val serverTime: Int,
) {
    companion object {
        fun read(r: TlReader): ServerDhInnerData {
            val id = r.int32()
            if (id != Ids.SERVER_DH_INNER_DATA) throw TlException("bad server_DH_inner_data")
            return ServerDhInnerData(r.int128(), r.int128(), r.int32(), r.bytes(), r.bytes(), r.int32())
        }
    }
}

class ClientDhInnerData(
    val nonce: ByteArray, val serverNonce: ByteArray, val retryId: Long, val gB: ByteArray,
) : TlObject {
    override fun serialize(w: TlWriter) {
        w.int32(Ids.CLIENT_DH_INNER_DATA)
        w.int128(nonce); w.int128(serverNonce); w.int64(retryId); w.bytes(gB)
    }
}

class SetClientDhParams(val nonce: ByteArray, val serverNonce: ByteArray, val encryptedData: ByteArray) : TlObject {
    override fun serialize(w: TlWriter) {
        w.int32(Ids.SET_CLIENT_DH_PARAMS)
        w.int128(nonce); w.int128(serverNonce); w.bytes(encryptedData)
    }
}

class DhGenAnswer(val kind: Kind, val nonce: ByteArray, val serverNonce: ByteArray, val newNonceHash: ByteArray) {
    enum class Kind { OK, RETRY, FAIL }

    companion object {
        fun read(r: TlReader): DhGenAnswer {
            val kind = when (val id = r.int32()) {
                Ids.DH_GEN_OK -> Kind.OK
                Ids.DH_GEN_RETRY -> Kind.RETRY
                Ids.DH_GEN_FAIL -> Kind.FAIL
                else -> throw TlException("bad dh_gen answer 0x${Integer.toHexString(id)}")
            }
            return DhGenAnswer(kind, r.int128(), r.int128(), r.int128())
        }
    }
}

class Ping(val pingId: Long) : TlObject {
    override fun serialize(w: TlWriter) { w.int32(Ids.PING); w.int64(pingId) }
}

class MsgsAck(val msgIds: List<Long>) : TlObject {
    override fun serialize(w: TlWriter) { w.int32(Ids.MSGS_ACK); w.vector(msgIds) { w.int64(it) } }
}

/** A serialized object that is written as-is (used to pass already-serialized bodies around). */
class RawObject(val bytes: ByteArray) : TlObject {
    override fun serialize(w: TlWriter) = w.raw(bytes)
}

fun TlObject.toBytes(): ByteArray = TlWriter().also { serialize(it) }.toByteArray()
