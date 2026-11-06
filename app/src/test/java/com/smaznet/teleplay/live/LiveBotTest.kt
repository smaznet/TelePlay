package com.smaznet.teleplay.live

import com.smaznet.teleplay.BuildConfig
import com.smaznet.teleplay.data.telegram.*
import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.domain.update.parse
import com.smaznet.teleplay.mtproto.session.DcSessionData
import com.smaznet.teleplay.mtproto.transport.TcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Opt-in: set TELEPLAY_LIVE_TOKEN (and optionally TELEPLAY_LIVE_MESSAGE_ID for a message the bot
 * received in a private chat). Skipped otherwise. Never prints the token.
 */
class LiveBotTest {
    private class Mem : SessionStore, FileReferenceStore {
        val s = HashMap<Int, DcSessionData>(); var home: Int? = null; var tok: String? = null; var st: UpdateState? = null
        override fun load(dcId: Int) = s[dcId]
        override fun save(data: DcSessionData) { s[data.dcId] = data }
        override fun clearAll() { s.clear(); home = null }
        override fun clear(dcId: Int) { s.remove(dcId) }
        override fun loadHomeDc() = home
        override fun saveHomeDc(dcId: Int) { home = dcId }
        override fun update(videoId: Long, fileReference: ByteArray) {}
    }

    private lateinit var scope: CoroutineScope
    private val token = System.getenv("TELEPLAY_LIVE_TOKEN")

    @Before fun setUp() {
        assumeTrue("TELEPLAY_LIVE_TOKEN not set", !token.isNullOrBlank())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After fun tearDown() { if (::scope.isInitialized) scope.cancel() }

    private fun gateway(mem: Mem): MtprotoGateway {
        val tokens = object : TokenStore {
            override fun load() = mem.tok
            override fun save(token: String) { mem.tok = token }
            override fun clear() { mem.tok = null }
        }
        val states = object : UpdateStateStore {
            override fun load() = mem.st
            override fun save(state: UpdateState) { mem.st = state }
            override fun clear() { mem.st = null }
        }
        return MtprotoGateway(
            GatewayConfig(BuildConfig.TELEGRAM_API_ID, BuildConfig.TELEGRAM_API_HASH), TcpTransport.factory, scope,
            mem, tokens, states, mem, pollIntervalMs = 3_600_000,
        )
    }

    @Test fun signIn_succeeds_andGetStateWorks() = runBlocking {
        val mem = Mem()
        val gw = gateway(mem)
        val info = gw.signInWithBotToken(token!!).getOrThrow()
        assertTrue(info.botUserId > 0)
        // the update loop performs updates.getState on first run
        val deadline = System.currentTimeMillis() + 30_000
        while (mem.st == null && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(200)
        assertNotNull("updates.getState result persisted", mem.st)
        gw.signOutAndWipe()
    }

    @Test fun invalidToken_isRejected() = runBlocking {
        val gw = gateway(Mem())
        val r = gw.signInWithBotToken("123456:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertTrue(r.exceptionOrNull() is TelegramError.TokenRejected)
    }

    @Test fun getMessages_forKnownMessageId() = runBlocking {
        val id = System.getenv("TELEPLAY_LIVE_MESSAGE_ID")?.toIntOrNull()
        assumeTrue("TELEPLAY_LIVE_MESSAGE_ID not set", id != null)
        val gw = gateway(Mem())
        gw.signInWithBotToken(token!!).getOrThrow()
        val rpcField = MtprotoGateway::class.java.getDeclaredField("rpc").apply { isAccessible = true }
        val rpc = rpcField.get(gw) as com.smaznet.teleplay.mtproto.Rpc
        val res = rpc.invoke(
            com.smaznet.teleplay.mtproto.tl.generated.F_messages_getMessages(
                listOf(com.smaznet.teleplay.mtproto.tl.generated.C_inputMessageID(id!!)),
            ),
        )
        assertNotNull(res)
        gw.signOutAndWipe()
    }

    /** Feature 004 against the real update channel: needs the channel to exist and post 3 to hold an APK. */
    @Test fun updateFeed_readsAnnouncementAndDownloadsRelease() = runBlocking {
        val gw = gateway(Mem())
        gw.signInWithBotToken(token!!).getOrThrow()
        val text = gw.readAnnouncementText()
        val a = com.smaznet.teleplay.domain.update.Announcement.parse(text)
        assertNotNull("announcement parses: $text", a)
        val release = gw.readRelease(a!!.releasePostId)
        assertTrue(release.sizeBytes > 0)
        val out = java.io.ByteArrayOutputStream()
        gw.download(release, out) {}
        assertEquals(release.sizeBytes, out.size().toLong())
        assertEquals("PK", String(out.toByteArray(), 0, 2))
        gw.signOutAndWipe()
    }
}

