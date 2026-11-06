package com.smaznet.teleplay.mtproto

import com.smaznet.teleplay.mtproto.tl.TlReader
import com.smaznet.teleplay.mtproto.tl.generated.C_documentAttributeFilename
import com.smaznet.teleplay.mtproto.tl.generated.C_documentAttributeVideo
import com.smaznet.teleplay.mtproto.tl.generated.C_document
import com.smaznet.teleplay.mtproto.tl.generated.C_message
import com.smaznet.teleplay.mtproto.tl.generated.C_messageMediaDocument
import com.smaznet.teleplay.mtproto.tl.generated.C_messageMediaPhoto
import com.smaznet.teleplay.mtproto.tl.generated.C_messageService
import com.smaznet.teleplay.mtproto.tl.generated.C_peerUser
import com.smaznet.teleplay.mtproto.tl.generated.C_updates_difference
import com.smaznet.teleplay.mtproto.tl.generated.T_updates_Difference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `difference.bin` is written by tools/tlgen/fixtures.py, an independent serializer that follows
 * schema.tl, so these tests cross-check the generated Kotlin deserializers.
 */
class TlFixtureTest {
    private fun load(): C_updates_difference {
        val bytes = javaClass.getResourceAsStream("/tl/difference.bin")!!.readBytes()
        val r = TlReader(bytes)
        val d = T_updates_Difference.read(r)
        assertEquals("fully consumed", 0, r.remaining)
        return d as C_updates_difference
    }

    @Test
    fun difference_parsesAllFourMessageKinds() {
        val d = load()
        assertEquals(4, d.new_messages.size)
        assertEquals(10, (d.state as com.smaznet.teleplay.mtproto.tl.generated.C_updates_state).pts)
        assertEquals(1, d.users.size)
    }

    @Test
    fun videoMessage_exposesDocumentFields() {
        val m = load().new_messages[0] as C_message
        assertEquals(1, m.id)
        assertEquals(777L, (m.peer_id as C_peerUser).user_id)
        assertEquals("send me this", m.message)
        val media = m.media as C_messageMediaDocument
        assertTrue(media.video)
        val doc = media.document as C_document
        assertEquals(1234567890123L, doc.id)
        assertEquals(987654321L, doc.access_hash)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), doc.file_reference)
        assertEquals("video/mp4", doc.mime_type)
        assertEquals(3L * 1024 * 1024 * 1024, doc.size)
        assertEquals(4, doc.dc_id)
        val video = doc.attributes.filterIsInstance<C_documentAttributeVideo>().single()
        assertEquals(5400.5, video.duration, 0.0)
        assertEquals(1920, video.w)
        assertEquals("movie.mp4", doc.attributes.filterIsInstance<C_documentAttributeFilename>().single().file_name)
        assertEquals(1, doc.thumbs!!.size)
    }

    @Test
    fun textMessage_hasNoMedia() {
        val m = load().new_messages[1] as C_message
        assertEquals("hello", m.message)
        assertNull(m.media)
    }

    @Test
    fun photoMessage_isAPhotoNotADocument() {
        val m = load().new_messages[2] as C_message
        assertTrue(m.media is C_messageMediaPhoto)
    }

    @Test
    fun serviceMessage_parses() {
        assertTrue(load().new_messages[3] is C_messageService)
    }
}
