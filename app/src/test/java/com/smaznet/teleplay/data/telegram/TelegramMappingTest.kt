package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.mtproto.tl.generated.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TelegramMappingTest {
    @Test
    fun token_isTrimmedAndValidated() {
        assertEquals("123456:ABCdef_ghi-JKLmnop", BotSignIn.normalize("  123456:ABCdef_ghi-JKLmnop \n"))
        assertNull(BotSignIn.normalize("not a token"))
        assertNull(BotSignIn.normalize("123456:"))
        assertNull(BotSignIn.normalize(":ABCdefghijklmnop"))
        assertNull(BotSignIn.normalize("12345 6:ABCdefghijklmnop"))
        assertNull(BotSignIn.normalize(""))
    }

    private fun doc(mime: String, attrs: List<T_DocumentAttribute>, thumbs: List<T_PhotoSize>? = null) = C_document(
        id = 9, access_hash = 8, file_reference = byteArrayOf(1, 2), date = 0, mime_type = mime, size = 1000,
        thumbs = thumbs, dc_id = 4, attributes = attrs,
    )

    private fun message(media: T_MessageMedia?, text: String = "", out: Boolean = false, peer: T_Peer = C_peerUser(5)) =
        C_message(out = out, id = 3, from_id = C_peerUser(5), peer_id = peer, date = 77, message = text, media = media)

    @Test
    fun videoDocument_mapsFields() {
        val d = doc(
            "video/mp4",
            listOf(C_documentAttributeVideo(duration = 61.5, w = 1280, h = 720), C_documentAttributeFilename("Movie.mp4")),
            listOf(C_photoSize("s", 90, 50, 100), C_photoSize("m", 320, 180, 100), C_photoStrippedSize("i", byteArrayOf(1))),
        )
        val m = message(C_messageMediaDocument(document = d)).toIncoming()!!
        assertEquals(3, m.messageId); assertEquals(5L, m.senderUserId)
        val v = m.video!!
        assertEquals("Movie.mp4", v.name); assertEquals(61, v.duration); assertEquals(1280, v.width)
        assertEquals("m", v.thumbSize); assertEquals(4, v.dcId); assertEquals(9L, v.documentId)
    }

    @Test
    fun videoByMimeOnly_isVideo_andOtherDocumentsAreNot() {
        assertNotNull(message(C_messageMediaDocument(document = doc("video/x-matroska", emptyList()))).toIncoming()!!.video)
        assertNull(message(C_messageMediaDocument(document = doc("application/pdf", emptyList()))).toIncoming())
    }

    @Test
    fun textOnly_isEmitted_emptyIsNot_outgoingAndNonPrivateIgnored() {
        assertEquals("hi", message(null, "hi").toIncoming()!!.text)
        assertNull(message(null, "").toIncoming())
        assertNull(message(null, "hi", out = true).toIncoming())
        assertNull(message(null, "hi", peer = C_peerChannel(5)).toIncoming())
    }
}
