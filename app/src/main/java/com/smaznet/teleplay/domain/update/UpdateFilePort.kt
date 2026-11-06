package com.smaznet.teleplay.domain.update

import java.io.File
import java.io.OutputStream

/** The temporary download file, as the updater sees it. Implemented in data/update/UpdateFiles. */
interface UpdateFilePort {
    /** Removes every leftover from earlier attempts. */
    fun prepare()

    /** True when there is room for a package of [sizeBytes] plus a safety margin. */
    fun hasSpaceFor(sizeBytes: Long): Boolean

    /** Bytes of free space needed for [sizeBytes], for the "not enough space" message. */
    fun neededSpace(sizeBytes: Long): Long

    fun openPart(versionCode: Int): PartFile

    /** Checks the finished [part] and turns it into the installable file, or deletes it and throws [UpdateError.PackageInvalid]. */
    fun finalize(part: File, release: ReleasePackage, announcedCode: Int): File

    fun discard(part: File)

    fun deleteAll()
}

class PartFile(val file: File, val stream: OutputStream)
