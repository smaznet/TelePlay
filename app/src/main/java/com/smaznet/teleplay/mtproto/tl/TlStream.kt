package com.smaznet.teleplay.mtproto.tl

import java.io.ByteArrayOutputStream

class TlException(message: String) : RuntimeException(message)

/** Anything that can be written as a boxed TL object (constructor id + fields). */
interface TlObject {
    fun serialize(w: TlWriter)
}

/** A TL method: serializes itself and knows how to read its own result type. */
interface TlFunction<R> : TlObject {
    fun readResult(r: TlReader): R
}

const val VECTOR_ID = 0x1cb5c415
const val BOOL_TRUE_ID = 0x997275b5.toInt()
const val BOOL_FALSE_ID = 0xbc799737.toInt()
const val GZIP_PACKED_ID = 0x3072cfa1

class TlWriter {
    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun int32(v: Int) {
        out.write(v); out.write(v ushr 8); out.write(v ushr 16); out.write(v ushr 24)
    }

    fun int64(v: Long) {
        int32(v.toInt()); int32((v ushr 32).toInt())
    }

    fun double(v: Double) = int64(java.lang.Double.doubleToRawLongBits(v))

    fun raw(b: ByteArray) = out.write(b, 0, b.size)

    fun int128(b: ByteArray) { require(b.size == 16); raw(b) }

    fun int256(b: ByteArray) { require(b.size == 32); raw(b) }

    fun bool(v: Boolean) = int32(if (v) BOOL_TRUE_ID else BOOL_FALSE_ID)

    fun bytes(b: ByteArray) {
        val header = if (b.size < 254) {
            out.write(b.size); 1
        } else {
            out.write(254); out.write(b.size); out.write(b.size ushr 8); out.write(b.size ushr 16); 4
        }
        out.write(b, 0, b.size)
        val pad = (4 - (header + b.size) % 4) % 4
        repeat(pad) { out.write(0) }
    }

    fun string(s: String) = bytes(s.toByteArray(Charsets.UTF_8))

    fun <T> vector(items: List<T>, item: (T) -> Unit) {
        int32(VECTOR_ID)
        bareVector(items, item)
    }

    fun <T> bareVector(items: List<T>, item: (T) -> Unit) {
        int32(items.size)
        items.forEach(item)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

class TlReader(private val buf: ByteArray, start: Int = 0, private val end: Int = buf.size) {
    var pos: Int = start
        private set

    val remaining: Int get() = end - pos

    private fun need(n: Int) {
        if (n < 0 || pos + n > end) throw TlException("TL read past end")
    }

    fun int32(): Int {
        need(4)
        val v = (buf[pos].toInt() and 0xff) or ((buf[pos + 1].toInt() and 0xff) shl 8) or
            ((buf[pos + 2].toInt() and 0xff) shl 16) or ((buf[pos + 3].toInt() and 0xff) shl 24)
        pos += 4
        return v
    }

    fun int64(): Long {
        val lo = int32().toLong() and 0xffffffffL
        val hi = int32().toLong()
        return (hi shl 32) or lo
    }

    fun double(): Double = java.lang.Double.longBitsToDouble(int64())

    fun raw(n: Int): ByteArray {
        need(n)
        val r = buf.copyOfRange(pos, pos + n)
        pos += n
        return r
    }

    fun int128(): ByteArray = raw(16)

    fun int256(): ByteArray = raw(32)

    fun bool(): Boolean = when (val id = int32()) {
        BOOL_TRUE_ID -> true
        BOOL_FALSE_ID -> false
        else -> throw TlException("bad Bool id 0x${Integer.toHexString(id)}")
    }

    fun bytes(): ByteArray {
        need(1)
        var len = buf[pos++].toInt() and 0xff
        var header = 1
        if (len == 254) {
            need(3)
            len = (buf[pos].toInt() and 0xff) or ((buf[pos + 1].toInt() and 0xff) shl 8) or
                ((buf[pos + 2].toInt() and 0xff) shl 16)
            pos += 3
            header = 4
        } else if (len > 254) throw TlException("bad bytes length")
        val r = raw(len)
        val pad = (4 - (header + len) % 4) % 4
        need(pad)
        pos += pad
        return r
    }

    fun string(): String = String(bytes(), Charsets.UTF_8)

    fun <T> vector(item: () -> T): List<T> {
        val id = int32()
        if (id != VECTOR_ID) throw TlException("bad vector id 0x${Integer.toHexString(id)}")
        return bareVector(item)
    }

    fun <T> bareVector(item: () -> T): List<T> {
        val n = int32()
        if (n < 0 || n > remaining) throw TlException("bad vector length")
        val l = ArrayList<T>(n)
        repeat(n) { l.add(item()) }
        return l
    }

    fun peekInt32(): Int {
        val p = pos
        val v = int32()
        pos = p
        return v
    }
}
