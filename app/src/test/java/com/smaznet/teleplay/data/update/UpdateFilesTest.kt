package com.smaznet.teleplay.data.update

import com.smaznet.teleplay.domain.update.ReleasePackage
import com.smaznet.teleplay.domain.update.UpdateError
import com.smaznet.teleplay.domain.update.UpdateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4)
    private var info: Pair<String, Int>? = "com.smaznet.teleplay" to 3
    private var free = 1L shl 40

    private fun files(installed: Int = 2) = UpdateFiles(
        dir = File(tmp.root, "updates"), freeSpace = { free }, archiveInfo = { info },
        expectedPackage = "com.smaznet.teleplay", installedVersion = { installed },
    )

    private fun write(f: UpdateFiles, bytes: ByteArray, code: Int = 3): File {
        f.prepare()
        val p = f.openPart(code)
        p.stream.use { it.write(bytes) }
        return p.file
    }

    private fun release(size: Long = zip.size.toLong()) = ReleasePackage(3, "app-release.apk", size)

    private fun invalid(block: () -> Unit) {
        try { block(); fail("expected PackageInvalid") } catch (e: UpdateException) { assertEquals(UpdateError.PackageInvalid, e.failure.error) }
    }

    @Test fun prepare_deletesLeftovers() {
        val f = files()
        val dir = File(tmp.root, "updates").also { it.mkdirs() }
        File(dir, "teleplay-1.apk").writeBytes(zip)
        File(dir, "teleplay-2.apk.part").writeBytes(zip)
        f.prepare()
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun openPart_usesVersionedPartName() {
        val f = files(); f.prepare()
        assertEquals("teleplay-3.apk.part", f.openPart(3).file.name)
    }

    @Test fun finalize_succeedsAndRenames() {
        val f = files()
        val part = write(f, zip)
        val done = f.finalize(part, release(), 3)
        assertEquals("teleplay-3.apk", done.name)
        assertTrue(done.isFile)
        assertFalse(part.exists())
    }

    @Test fun finalize_sizeMismatchDeletesPart() {
        val f = files(); val part = write(f, zip)
        invalid { f.finalize(part, release(size = 99), 3) }
        assertFalse(part.exists())
    }

    @Test fun finalize_badMagic() {
        val f = files(); val part = write(f, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        invalid { f.finalize(part, release(), 3) }
        assertFalse(part.exists())
    }

    @Test fun finalize_wrongPackage() {
        info = "com.evil.app" to 3
        val f = files(); val part = write(f, zip)
        invalid { f.finalize(part, release(), 3) }
        assertFalse(part.exists())
    }

    @Test fun finalize_unreadableArchive() {
        info = null
        val f = files(); val part = write(f, zip)
        invalid { f.finalize(part, release(), 3) }
    }

    @Test fun finalize_versionDiffersFromAnnounced() {
        info = "com.smaznet.teleplay" to 4
        val f = files(); val part = write(f, zip)
        invalid { f.finalize(part, release(), 3) }
    }

    @Test fun finalize_versionNotNewerThanInstalled() {
        val f = files(installed = 3); val part = write(f, zip)
        invalid { f.finalize(part, release(), 3) }
    }

    @Test fun hasSpaceFor_usesSizePlusMargin() {
        val f = files()
        free = 1000L + UpdateFiles.SAFETY_MARGIN_BYTES
        assertTrue(f.hasSpaceFor(1000))
        free -= 1
        assertFalse(f.hasSpaceFor(1000))
        assertEquals(1000L + UpdateFiles.SAFETY_MARGIN_BYTES, f.neededSpace(1000))
    }

    @Test fun deleteAll_removesEverything() {
        val f = files(); write(f, zip)
        f.deleteAll()
        assertEquals(0, File(tmp.root, "updates").listFiles()!!.size)
    }
}
