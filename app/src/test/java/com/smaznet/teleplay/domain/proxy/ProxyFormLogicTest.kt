package com.smaznet.teleplay.domain.proxy

import org.junit.Assert.*
import org.junit.Test

class ProxyFormLogicTest {
    private val hex16 = "0102030405060708090a0b0c0d0e0f10"
    private fun ok(f: ProxyForm) = (ProxyFormLogic.toDraft(f) as FormResult.Ok).draft
    private fun bad(f: ProxyForm) = (ProxyFormLogic.toDraft(f) as FormResult.Bad).error

    @Test fun socksWithAndWithoutCredentials() {
        assertNull(ok(ProxyForm(ProxyKind.SOCKS5, "1.2.3.4", "1080")).username)
        val d = ok(ProxyForm(ProxyKind.SOCKS5, " host.example.com ", "1080", name = " home ", username = "u", password = "p"))
        assertEquals("host.example.com", d.server); assertEquals("home", d.name); assertEquals("u", d.username)
    }

    @Test fun socksValidation() {
        assertEquals(FormError.SERVER, bad(ProxyForm(ProxyKind.SOCKS5, "", "1")))
        assertEquals(FormError.SERVER, bad(ProxyForm(ProxyKind.SOCKS5, "not a host", "1")))
        assertEquals(FormError.PORT, bad(ProxyForm(ProxyKind.SOCKS5, "a.b", "")))
        assertEquals(FormError.PORT, bad(ProxyForm(ProxyKind.SOCKS5, "a.b", "0")))
        assertEquals(FormError.PORT, bad(ProxyForm(ProxyKind.SOCKS5, "a.b", "65536")))
        assertEquals(FormError.CREDENTIALS, bad(ProxyForm(ProxyKind.SOCKS5, "a.b", "1", username = "u")))
        assertEquals(FormError.CREDENTIALS, bad(ProxyForm(ProxyKind.SOCKS5, "a.b", "1", password = "p")))
        assertEquals(FormError.NAME, bad(ProxyForm(ProxyKind.SOCKS5, "a.b", "1", name = "x".repeat(41))))
    }

    @Test fun mtprotoAcceptsAllThreeSecretKinds() {
        assertTrue(ok(ProxyForm(ProxyKind.MTPROTO, "a.b", "443", secret = hex16)).secret is MtSecret.Plain)
        assertTrue(ok(ProxyForm(ProxyKind.MTPROTO, "a.b", "443", secret = "dd$hex16")).secret is MtSecret.Padded)
        val ee = "ee$hex16" + "example.com".toByteArray().joinToString("") { "%02x".format(it) }
        assertEquals("example.com", (ok(ProxyForm(ProxyKind.MTPROTO, "a.b", "443", secret = ee)).secret as MtSecret.FakeTls).domain)
        assertEquals(FormError.SECRET, bad(ProxyForm(ProxyKind.MTPROTO, "a.b", "443", secret = "123")))
        assertEquals(FormError.SECRET, bad(ProxyForm(ProxyKind.MTPROTO, "a.b", "443")))
    }

    @Test fun detectedSecretKindForTheForm() {
        assertEquals(SecretKind.PLAIN, ProxyFormLogic.secretKind(hex16))
        assertEquals(SecretKind.PADDED, ProxyFormLogic.secretKind("dd$hex16"))
        val ee = "ee$hex16" + "a.example".toByteArray().joinToString("") { "%02x".format(it) }
        assertEquals(SecretKind.FAKE_TLS, ProxyFormLogic.secretKind(ee))
        assertEquals("a.example", ProxyFormLogic.coverDomain(ee))
        assertNull(ProxyFormLogic.secretKind("xyz"))
    }

    @Test fun aPastedLinkFillsTheForm() {
        val f = ProxyFormLogic.fromLink("look: https://t.me/proxy?server=mt.example.org&port=8443&secret=dd$hex16 ok")!!
        assertEquals(ProxyKind.MTPROTO, f.kind); assertEquals("mt.example.org", f.server); assertEquals("8443", f.port); assertEquals("dd$hex16", f.secret)
        val s = ProxyFormLogic.fromLink("tg://socks?server=1.1.1.1&port=1080&user=u&pass=p")!!
        assertEquals("u", s.username); assertEquals("p", s.password)
        assertNull(ProxyFormLogic.fromLink("not a link"))
    }

    @Test fun editingRoundTripsASecretThroughTheForm() {
        val secret = MtSecret.FakeTls(ByteArray(16) { it.toByte() }, "cover.example")
        val d = ProxyDraft(ProxyKind.MTPROTO, "a.b", 443, null, secret = secret)
        val back = ok(ProxyFormLogic.fromDraft(d))
        assertEquals(secret.identity(), back.secret!!.identity())
    }

    @Test fun formStringFormIsRedacted() {
        assertFalse(ProxyForm(ProxyKind.SOCKS5, "secret-host", "1", username = "u", password = "hunter2").toString().contains("hunter2"))
    }
}
