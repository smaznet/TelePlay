package com.smaznet.teleplay.data

import com.smaznet.teleplay.data.telegram.BotMessenger
import com.smaznet.teleplay.data.telegram.PushedUpdates
import com.smaznet.teleplay.data.telegram.RpcBotMessenger
import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.domain.remote.RemoteFailure
import com.smaznet.teleplay.mtproto.tl.TlWriter
import com.smaznet.teleplay.mtproto.tl.generated.*
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are written by tools/tlgen/fixtures.py (independent serializer following schema.tl). */
class PushedUpdatesTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/tl/$name")!!.readBytes()

    @Test fun updateShortCallbackIsExtracted() {
        val r = PushedUpdates.parse(res("push_short_callback.bin"))!!
        val q = r.callbacks.single()
        assertEquals(9001L, q.queryId)
        assertEquals(777L, q.userId)
        assertEquals(42, q.messageId)
        assertEquals("v1:AbCd1234:pp", q.data)
        assertTrue(r.userHashes.isEmpty())
    }

    @Test fun updatesCarryUserHashesAndSkipMinUsers() {
        val r = PushedUpdates.parse(res("push_updates.bin"))!!
        assertEquals(1, r.callbacks.size)
        assertEquals("v1:AbCd1234:nx", r.callbacks[0].data)
        assertEquals(mapOf(777L to 5L), r.userHashes)
    }

    @Test fun unrelatedUpdateYieldsNoCallbacks() {
        val r = PushedUpdates.parse(res("push_unrelated.bin"))!!
        assertTrue(r.callbacks.isEmpty())
    }

    @Test fun garbageAndTruncatedBytesDoNotThrow() {
        assertNull(PushedUpdates.parse(ByteArray(0)))
        assertNull(PushedUpdates.parse(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val full = res("push_updates.bin")
        assertNull(PushedUpdates.parse(full.copyOf(full.size / 2)))
    }

    // --- TL round trips of the four new methods --------------------------------------------

    private fun bytes(f: com.smaznet.teleplay.mtproto.tl.TlObject) = TlWriter().also { f.serialize(it) }.toByteArray()

    private val peer = C_inputPeerUser(777, 5)
    private val markup = C_replyInlineMarkup(
        rows = listOf(
            C_keyboardInlineButtonRow(
                listOf(C_keyboardInlineButton(text = "⏯", type = C_inlineButtonTypeCallback(data = "v1:AbCd1234:pp".toByteArray()))),
            ),
        ),
    )

    @Test fun sendMessageMatchesFixture() =
        assertArrayEquals(res("req_send_message.bin"), bytes(F_messages_sendMessage(peer = peer, message = "hi", random_id = 42, reply_markup = markup)))

    @Test fun editMessageMatchesFixture() =
        assertArrayEquals(res("req_edit_message.bin"), bytes(F_messages_editMessage(peer = peer, id = 7, message = "bye", reply_markup = markup)))

    @Test fun pinMessageMatchesFixture() =
        assertArrayEquals(res("req_pin_message.bin"), bytes(F_messages_updatePinnedMessage(silent = true, peer = peer, id = 7)))

    @Test fun answerCallbackMatchesFixture() =
        assertArrayEquals(res("req_answer_callback.bin"), bytes(F_messages_setBotCallbackAnswer(query_id = 9001, message = "No next video", cache_time = 0)))

    // --- error mapping -----------------------------------------------------------------------

    @Test fun errorClassification() {
        fun c(m: String) = RpcBotMessenger.classify(TelegramError.Unknown(400, m))
        assertEquals(RemoteFailure.GONE, c("MESSAGE_ID_INVALID"))
        assertEquals(RemoteFailure.BLOCKED, c("USER_IS_BLOCKED"))
        assertEquals(RemoteFailure.BLOCKED, c("PEER_ID_INVALID"))
        assertEquals(RemoteFailure.BLOCKED, c("INPUT_USER_DEACTIVATED"))
        assertEquals(RemoteFailure.FLOOD, c("FLOOD_WAIT_30"))
        assertEquals(RemoteFailure.OTHER, c("SOMETHING_ELSE"))
        assertEquals(RemoteFailure.OFFLINE, RpcBotMessenger.classify(TelegramError.Offline()))
        assertEquals(RemoteFailure.FLOOD, RpcBotMessenger.classify(TelegramError.RateLimited(10)))
    }
}
