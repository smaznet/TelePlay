package com.smaznet.teleplay.setup

import com.smaznet.teleplay.setup.qr.QrEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Known-answer tests against matrices from the `qrcode` Python package (tools/genqr_fixtures.py).
 * Fixture format: line 1 = mask the reference chose, line 2 = payload, remaining lines = matrix rows.
 * With the reference mask forced, the matrix must match module for module (data, Reed-Solomon, blocks,
 * format and version info). Automatic mask selection may differ from the reference (penalty heuristics tie-break
 * differently); it only has to produce one of the 8 valid masks.
 */
class QrEncoderTest {
    private class Fixture(val mask: Int, val payload: String, val rows: List<String>)

    private fun fixture(name: String): Fixture {
        val lines = checkNotNull(javaClass.getResourceAsStream("/qr/qr_$name.txt")).bufferedReader().readLines().filter { it.isNotEmpty() }
        return Fixture(lines[0].toInt(), lines[1], lines.drop(2))
    }

    private fun rows(m: Array<BooleanArray>) = m.map { r -> r.joinToString("") { if (it) "1" else "0" } }

    private fun check(name: String, size: Int) {
        val f = fixture(name)
        assertEquals("$name size", size, f.rows.size)
        assertEquals("$name forced mask", f.rows, rows(QrEncoder.encode(f.payload, f.mask)))
    }

    @Test fun shortPayload() = check("short", 21)
    @Test fun setupUrl() = check("url50", 33)
    @Test fun version6() = check("v5", 41)
    @Test fun versionWithVersionInfo() = check("v7", 45)
    @Test fun maxVersion() = check("v10", 57)

    @Test fun automaticMaskIsAValidMask() {
        for (name in listOf("short", "url50", "v5", "v7", "v10")) {
            val f = fixture(name)
            val auto = rows(QrEncoder.encode(f.payload))
            val masked = (0..7).map { rows(QrEncoder.encode(f.payload, it)) }
            assertTrue("$name auto matches one of the 8 masks", auto in masked)
        }
    }

    @Test fun oversizePayloadThrows() {
        try {
            QrEncoder.encode("x".repeat(400))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
