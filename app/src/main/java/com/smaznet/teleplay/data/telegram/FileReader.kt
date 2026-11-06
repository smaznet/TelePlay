package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.domain.IncomingDocument
import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.domain.Video
import com.smaznet.teleplay.mtproto.Rpc
import com.smaznet.teleplay.mtproto.tl.generated.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads file parts with `upload.getFile`. Handles FILE_MIGRATE_X (inside [Rpc]) and
 * FILE_REFERENCE_EXPIRED (refresh through [refresh], then one retry). Refreshed references are kept
 * in memory so later chunks of the same video use them.
 */
class FileReader(
    private val rpc: Rpc,
    private val refresh: suspend (Video) -> ByteArray,
) {
    private val latestRefs = ConcurrentHashMap<Long, ByteArray>()
    private val refreshMutex = Mutex()

    suspend fun readChunk(video: Video, chunkIndex: Int): ByteArray =
        fetch(video, "", chunkIndex.toLong() * CHUNK_SIZE, CHUNK_SIZE)

    /** Whole thumbnail bytes (thumbnails are far smaller than a chunk). */
    suspend fun readThumbnail(video: Video): ByteArray? {
        val size = video.thumbSize ?: return null
        val out = java.io.ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            val part = fetch(video, size, offset, THUMB_PART)
            out.write(part)
            if (part.size < THUMB_PART || out.size() > MAX_THUMB) break
            offset += THUMB_PART
        }
        return out.toByteArray()
    }

    /**
     * Whole small document (the playlist backup). [refresh] returns a fresh file reference after
     * FILE_REFERENCE_EXPIRED (one retry).
     */
    suspend fun readDocument(doc: IncomingDocument, maxBytes: Int, refresh: suspend () -> ByteArray): ByteArray {
        if (doc.size > maxBytes) throw TelegramError.Unsupported("too large")
        var ref = doc.fileReference
        var retried = false
        val out = java.io.ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            try {
                val loc = C_inputDocumentFileLocation(doc.id, doc.accessHash, ref, "")
                val part = when (val r = rpc.invoke(F_upload_getFile(location = loc, offset = offset, limit = CHUNK_SIZE), doc.dcId)) {
                    is C_upload_file -> r.bytes
                    is C_upload_fileCdnRedirect -> throw TelegramError.Unsupported("cdn redirect")
                }
                out.write(part)
                if (out.size() > maxBytes) throw TelegramError.Unsupported("too large")
                if (part.size < CHUNK_SIZE) return out.toByteArray()
                offset += CHUNK_SIZE
            } catch (e: TelegramError.FileReferenceExpired) {
                if (retried) throw TelegramError.FileUnavailable()
                retried = true
                ref = refresh()
                out.reset(); offset = 0
            }
        }
    }

    private suspend fun fetch(video: Video, thumbSize: String, offset: Long, limit: Int): ByteArray {
        var retried = false
        while (true) {
            val ref = latestRefs[video.id] ?: video.fileReference
            try {
                val loc = C_inputDocumentFileLocation(video.documentId, video.accessHash, ref, thumbSize)
                return when (val r = rpc.invoke(F_upload_getFile(location = loc, offset = offset, limit = limit), video.dcId)) {
                    is C_upload_file -> r.bytes
                    is C_upload_fileCdnRedirect -> throw TelegramError.Unsupported("cdn redirect")
                }
            } catch (e: TelegramError.FileReferenceExpired) {
                if (retried) throw TelegramError.FileUnavailable()
                retried = true
                refreshMutex.withLock {
                    // another caller may have refreshed while we waited
                    if ((latestRefs[video.id] ?: video.fileReference) === ref) {
                        latestRefs[video.id] = refresh(video)
                    }
                }
            }
        }
    }

    companion object {
        const val CHUNK_SIZE = 524_288
        private const val THUMB_PART = 131_072
        private const val MAX_THUMB = 2 * 1024 * 1024
    }
}
