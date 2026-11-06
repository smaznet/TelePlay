package com.smaznet.teleplay.domain.update

import java.io.OutputStream

/**
 * Read-only access to the public TelePlay update channel through the connected bot. Every method
 * throws [UpdateException] only. Implementations never put the token, session or pairing code into
 * exception messages.
 */
interface UpdateFeed {
    /** Text of the announcement post (post 2 of the update channel). */
    suspend fun readAnnouncementText(): String

    /** The single installable document of post [postId]; [UpdateError.PackageMissing] when there is none. */
    suspend fun readRelease(postId: Int): ReleasePackage

    /** Streams [release] into [sink] in order; [onProgress] gets the cumulative byte count. */
    suspend fun download(release: ReleasePackage, sink: OutputStream, onProgress: (Long) -> Unit)
}
