package com.smaznet.teleplay.setup.qr

/**
 * Minimal QR Code generator: byte mode, error correction level M, versions 1-10, automatic mask selection.
 * Returns a square matrix where `true` is a dark module. No Android dependencies.
 */
object QrEncoder {
    private const val MAX_VERSION = 10
    private val ECC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val NUM_BLOCKS = intArrayOf(1, 1, 2, 2, 2, 4, 4, 4, 5, 5)

    fun encode(text: String): Array<BooleanArray> = encode(text, null)

    internal fun encode(text: String, forcedMask: Int?): Array<BooleanArray> {
        val data = text.toByteArray(Charsets.UTF_8)
        var version = 1
        while (true) {
            if (version > MAX_VERSION) throw IllegalArgumentException("Payload too long for QR version $MAX_VERSION")
            val countBits = if (version < 10) 8 else 16
            if (4 + countBits + data.size * 8 <= dataCodewords(version) * 8) break
            version++
        }
        val codewords = addEccAndInterleave(buildDataCodewords(data, version), version)
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }
        drawFunctionPatterns(modules, isFunction, version)
        drawCodewords(modules, isFunction, codewords)
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in (forcedMask?.let { it..it } ?: 0..7)) {
            applyMask(modules, isFunction, mask)
            drawFormatBits(modules, isFunction, mask)
            val p = penalty(modules)
            if (p < bestPenalty) { bestPenalty = p; bestMask = mask }
            applyMask(modules, isFunction, mask) // XOR again to undo
        }
        applyMask(modules, isFunction, bestMask)
        drawFormatBits(modules, isFunction, bestMask)
        return modules
    }

    // --- capacity ------------------------------------------------------------------------------

    private fun rawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val numAlign = version / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    private fun dataCodewords(version: Int): Int =
        rawDataModules(version) / 8 - ECC_PER_BLOCK[version - 1] * NUM_BLOCKS[version - 1]

    // --- data ----------------------------------------------------------------------------------

    private fun buildDataCodewords(data: ByteArray, version: Int): ByteArray {
        val bits = ArrayList<Int>()
        fun put(value: Int, len: Int) { for (i in len - 1 downTo 0) bits.add((value ushr i) and 1) }
        put(0b0100, 4)
        put(data.size, if (version < 10) 8 else 16)
        for (b in data) put(b.toInt() and 0xFF, 8)
        val capacity = dataCodewords(version) * 8
        put(0, minOf(4, capacity - bits.size))
        while (bits.size % 8 != 0) bits.add(0)
        val out = ByteArray(capacity / 8)
        for (i in bits.indices) out[i / 8] = (out[i / 8].toInt() or (bits[i] shl (7 - i % 8))).toByte()
        var pad = 0xEC
        for (i in bits.size / 8 until out.size) { out[i] = pad.toByte(); pad = pad xor 0xEC xor 0x11 }
        return out
    }

    private fun addEccAndInterleave(data: ByteArray, version: Int): ByteArray {
        val numBlocks = NUM_BLOCKS[version - 1]
        val eccLen = ECC_PER_BLOCK[version - 1]
        val rawCodewords = rawDataModules(version) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockLen = rawCodewords / numBlocks
        val divisor = reedSolomonDivisor(eccLen)
        val blocks = ArrayList<ByteArray>()
        var k = 0
        for (i in 0 until numBlocks) {
            val dataLen = shortBlockLen - eccLen + if (i < numShortBlocks) 0 else 1
            val chunk = data.copyOfRange(k, k + dataLen)
            k += dataLen
            val ecc = reedSolomonRemainder(chunk, divisor)
            val block = ByteArray(shortBlockLen + 1)
            // Layout: data bytes, (padding slot for short blocks), ecc bytes.
            chunk.copyInto(block, 0)
            ecc.copyInto(block, block.size - eccLen)
            blocks.add(block)
        }
        val result = ByteArray(rawCodewords)
        var idx = 0
        for (i in 0..shortBlockLen) {
            for (j in blocks.indices) {
                if (i != shortBlockLen - eccLen || j >= numShortBlocks) {
                    result[idx++] = blocks[j][i]
                }
            }
        }
        return result
    }

    private fun reedSolomonDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMul(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = gfMul(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: ByteArray, divisor: IntArray): ByteArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor result[0]
            for (i in 0 until result.size - 1) result[i] = result[i + 1]
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor gfMul(divisor[i], factor)
        }
        return ByteArray(result.size) { result[it].toByte() }
    }

    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    // --- function patterns -----------------------------------------------------------------------

    private fun set(m: Array<BooleanArray>, f: Array<BooleanArray>, x: Int, y: Int, dark: Boolean) {
        m[y][x] = dark
        f[y][x] = true
    }

    private fun drawFunctionPatterns(m: Array<BooleanArray>, f: Array<BooleanArray>, version: Int) {
        val size = m.size
        for (i in 0 until size) {
            set(m, f, 6, i, i % 2 == 0)
            set(m, f, i, 6, i % 2 == 0)
        }
        drawFinder(m, f, 3, 3)
        drawFinder(m, f, size - 4, 3)
        drawFinder(m, f, 3, size - 4)
        val pos = alignmentPositions(version)
        for (i in pos.indices) for (j in pos.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == pos.size - 1) || (i == pos.size - 1 && j == 0)) continue
            drawAlignment(m, f, pos[i], pos[j])
        }
        drawFormatBits(m, f, 0) // reserve
        drawVersion(m, f, version)
    }

    private fun drawFinder(m: Array<BooleanArray>, f: Array<BooleanArray>, cx: Int, cy: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val x = cx + dx
            val y = cy + dy
            if (x in m.indices && y in m.indices) {
                val dist = maxOf(Math.abs(dx), Math.abs(dy))
                set(m, f, x, y, dist != 2 && dist != 4)
            }
        }
    }

    private fun drawAlignment(m: Array<BooleanArray>, f: Array<BooleanArray>, cx: Int, cy: Int) {
        for (dy in -2..2) for (dx in -2..2) {
            set(m, f, cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }
    }

    private fun alignmentPositions(version: Int): IntArray {
        if (version == 1) return IntArray(0)
        val num = version / 7 + 2
        val size = version * 4 + 17
        val step = if (version == 32) 26 else (version * 4 + num * 2 + 1) / (num * 2 - 2) * 2
        val result = IntArray(num)
        result[0] = 6
        var p = size - 7
        for (i in num - 1 downTo 1) { result[i] = p; p -= step }
        return result
    }

    private fun drawFormatBits(m: Array<BooleanArray>, f: Array<BooleanArray>, mask: Int) {
        val size = m.size
        val data = (0 shl 3) or mask // ECC level M = 00
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = ((bits ushr i) and 1) != 0
        for (i in 0..5) set(m, f, 8, i, bit(i))
        set(m, f, 8, 7, bit(6))
        set(m, f, 8, 8, bit(7))
        set(m, f, 7, 8, bit(8))
        for (i in 9..14) set(m, f, 14 - i, 8, bit(i))
        for (i in 0..7) set(m, f, size - 1 - i, 8, bit(i))
        for (i in 8..14) set(m, f, 8, size - 15 + i, bit(i))
        set(m, f, 8, size - 8, true)
    }

    private fun drawVersion(m: Array<BooleanArray>, f: Array<BooleanArray>, version: Int) {
        if (version < 7) return
        var rem = version
        for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        val bits = (version shl 12) or rem
        val size = m.size
        for (i in 0 until 18) {
            val dark = ((bits ushr i) and 1) != 0
            val a = size - 11 + i % 3
            val b = i / 3
            set(m, f, a, b, dark)
            set(m, f, b, a, dark)
        }
    }

    // --- codewords, masks, penalty ---------------------------------------------------------------

    private fun drawCodewords(m: Array<BooleanArray>, f: Array<BooleanArray>, data: ByteArray) {
        val size = m.size
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0 until 2) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!f[y][x] && i < data.size * 8) {
                        m[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(m: Array<BooleanArray>, f: Array<BooleanArray>, mask: Int) {
        for (y in m.indices) for (x in m.indices) {
            val invert = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (!f[y][x] && invert) m[y][x] = !m[y][x]
        }
    }

    private fun penalty(m: Array<BooleanArray>): Int {
        val size = m.size
        var result = 0
        // Rules 1 and 3 on rows, then columns
        for (pass in 0 until 2) {
            for (a in 0 until size) {
                var runColor = false
                var runLen = 0
                val history = IntArray(7)
                for (b in 0 until size) {
                    val c = if (pass == 0) m[a][b] else m[b][a]
                    if (c == runColor) {
                        runLen++
                        if (runLen == 5) result += 3 else if (runLen > 5) result++
                    } else {
                        addHistory(runLen, history, size)
                        if (!runColor) result += finderCount(history) * 40
                        runColor = c
                        runLen = 1
                    }
                }
                result += terminateAndCount(runColor, runLen, history, size) * 40
            }
        }
        // Rule 2: 2x2 blocks
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = m[y][x]
            if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += 3
        }
        // Rule 4: balance
        var dark = 0
        for (row in m) for (c in row) if (c) dark++
        val total = size * size
        val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        result += k * 10
        return result
    }

    // Finder-like pattern detection over a run-length history with virtual light borders.
    private fun addHistory(runLen: Int, history: IntArray, size: Int) {
        var len = runLen
        if (history[0] == 0) len += size
        for (i in history.size - 1 downTo 1) history[i] = history[i - 1]
        history[0] = len
    }

    private fun finderCount(h: IntArray): Int {
        val n = h[1]
        val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
        return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) + (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0)
    }

    private fun terminateAndCount(runColor: Boolean, runLen: Int, history: IntArray, size: Int): Int {
        var len = runLen
        if (runColor) {
            addHistory(len, history, size)
            len = 0
        }
        len += size
        addHistory(len, history, size)
        return finderCount(history)
    }
}
