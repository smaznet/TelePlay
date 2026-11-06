package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.domain.IncomingDocument
import com.smaznet.teleplay.domain.IncomingMessage
import com.smaznet.teleplay.domain.IncomingVideo
import com.smaznet.teleplay.mtproto.tl.generated.*

/** Maps a TL message to an [IncomingMessage]; null for anything but a received private text/video message. */
internal fun T_Message.toIncoming(userHashes: Map<Long, Long> = emptyMap()): IncomingMessage? {
    val m = this as? C_message ?: return null
    if (m.out) return null
    val peer = m.peer_id as? C_peerUser ?: return null
    val sender = (m.from_id as? C_peerUser)?.user_id ?: peer.user_id
    val rawDoc = (m.media as? C_messageMediaDocument)?.document
    val video = rawDoc?.let { documentToVideo(it) }
    val text = m.message.takeIf { it.isNotEmpty() }
    // A non-video document is kept only when its caption is a command (feature 005, Principle III).
    val document = if (video == null && text?.startsWith("/") == true) rawDoc?.let { documentToIncomingDocument(it) } else null
    if (video == null && text == null) return null
    val replyTo = (m.reply_to as? C_messageReplyHeader)?.reply_to_msg_id
    // date is Telegram's epoch seconds
    return IncomingMessage(m.id, sender, m.date.toLong(), text, video, userHashes[sender], replyTo, document)
}

internal fun documentToIncomingDocument(d: T_Document): IncomingDocument? {
    val doc = d as? C_document ?: return null
    val name = doc.attributes.firstNotNullOfOrNull { it as? C_documentAttributeFilename }?.file_name
    return IncomingDocument(doc.id, doc.access_hash, doc.file_reference, doc.dc_id, name, doc.mime_type, doc.size)
}

internal fun documentToVideo(d: T_Document): IncomingVideo? {
    val doc = d as? C_document ?: return null
    val va = doc.attributes.firstNotNullOfOrNull { it as? C_documentAttributeVideo }
    if (va == null && !doc.mime_type.startsWith("video/")) return null
    val name = doc.attributes.firstNotNullOfOrNull { it as? C_documentAttributeFilename }?.file_name
    return IncomingVideo(
        documentId = doc.id,
        accessHash = doc.access_hash,
        fileReference = doc.file_reference,
        dcId = doc.dc_id,
        name = name,
        mime = doc.mime_type,
        size = doc.size,
        duration = va?.duration?.toInt() ?: 0,
        width = va?.w ?: 0,
        height = va?.h ?: 0,
        thumbSize = largestThumbType(doc.thumbs),
    )
}

/** Type char of the largest downloadable PhotoSize. */
internal fun largestThumbType(thumbs: List<T_PhotoSize>?): String? =
    thumbs?.mapNotNull {
        when (it) {
            is C_photoSize -> it.type to it.w.toLong() * it.h
            is C_photoSizeProgressive -> it.type to it.w.toLong() * it.h
            else -> null
        }
    }?.maxByOrNull { it.second }?.first
