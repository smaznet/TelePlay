package com.smaznet.teleplay.mtproto.crypto

import java.math.BigInteger
import java.security.SecureRandom

/** Diffie-Hellman helpers with the checks required by the MTProto security guidelines. */
object Dh {
    private val random = SecureRandom()
    private val ONE = BigInteger.ONE
    private val TWO_POW_1984 = ONE.shiftLeft(2048 - 64)

    /** Telegram's well-known 2048-bit safe prime (big-endian hex). */
    val KNOWN_PRIME: BigInteger = BigInteger(
        "c71caeb9c6b1c9048e6c522f70f13f73980d40238e3e21c14934d037563d930f" +
            "48198a0aa7c14058229493d22530f4dbfa336f6e0ac925139543aed44cce7c37" +
            "20fd51f69458705ac68cd4fe6b6b13abdc9746512969328454f18faf8c595f64" +
            "2477fe96bb2a941d5bcd1d4ac8cc49880708fa9b378e3c4f3a9060bee67cf9a4" +
            "a4a695811051907e162753b56b0f6b410dba74d8a84b2a14b3144e0ef1284754" +
            "fd17ed950d5965b4b9dd46582db1178d169c6bc465b0d6ff9ca3928fef5b9ae4" +
            "e418fc15e83ebea0f87fa9ff5eed70050ded2849f47bf959d956850ce929851f" +
            "0d8115f635b105ee2e4e15d04b2454bf6f4fadf034b10403119cd8e3b92fcc5b",
        16,
    )

    /** Validates server-provided (g, dh_prime). The prime must equal the known safe prime. */
    fun checkGroup(g: Int, dhPrime: BigInteger) {
        require(g in 2..7) { "bad g" }
        require(dhPrime.bitLength() == 2048) { "bad dh_prime size" }
        require(dhPrime == KNOWN_PRIME) { "unknown dh_prime" }
        // g must generate a subgroup of prime order q = (p-1)/2 (Telegram's guideline conditions)
        when (g) {
            2 -> require(dhPrime.mod(BigInteger.valueOf(8)).toInt() == 7)
            3 -> require(dhPrime.mod(BigInteger.valueOf(3)).toInt() == 2)
            4 -> {}
            5 -> require(dhPrime.mod(BigInteger.valueOf(5)).toInt() in listOf(1, 4))
            6 -> require(dhPrime.mod(BigInteger.valueOf(24)).toInt() in listOf(19, 23))
            7 -> require(dhPrime.mod(BigInteger.valueOf(7)).toInt() in listOf(3, 5, 6))
        }
    }

    /** `1 < value < p-1` and within 2^(2048-64) of both ends. */
    fun checkPublicValue(v: BigInteger, p: BigInteger) {
        require(v > ONE && v < p.subtract(ONE)) { "DH value out of range" }
        require(v >= TWO_POW_1984 && v <= p.subtract(TWO_POW_1984)) { "DH value too close to the edge" }
    }

    fun randomExponent(): BigInteger = BigInteger(2048, random)

    fun toFixed256(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        val out = ByteArray(256)
        val src = if (b.size > 256) b.copyOfRange(b.size - 256, b.size) else b
        System.arraycopy(src, 0, out, 256 - src.size, src.size)
        return out
    }
}
