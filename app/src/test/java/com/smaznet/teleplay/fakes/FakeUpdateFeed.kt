package com.smaznet.teleplay.fakes

import com.smaznet.teleplay.domain.update.ReleasePackage
import com.smaznet.teleplay.domain.update.UpdateException
import com.smaznet.teleplay.domain.update.UpdateFeed
import java.io.OutputStream

class FakeUpdateFeed : UpdateFeed {
    var announcementText: String = "latest:\ncode: 3\nhttps://t.me/TelePlayUpdate/3"
    var release: ReleasePackage = ReleasePackage(3, "app-release.apk", 4)
    var bytes: ByteArray = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    var announcementFailure: UpdateException? = null
    var releaseFailure: UpdateException? = null
    var downloadFailure: UpdateException? = null

    /** Called after every [partSize] bytes written; tests use it to cancel or fail mid-stream. */
    var partSize = 2
    var betweenParts: suspend (written: Int) -> Unit = {}

    var announcementReads = 0
    var releaseReads = 0
    var downloads = 0

    override suspend fun readAnnouncementText(): String {
        announcementReads++
        announcementFailure?.let { throw it }
        return announcementText
    }

    override suspend fun readRelease(postId: Int): ReleasePackage {
        releaseReads++
        releaseFailure?.let { throw it }
        return release
    }

    override suspend fun download(release: ReleasePackage, sink: OutputStream, onProgress: (Long) -> Unit) {
        downloads++
        downloadFailure?.let { throw it }
        var written = 0
        while (written < bytes.size) {
            val n = minOf(partSize, bytes.size - written)
            sink.write(bytes, written, n)
            written += n
            onProgress(written.toLong())
            betweenParts(written)
        }
    }
}
