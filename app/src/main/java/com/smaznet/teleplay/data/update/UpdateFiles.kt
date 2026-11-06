package com.smaznet.teleplay.data.update

import com.smaznet.teleplay.domain.update.PartFile
import com.smaznet.teleplay.domain.update.ReleasePackage
import com.smaznet.teleplay.domain.update.UpdateError
import com.smaznet.teleplay.domain.update.UpdateException
import com.smaznet.teleplay.domain.update.UpdateFilePort
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The transient download in [dir] (the app's cache, `updates/`). At most one package lives here:
 * `teleplay-<code>.apk.part` while downloading, `teleplay-<code>.apk` once verified. Everything is
 * removed on [prepare], on failure and on [deleteAll].
 *
 * [archiveInfo] returns (package name, version code) of an APK file or null when it cannot be read.
 */
class UpdateFiles(
    private val dir: File,
    private val freeSpace: (File) -> Long,
    private val archiveInfo: (File) -> Pair<String, Int>?,
    private val expectedPackage: String,
    private val installedVersion: () -> Int,
) : UpdateFilePort {

    override fun prepare() {
        deleteAll()
        dir.mkdirs()
    }

    override fun neededSpace(sizeBytes: Long) = sizeBytes + SAFETY_MARGIN_BYTES

    override fun hasSpaceFor(sizeBytes: Long): Boolean {
        dir.mkdirs()
        return freeSpace(dir) >= neededSpace(sizeBytes)
    }

    override fun openPart(versionCode: Int): PartFile {
        dir.mkdirs()
        val f = File(dir, "teleplay-$versionCode.apk.part")
        return PartFile(f, FileOutputStream(f))
    }

    override fun finalize(part: File, release: ReleasePackage, announcedCode: Int): File {
        fun invalid(): Nothing {
            discard(part)
            throw UpdateException(UpdateError.PackageInvalid)
        }
        if (!part.isFile || part.length() != release.sizeBytes) invalid()
        val magic = ByteArray(4)
        val read = FileInputStream(part).use { it.read(magic) }
        if (read != 4 || !magic.contentEquals(ZIP_MAGIC)) invalid()
        val info = archiveInfo(part) ?: invalid()
        if (info.first != expectedPackage || info.second != announcedCode || info.second <= installedVersion()) invalid()
        val done = File(dir, "teleplay-$announcedCode.apk")
        done.delete()
        if (!part.renameTo(done)) invalid()
        return done
    }

    override fun discard(part: File) {
        part.delete()
    }

    override fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val SAFETY_MARGIN_BYTES = 25L * 1024 * 1024
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}
