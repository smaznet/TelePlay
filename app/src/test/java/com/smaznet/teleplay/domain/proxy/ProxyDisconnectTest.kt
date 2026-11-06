package com.smaznet.teleplay.domain.proxy

import com.smaznet.teleplay.data.proxy.ProxyBlob
import com.smaznet.teleplay.domain.DisconnectUseCase
import com.smaznet.teleplay.domain.ConnectionState
import com.smaznet.teleplay.domain.Env
import com.smaznet.teleplay.domain.msg
import com.smaznet.teleplay.domain.vid
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Saved proxies survive disconnect (FR-004, research R13); everything else is erased as before. */
class ProxyDisconnectTest {
    @Test fun proxiesSurviveWhileTokenHistoryAndCacheAreErased() = runTest {
        val e = Env(authorized = 42)
        e.connections.completePairing(42)
        e.videos.ingest(msg(video = vid()))
        val store = MemoryProxyStore(); val prefs = MemoryProxyPrefs()
        val repo = newRepo(store, prefs)
        val a = (repo.add(socksDraft(user = "u", pass = "p")) as AddResult.Added).proxy
        repo.add(mtDraft(secret = MtSecret.FakeTls(KEY, "cover.example")))
        repo.select(a.id); repo.setEnabled(true)
        var cache = false
        // The same dance ServiceLocator does around SecretStore.wipe(), which deletes every secret and the Keystore key.
        val wipeSecrets = { val keep = store.snapshot(); store.blob = ProxyBlob.EMPTY; store.restore(keep) }
        DisconnectUseCase(e.connections, e.videos, gatewayWiper = { }, sessionWiper = { wipeSecrets() }, cacheWiper = { cache = true }).invoke()

        assertNull(e.tokens.token)
        assertTrue(e.videoStore.all.isEmpty())
        assertTrue(cache)
        assertEquals(ConnectionState.SIGNED_OUT, e.connections.state.value)

        val after = newRepo(store, prefs)
        assertEquals(2, after.proxies.value.size)
        assertEquals(ProxyState(true, a.id), after.state.value)
        assertEquals("p", after.get(a.id)!!.password)
        assertEquals(ActiveRoute.Through::class, after.route()::class)
    }

    @Test fun onlyDeletingByHandRemovesAProxy() {
        val repo = newRepo()
        val a = (repo.add(socksDraft()) as AddResult.Added).proxy
        repo.add(socksDraft(server = "5.5.5.5"))
        repo.delete(a.id)
        assertEquals(1, repo.proxies.value.size)
    }
}
