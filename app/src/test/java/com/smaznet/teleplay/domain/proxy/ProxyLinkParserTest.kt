package com.smaznet.teleplay.domain.proxy

import org.junit.Assert.*
import org.junit.Test

class ProxyLinkParserTest {
    private val hex16 = "0102030405060708090a0b0c0d0e0f10"

    private fun valid(link: String): ProxyDraft = (ProxyLinkParser.parse(link) as? ParsedLink.Valid)?.draft ?: fail("not valid: $link").let { error("") }
    private fun invalid(link: String): String = (ProxyLinkParser.parse(link) as? ParsedLink.Invalid)?.reason ?: fail("should be invalid: $link").let { error("") }

    @Test fun socksLinkInEveryForm() {
        for (prefix in listOf("https://t.me/socks", "http://t.me/socks", "https://telegram.me/socks", "https://telegram.dog/socks", "tg://socks")) {
            val d = valid("$prefix?server=1.2.3.4&port=1080")
            assertEquals(ProxyKind.SOCKS5, d.kind); assertEquals("1.2.3.4", d.server); assertEquals(1080, d.port)
            assertNull(d.username)
        }
        val d = valid("tg://socks?server=h.example.com&port=443&user=bob&pass=p%40ss")
        assertEquals("bob", d.username); assertEquals("p@ss", d.password)
    }

    @Test fun mtprotoPlainSecretHex() {
        val d = valid("https://t.me/proxy?server=mt.example.org&port=443&secret=$hex16")
        assertEquals(ProxyKind.MTPROTO, d.kind)
        assertTrue(d.secret is MtSecret.Plain)
        assertArrayEquals(KEY, d.secret!!.key)
    }

    @Test fun ddSecretIsPadded() {
        val d = valid("tg://proxy?server=a.b&port=443&secret=dd$hex16")
        assertTrue(d.secret is MtSecret.Padded)
        assertArrayEquals(KEY, d.secret!!.key)
    }

    @Test fun eeSecretIsFakeTlsWithDomain() {
        val domain = "www.google.com"
        val hexDomain = domain.toByteArray().joinToString("") { "%02x".format(it) }
        val d = valid("tg://proxy?server=a.b&port=443&secret=ee$hex16$hexDomain")
        val s = d.secret as MtSecret.FakeTls
        assertEquals(domain, s.domain)
        assertArrayEquals(KEY, s.key)
    }

    @Test fun base64UrlSecretsDecodeToTheSameKinds() {
        fun b64(bytes: ByteArray): String {
            val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val sb = StringBuilder(); var i = 0
            while (i < bytes.size) {
                val n = minOf(3, bytes.size - i)
                var v = 0; for (k in 0 until 3) v = (v shl 8) or (if (k < n) bytes[i + k].toInt() and 0xff else 0)
                for (k in 0..n) sb.append(a[(v shr (18 - 6 * k)) and 63])
                i += 3
            }
            return sb.toString()
        }
        assertTrue(valid("tg://proxy?server=a.b&port=1&secret=${b64(KEY)}").secret is MtSecret.Plain)
        assertTrue(valid("tg://proxy?server=a.b&port=1&secret=${b64(byteArrayOf(0xdd.toByte()) + KEY)}").secret is MtSecret.Padded)
        val ee = byteArrayOf(0xee.toByte()) + KEY + "example.com".toByteArray()
        val s = valid("tg://proxy?server=a.b&port=1&secret=${b64(ee)}").secret as MtSecret.FakeTls
        assertEquals("example.com", s.domain)
        assertEquals(b64(KEY).length % 4 != 0, true) // exercised the unpadded path
    }

    @Test fun badSecretsAreInvalid() {
        assertEquals("secret", invalid("tg://proxy?server=a.b&port=1"))
        assertEquals("secret", invalid("tg://proxy?server=a.b&port=1&secret=abcd"))
        assertEquals("secret", invalid("tg://proxy?server=a.b&port=1&secret=dd${hex16}00"))
        assertEquals("secret", invalid("tg://proxy?server=a.b&port=1&secret=ee$hex16")) // no domain
        assertEquals("secret", invalid("tg://proxy?server=a.b&port=1&secret=zz$hex16"))
    }

    @Test fun portAndServerAreChecked() {
        assertEquals("port", invalid("tg://socks?server=1.2.3.4&port=0"))
        assertEquals("port", invalid("tg://socks?server=1.2.3.4&port=65536"))
        assertEquals("port", invalid("tg://socks?server=1.2.3.4"))
        assertEquals("server", invalid("tg://socks?port=1080"))
        assertEquals("server", invalid("tg://socks?server=bad%20host&port=1080"))
    }

    @Test fun socksCredentialsComeInPairs() {
        assertEquals("credentials", invalid("tg://socks?server=1.2.3.4&port=1&user=bob"))
        assertEquals("credentials", invalid("tg://socks?server=1.2.3.4&port=1&pass=x"))
    }

    @Test fun otherSchemesAreRejected() {
        assertEquals("scheme", invalid("http://example.com/proxy?server=a.b&port=1"))
        assertEquals("scheme", invalid("https://t.me/joinchat/abc"))
        assertEquals("scheme", invalid("ss://abc"))
    }

    @Test fun linksAreFoundInNoisyText() {
        val text = """
            Free proxies!
            1) https://t.me/proxy?server=a.example.com&port=443&secret=$hex16,
            <tg://socks?server=1.1.1.1&port=1080&user=u&pass=p>
            (https://t.me/socks?server=2.2.2.2&port=1081).
            see https://example.com/page and nothing else
        """.trimIndent()
        val links = ProxyLinkParser.extractLinks(text)
        assertEquals(3, links.size)
        assertTrue(links.all { ProxyLinkParser.parse(it) is ParsedLink.Valid })
    }

    @Test fun neverThrowsOnGarbage() {
        for (s in listOf("", "tg://", "tg://proxy?", "tg://proxy?&&&", "https://t.me/proxy?server=%zz&port=%", "\u0000\u0001")) ProxyLinkParser.parse(s)
    }

    @Test fun secretsNeverShowInToString() {
        val d = valid("tg://proxy?server=secret-host.example&port=443&secret=$hex16")
        assertFalse(d.toString().contains("secret-host"))
        assertFalse(d.secret.toString().contains("0102"))
    }
}
