package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.domain.IncomingMessage
import com.smaznet.teleplay.mtproto.tl.TlObject
import com.smaznet.teleplay.mtproto.tl.TlWriter
import com.smaznet.teleplay.mtproto.tl.generated.*
import org.junit.Assert.*
import org.junit.Test

/** TL fixtures come from tools/tlgen/fixtures.py (independent serializer); plus the inbound mapping for feature 005. */
class PlaylistTlTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/tl/$name")!!.readBytes()
    private fun bytes(f: TlObject) = TlWriter().also { f.serialize(it) }.toByteArray()

    @Test fun saveFilePartMatchesFixture() =
        assertArrayEquals(res("req_save_file_part.bin"), bytes(F_upload_saveFilePart(123456789, 2, "hello part".toByteArray())))

    @Test fun sendMediaMatchesFixture() {
        val name = "teleplay-backup-20260803-135000.json"
        val media = C_inputMediaUploadedDocument(
            force_file = true, file = C_inputFile(123456789, 1, name, ""), mime_type = "application/json",
            attributes = listOf(C_documentAttributeFilename(name)),
        )
        assertArrayEquals(
            res("req_send_media.bin"),
            bytes(F_messages_sendMedia(peer = C_inputPeerUser(777, 5), media = media, message = "Playlist backup: 2 videos", random_id = 42)),
        )
    }

    @Test fun setBotCommandsMatchesFixture() =
        assertArrayEquals(
            res("req_set_bot_commands.bin"),
            bytes(
                F_bots_setBotCommands(
                    C_botCommandScopeDefault(), "",
                    listOf(
                        C_botCommand(command = "export", description = "Save my playlist as a backup file"),
                        C_botCommand(command = "help", description = "How to back up and restore"),
                    ),
                ),
            ),
        )

    // --- inbound mapping ---------------------------------------------------------------------

    private fun jsonDoc() = C_document(
        id = 9, access_hash = 8, file_reference = byteArrayOf(1, 2), date = 0, mime_type = "application/json", size = 321,
        dc_id = 4, attributes = listOf(C_documentAttributeFilename("teleplay-backup.json")),
    )

    private fun message(text: String, media: T_MessageMedia? = null, replyTo: Int? = null, out: Boolean = false): IncomingMessage? =
        C_message(
            out = out, id = 3, from_id = C_peerUser(5), peer_id = C_peerUser(5), date = 77, message = text, media = media,
            reply_to = replyTo?.let { C_messageReplyHeader(reply_to_msg_id = it) },
        ).toIncoming()

    @Test fun documentWithCommandCaptionIsKept() {
        val m = message("/import", C_messageMediaDocument(document = jsonDoc()))!!
        val d = m.document!!
        assertEquals(9L, d.id); assertEquals(4, d.dcId); assertEquals("teleplay-backup.json", d.name); assertEquals(321L, d.size)
        assertNull(m.video)
    }

    @Test fun documentWithOtherOrNoCaptionIsDropped() {
        assertNull(message("", C_messageMediaDocument(document = jsonDoc())))
        val withText = message("my notes", C_messageMediaDocument(document = jsonDoc()))
        assertNull(withText!!.document) // the text is kept as before, the file is not
    }

    @Test fun replyIdIsMapped() {
        assertEquals(60, message("/import", replyTo = 60)!!.replyToMessageId)
        assertNull(message("/import")!!.replyToMessageId)
    }

    @Test fun videoDocumentsStillMapAsVideos() {
        val video = C_document(
            id = 1, access_hash = 2, file_reference = byteArrayOf(1), date = 0, mime_type = "video/mp4", size = 10, dc_id = 2,
            attributes = listOf(C_documentAttributeVideo(duration = 5.0, w = 10, h = 10)),
        )
        val m = message("/import", C_messageMediaDocument(document = video))!!
        assertNotNull(m.video); assertNull(m.document)
    }
}
