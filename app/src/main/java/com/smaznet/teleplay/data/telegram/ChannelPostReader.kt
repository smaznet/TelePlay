package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.domain.update.ReleasePackage
import com.smaznet.teleplay.domain.update.UpdateError
import com.smaznet.teleplay.domain.update.UpdateException
import com.smaznet.teleplay.mtproto.Rpc
import com.smaznet.teleplay.mtproto.tl.TlFunction
import com.smaznet.teleplay.mtproto.tl.generated.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream

/** The part of [Rpc] the reader needs; lets tests answer calls from recorded TL bytes. */
interface ChannelRpc {
    suspend fun <R> invoke(fn: TlFunction<R>, dcId: Int? = null): R
}

class RpcChannelRpc(private val rpc: Rpc) : ChannelRpc {
    override suspend fun <R> invoke(fn: TlFunction<R>, dcId: Int?): R =
        if (dcId == null) rpc.invoke(fn) else rpc.invoke(fn, dcId)
}

/**
 * Read-only access to the public update channel for the update check (constitution v2.2.0): resolves
 * the channel by name, reads posts, and downloads the document of a release post. It sends nothing.
 * Never logs; failures are [UpdateException]s that carry no token, session or pairing data.
 */
class ChannelPostReader(
    private val rpc: ChannelRpc,
    private val username: String = CHANNEL_USERNAME,
) {
    private class DocHandle(val id: Long, val accessHash: Long, val fileReference: ByteArray, val dcId: Int)

    private val channelMutex = Mutex()
    private var channel: C_inputChannel? = null

    /** Text of post [postId]; [UpdateError.AnnouncementUnreadable] when it is missing or empty. */
    suspend fun readPostText(postId: Int): String = guard(UpdateError.AnnouncementUnreadable) {
        val m = post(postId) ?: throw UpdateException(UpdateError.AnnouncementUnreadable)
        m.message.ifBlank { throw UpdateException(UpdateError.AnnouncementUnreadable) }
    }

    /** The APK document of post [postId]; [UpdateError.PackageMissing] when there is none. */
    suspend fun readRelease(postId: Int): ReleasePackage = guard(UpdateError.PackageMissing) {
        val m = post(postId) ?: throw UpdateException(UpdateError.PackageMissing)
        val doc = (m.media as? C_messageMediaDocument)?.document as? C_document ?: throw UpdateException(UpdateError.PackageMissing)
        val name = doc.attributes.filterIsInstance<C_documentAttributeFilename>().firstOrNull()?.file_name.orEmpty()
        val apkLike = name.endsWith(".apk", ignoreCase = true) || doc.mime_type == APK_MIME || doc.mime_type == OCTET_STREAM
        if (!apkLike || doc.size < 1 || doc.size > ReleasePackage.MAX_SIZE_BYTES) throw UpdateException(UpdateError.PackageMissing)
        ReleasePackage(postId, name.ifEmpty { "update.apk" }, doc.size, DocHandle(doc.id, doc.access_hash, doc.file_reference, doc.dc_id))
    }

    /** Streams the document into [sink] in 512 KB parts; stops at the document size or a short part. */
    suspend fun download(release: ReleasePackage, sink: OutputStream, onProgress: (Long) -> Unit) = guard(UpdateError.DownloadFailed) {
        var handle = release.handle as? DocHandle ?: throw UpdateException(UpdateError.DownloadFailed)
        var offset = 0L
        while (offset < release.sizeBytes) {
            currentCoroutineContext().ensureActive()
            val part = try {
                part(handle, offset)
            } catch (e: TelegramError.FileReferenceExpired) {
                // The reference is only seconds old, so this is rare: re-read the post once and retry the part.
                handle = readRelease(release.postId).handle as DocHandle
                try { part(handle, offset) } catch (e2: TelegramError.FileReferenceExpired) { throw UpdateException(UpdateError.DownloadFailed, e2) }
            }
            if (part.isEmpty()) break
            sink.write(part)
            offset += part.size
            onProgress(offset)
            if (part.size < PART_SIZE) break
        }
    }

    private suspend fun part(h: DocHandle, offset: Long): ByteArray {
        val loc = C_inputDocumentFileLocation(h.id, h.accessHash, h.fileReference, "")
        return when (val r = rpc.invoke(F_upload_getFile(location = loc, offset = offset, limit = PART_SIZE), h.dcId)) {
            is C_upload_file -> r.bytes
            is C_upload_fileCdnRedirect -> throw UpdateException(UpdateError.DownloadFailed)
        }
    }

    private suspend fun post(postId: Int): C_message? {
        val ch = resolveChannel()
        val res = rpc.invoke(F_channels_getMessages(ch, listOf(C_inputMessageID(postId))))
        val list = when (res) {
            is C_messages_messages -> res.messages
            is C_messages_messagesSlice -> res.messages
            is C_messages_channelMessages -> res.messages
            is C_messages_messagesNotModified -> emptyList()
        }
        return list.asSequence().mapNotNull { it as? C_message }.firstOrNull { it.id == postId }
    }

    private suspend fun resolveChannel(): C_inputChannel = channelMutex.withLock {
        channel?.let { return it }
        val res = rpc.invoke(F_contacts_resolveUsername(username)) as C_contacts_resolvedPeer
        val ch = res.chats.asSequence().mapNotNull { it as? C_channel }
            .firstOrNull { it.broadcast && it.username.equals(username, ignoreCase = true) }
        val hash = ch?.access_hash ?: throw UpdateException(UpdateError.AnnouncementUnreadable)
        C_inputChannel(ch.id, hash).also { channel = it }
    }

    private suspend fun <T> guard(missing: UpdateError, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: UpdateException) {
        throw e
    } catch (e: Exception) {
        throw map(e, missing)
    }

    companion object {
        const val CHANNEL_USERNAME = "TelePlayUpdate"
        const val PART_SIZE = 524_288
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val OCTET_STREAM = "application/octet-stream"

        /** Maps Telegram failures to the update errors of contracts/telegram-api-additions.md. */
        fun map(e: Throwable, fallback: UpdateError): UpdateException = when (e) {
            is UpdateException -> e
            is TelegramError.Offline -> UpdateException(UpdateError.Offline, e)
            is TelegramError.AuthKeyLost, is TelegramError.TokenRejected -> UpdateException(UpdateError.NotConnected, e)
            is TelegramError.RateLimited, is TelegramError.FileUnavailable, is TelegramError.FileReferenceExpired,
            is TelegramError.Unsupported -> UpdateException(UpdateError.DownloadFailed, e)
            is TelegramError.Unknown -> UpdateException(
                when (e.rpcMessage) {
                    "USERNAME_NOT_OCCUPIED", "USERNAME_INVALID", "CHANNEL_PRIVATE", "CHANNEL_INVALID" -> UpdateError.AnnouncementUnreadable
                    else -> fallback
                },
                e,
            )
            else -> UpdateException(fallback, e)
        }
    }
}
