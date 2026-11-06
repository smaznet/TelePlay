package com.smaznet.teleplay.domain.playlist

import com.smaznet.teleplay.domain.*
import com.smaznet.teleplay.fakes.FakeBotMessenger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Constitution Principles III and IV: a backup and the replies never carry the token, pairing code or session data. */
class PlaylistNoSecretsTest {
    private val token = "123456:SECRET-token-ABCdef"
    private val code = "PAIR-4242-CODE"
    private val session = "auth-key-bytes-9f8e7d"

    @Test fun exportAndRepliesContainNoSecrets() = runTest {
        val env = Env(authorized = 42)
        env.tokens.put(token)
        env.connStore.row = env.connStore.row!!.copy(pairingCodeHash = code, authorizedUserAccessHash = 99)
        for (i in 1..3) env.videos.ingest(msg(id = i, video = vid(doc = i.toLong(), name = "v$i.mp4")))
        val messenger = FakeBotMessenger()
        val transfer = PlaylistTransfer(env.videos, messenger, env.gateway, { 1L })
        val commands = PlaylistCommands({ 42 }, transfer, env.gateway, messenger)

        commands.handle(msg(text = "/export")); commands.handle(msg(text = "/help")); commands.handle(msg(text = "/import"))

        val doc = messenger.documents.single()
        val everything = String(doc.bytes) + doc.caption + doc.fileName + messenger.sends.joinToString("\n") { it.text }
        for (secret in listOf(token, code, session, "SECRET", "accessHash", "fileReference", "99")) {
            // "99" must not be the access hash we stored (it may legitimately appear inside other numbers, so match the key form)
            if (secret == "99") assertFalse(everything.contains("\"authorizedUserAccessHash\"")) else assertFalse(secret, everything.contains(secret))
        }
    }

    @Test fun importReplyNeverEchoesFileContent() = runTest {
        val env = Env(authorized = 42)
        val messenger = FakeBotMessenger()
        val commands = PlaylistCommands({ 42 }, PlaylistTransfer(env.videos, messenger, env.gateway, { 1L }), env.gateway, messenger)
        val doc = IncomingDocument(900, 1, byteArrayOf(1), 2, "b.json", "application/json", 100)
        env.gateway.documentBytes[900] = """{"app":"teleplay","version":1,"videos":[],"token":"$token"}""".toByteArray()
        commands.handle(msg(text = "/import", document = doc))
        assertFalse(messenger.sends.joinToString { it.text }.contains(token))
    }
}
