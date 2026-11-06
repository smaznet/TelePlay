package com.smaznet.teleplay.mtproto.crypto

/** Factorizes the server's `pq` (at most 63 bits) into p < q using Pollard's rho (Brent variant). */
object Pq {
    fun factor(pq: Long): Pair<Long, Long> {
        require(pq > 3) { "pq too small" }
        if (pq % 2 == 0L) return 2L to pq / 2
        var c = 1L
        while (true) {
            val d = rho(pq, c)
            if (d != 1L && d != pq) {
                val other = pq / d
                return if (d < other) d to other else other to d
            }
            c++
        }
    }

    private fun rho(n: Long, c: Long): Long {
        var x = 2L
        var y = 2L
        var d = 1L
        while (d == 1L) {
            x = f(x, c, n)
            y = f(f(y, c, n), c, n)
            d = gcd(if (x > y) x - y else y - x, n)
        }
        return d
    }

    private fun f(x: Long, c: Long, n: Long): Long = (mulMod(x, x, n) + c) % n

    private fun gcd(a: Long, b: Long): Long {
        var x = a
        var y = b
        while (y != 0L) { val t = x % y; x = y; y = t }
        return x
    }

    /** (a * b) mod m without overflow for m < 2^63. */
    internal fun mulMod(a: Long, b: Long, m: Long): Long {
        var result = 0L
        var x = a % m
        var y = b
        while (y > 0) {
            if (y and 1L == 1L) {
                result += x
                if (result >= m || result < 0) result -= m
            }
            x += x
            if (x >= m || x < 0) x -= m
            y = y shr 1
        }
        return result
    }
}
