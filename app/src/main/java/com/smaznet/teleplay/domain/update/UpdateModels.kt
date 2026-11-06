package com.smaznet.teleplay.domain.update

import java.io.File

/** Parsed update announcement: [versionCode] is > 0 (1 to 9 digits), [releasePostId] is > 0. */
data class Announcement(val versionCode: Int, val releasePostId: Int) {
    companion object
}

/**
 * The installable file attached to the release post. [sizeBytes] is within 1 .. [MAX_SIZE_BYTES].
 * [handle] is the opaque document reference of the data layer; it is never persisted or logged and is
 * left out of [toString].
 */
class ReleasePackage(val postId: Int, val fileName: String, val sizeBytes: Long, val handle: Any? = null) {
    override fun toString() = "ReleasePackage(postId=$postId, fileName=$fileName, sizeBytes=$sizeBytes)"

    companion object {
        const val MAX_SIZE_BYTES = 64L * 1024 * 1024
    }
}

enum class UpdateError {
    Offline, NotConnected, AnnouncementUnreadable, PackageMissing, StorageLow, DownloadFailed, PackageInvalid, InstallNotAllowed,
}

/** A failure to show: [neededBytes] is only set for [UpdateError.StorageLow]. */
data class UpdateFailure(val error: UpdateError, val neededBytes: Long = 0)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val versionCode: Int) : UpdateState
    data class Available(val announcement: Announcement, val release: ReleasePackage) : UpdateState
    data class Downloading(val done: Long, val total: Long) : UpdateState
    data object Verifying : UpdateState
    data class ReadyToInstall(val file: File) : UpdateState
    data class Failed(val failure: UpdateFailure) : UpdateState
}

class UpdateException(val failure: UpdateFailure, cause: Throwable? = null) : Exception(failure.error.name, cause) {
    constructor(error: UpdateError, cause: Throwable? = null) : this(UpdateFailure(error), cause)
}
