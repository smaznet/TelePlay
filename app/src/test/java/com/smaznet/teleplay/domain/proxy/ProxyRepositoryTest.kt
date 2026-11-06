package com.smaznet.teleplay.domain.proxy

import com.smaznet.teleplay.data.proxy.SecretProxyStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyRepositoryTest {
    @Test fun addDedupesOnTheDataModelKey() {
        val r = newRepo()
        assertTrue(r.add(socksDraft()) is AddResult.Added)
        assertTrue(r.add(socksDraft(server = "1.2.3.4")) is AddResult.Duplicate)
        assertTrue(r.add(socksDraft(server = "1.2.3.5")) is AddResult.Added)
        assertTrue(r.add(socksDraft(user = "u", pass = "p")) is AddResult.Added) // credentials are part of the key
        assertTrue(r.add(mtDraft()) is AddResult.Added)
        assertTrue(r.add(mtDraft(server = "MT.example.org")) is AddResult.Duplicate) // host is case-insensitive
        assertTrue(r.add(mtDraft(secret = MtSecret.Padded(KEY))) is AddResult.Added) // kind of secret is part of the key
        assertEquals(5, r.proxies.value.size)
    }

    @Test fun capsAt200() {
        val r = newRepo()
        repeat(200) { assertTrue(r.add(socksDraft(port = 1000 + it)) is AddResult.Added) }
        assertTrue(r.add(socksDraft(port = 5000)) is AddResult.LimitReached)
        assertEquals(200, r.proxies.value.size)
    }

    @Test fun invalidDraftsAreRefused() {
        val r = newRepo()
        assertTrue(r.add(socksDraft(port = 0)) is AddResult.Invalid)
        assertTrue(r.add(socksDraft(user = "u")) is AddResult.Invalid)
        assertTrue(r.add(socksDraft(name = "x".repeat(41))) is AddResult.Invalid)
        assertTrue(r.add(ProxyDraft(ProxyKind.MTPROTO, "a.b", 1)) is AddResult.Invalid)
        assertTrue(r.proxies.value.isEmpty())
    }

    @Test fun enablingNeedsASelection() {
        val r = newRepo()
        assertEquals(EnableResult.NeedsProxy, r.setEnabled(true))
        val p = (r.add(socksDraft()) as AddResult.Added).proxy
        assertEquals(EnableResult.NeedsProxy, r.setEnabled(true))
        r.select(p.id)
        assertEquals(EnableResult.Ok, r.setEnabled(true))
        assertTrue(r.state.value.active)
        assertEquals(p.id, (r.route() as ActiveRoute.Through).proxy.id)
        r.setEnabled(false)
        assertEquals(ActiveRoute.Direct, r.route())
    }

    @Test fun deletingTheSelectedProxySwitchesOffAndClearsSelection() {
        val r = newRepo()
        val a = (r.add(socksDraft()) as AddResult.Added).proxy
        val b = (r.add(socksDraft(server = "9.9.9.9")) as AddResult.Added).proxy
        r.select(a.id); r.setEnabled(true)
        r.delete(b.id)
        assertTrue(r.state.value.active)
        r.delete(a.id)
        assertEquals(ProxyState(false, null), r.state.value)
        assertEquals(ActiveRoute.Direct, r.route())
        assertTrue(r.proxies.value.isEmpty())
    }

    @Test fun routeChangesFireOnlyWhenTheRouteChanges() = runTest(UnconfinedTestDispatcher()) {
        val r = newRepo()
        var fired = 0
        val job = backgroundScope.launch { r.routeChanges.collect { fired++ } }
        val a = (r.add(socksDraft()) as AddResult.Added).proxy
        val b = (r.add(socksDraft(server = "9.9.9.9")) as AddResult.Added).proxy
        r.select(a.id); assertEquals("selecting while off changes nothing", 0, fired)
        r.setEnabled(true); assertEquals(1, fired)
        r.setEnabled(true); assertEquals("no-op", 1, fired)
        r.recordTest(a.id, ProxyTestResult.Working(5)); assertEquals("a test result is not a route change", 1, fired)
        r.select(b.id); assertEquals(2, fired)
        r.update(a.id, socksDraft(port = 2000)); assertEquals("editing an unused proxy", 2, fired)
        r.update(b.id, socksDraft(server = "9.9.9.9", port = 2000)); assertEquals("editing the active proxy", 3, fired)
        r.delete(a.id); assertEquals("deleting an unused proxy", 3, fired)
        r.delete(b.id); assertEquals(4, fired)
        job.cancel()
    }

    @Test fun recordingTestsNeverTouchSelectionOrSwitch() {
        val r = newRepo()
        val a = (r.add(socksDraft()) as AddResult.Added).proxy
        r.recordTest(a.id, ProxyTestResult.Working(12))
        assertEquals(ProxyState(false, null), r.state.value)
        assertEquals(ProxyTestResult.Working(12), r.get(a.id)!!.lastTest)
        assertEquals(1_000L, r.get(a.id)!!.lastTestAt)
    }

    @Test fun updateKeepsTheIdAndRefusesDuplicates() {
        val r = newRepo()
        val a = (r.add(socksDraft()) as AddResult.Added).proxy
        val b = (r.add(socksDraft(server = "9.9.9.9")) as AddResult.Added).proxy
        assertTrue(r.update(b.id, socksDraft()) is AddResult.Duplicate)
        val u = r.update(a.id, socksDraft(server = "5.5.5.5", name = "home")) as AddResult.Added
        assertEquals(a.id, u.proxy.id)
        assertEquals("home", r.get(a.id)!!.label)
    }

    @Test fun stateSurvivesARestart() {
        val store = MemoryProxyStore(); val prefs = MemoryProxyPrefs()
        val r = newRepo(store, prefs)
        val a = (r.add(mtDraft(secret = MtSecret.FakeTls(KEY, "cover.example"))) as AddResult.Added).proxy
        r.select(a.id); r.setEnabled(true)
        val again = newRepo(store, prefs)
        assertEquals(ProxyState(true, a.id), again.state.value)
        val back = again.get(a.id)!!
        assertEquals("cover.example", (back.secret as MtSecret.FakeTls).domain)
        assertArrayEquals(KEY, back.secret!!.key)
    }

    @Test fun anEnabledFlagWithNoSelectionIsIgnoredOnStart() {
        val r = newRepo(MemoryProxyStore(), MemoryProxyPrefs(enabled = true))
        assertFalse(r.state.value.active)
        assertEquals(ActiveRoute.Direct, r.route())
    }

    @Test fun savedProxiesSurviveTheDisconnectSnapshotRoundTrip() {
        val store = MemoryProxyStore()
        val r = newRepo(store)
        val a = (r.add(socksDraft(user = "u", pass = "p")) as AddResult.Added).proxy
        r.select(a.id)
        val snap = store.snapshot()
        store.blob = com.smaznet.teleplay.data.proxy.ProxyBlob.EMPTY // what a secret wipe does
        store.restore(snap)
        val again = newRepo(store, MemoryProxyPrefs())
        assertEquals(1, again.proxies.value.size)
        assertEquals("u", again.proxies.value[0].username)
        assertEquals(a.id, again.state.value.selectedId)
    }
}

class SecretProxyStoreCodecTest {
    @Test fun roundTripsEveryField() {
        val items = listOf(
            Proxy(1, ProxyKind.SOCKS5, "s.example.com", 1080, "work", "bob", "pw", null, ProxyTestResult.Working(33), 5),
            Proxy(2, ProxyKind.MTPROTO, "m.example.com", 443, null, null, null, MtSecret.Plain(KEY), ProxyTestResult.SecretRejected, 6),
            Proxy(3, ProxyKind.MTPROTO, "p.example.com", 8443, null, null, null, MtSecret.Padded(KEY)),
            Proxy(4, ProxyKind.MTPROTO, "e.example.com", 443, "tls", null, null, MtSecret.FakeTls(KEY, "cover.example.org"), ProxyTestResult.TimedOut, 7),
            Proxy(5, ProxyKind.SOCKS5, "n.example.com", 1, null, null, null, null, ProxyTestResult.NotReachable, 8),
        )
        val back = SecretProxyStore.decode(SecretProxyStore.encode(com.smaznet.teleplay.data.proxy.ProxyBlob(items, 4L, 6L)))
        assertEquals(4L, back.selectedId); assertEquals(6L, back.nextId)
        assertEquals(items.map { it.dedupeKey() }, back.items.map { it.dedupeKey() })
        assertEquals(items.map { it.lastTest }, back.items.map { it.lastTest })
        assertEquals(items.map { it.label }, back.items.map { it.label })
        assertEquals("bob", back.items[0].username); assertEquals("pw", back.items[0].password)
        assertEquals(ProxyTestResult.Working(33), back.items[0].lastTest)
    }

    @Test fun emptyPasswordIsDistinctFromNone() {
        val p = Proxy(1, ProxyKind.SOCKS5, "a.b", 1, null, "u", "", null)
        val back = SecretProxyStore.decode(SecretProxyStore.encode(com.smaznet.teleplay.data.proxy.ProxyBlob(listOf(p), null, 2))).items[0]
        assertEquals("", back.password)
        assertNull(back.copyNoPass())
    }

    private fun Proxy.copyNoPass(): String? = if (secret == null) null else "x"

    @Test fun garbageDecodesToAnError() {
        try { SecretProxyStore.decode(byteArrayOf(9, 9, 9)); fail() } catch (_: Exception) { }
    }
}
