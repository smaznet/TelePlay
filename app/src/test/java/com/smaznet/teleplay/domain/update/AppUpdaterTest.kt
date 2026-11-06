package com.smaznet.teleplay.domain.update

import com.smaznet.teleplay.fakes.FakeUpdateFeed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

private class FakeFiles : UpdateFilePort {
    val dir = java.nio.file.Files.createTempDirectory("upd").toFile()
    var hasSpace = true
    var finalizeFailure: UpdateException? = null
    var prepared = 0
    var deleteAlls = 0
    val discarded = mutableListOf<File>()
    override fun prepare() { prepared++ }
    override fun hasSpaceFor(sizeBytes: Long) = hasSpace
    override fun neededSpace(sizeBytes: Long) = sizeBytes + 100
    override fun openPart(versionCode: Int): PartFile {
        val f = File(dir, "p$versionCode.part")
        return PartFile(f, f.outputStream())
    }
    override fun finalize(part: File, release: ReleasePackage, announcedCode: Int): File {
        finalizeFailure?.let { part.delete(); throw it }
        return File(dir, "done.apk").also { part.renameTo(it) }
    }
    override fun discard(part: File) { part.delete(); discarded += part }
    override fun deleteAll() { deleteAlls++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdaterTest {
    private val feed = FakeUpdateFeed()
    private val files = FakeFiles()
    private var installed = 2
    private var connected = true

    private fun TestScope.updater() = AppUpdater(
        feed, files, installedVersion = { installed }, isConnected = { connected },
        scope = this, progressIntervalMs = 0,
    )

    private fun fail(e: UpdateError, needed: Long = 0) = UpdateState.Failed(UpdateFailure(e, needed))

    // --- US1: check ------------------------------------------------------------------------
    @Test fun check_upToDateWhenAnnouncedEqualsInstalled() = runTest {
        feed.announcementText = "code: 2\nhttps://t.me/TelePlayUpdate/3"
        val u = updater(); u.check(); advanceUntilIdle()
        assertEquals(UpdateState.UpToDate(2), u.state.value)
        assertEquals(0, feed.releaseReads)
    }

    @Test fun check_upToDateWhenAnnouncedIsOlder() = runTest {
        installed = 5
        val u = updater(); u.check(); advanceUntilIdle()
        assertEquals(UpdateState.UpToDate(5), u.state.value)
    }

    @Test fun check_availableWhenStrictlyNewer() = runTest {
        val u = updater(); u.check(); advanceUntilIdle()
        val s = u.state.value as UpdateState.Available
        assertEquals(Announcement(3, 3), s.announcement)
        assertEquals(3, s.release.postId)
        assertEquals(0, feed.downloads)
    }

    @Test fun check_notConnected_noFeedCall() = runTest {
        connected = false
        val u = updater(); u.check(); advanceUntilIdle()
        assertEquals(fail(UpdateError.NotConnected), u.state.value)
        assertEquals(0, feed.announcementReads)
    }

    @Test fun check_unparseableAnnouncement() = runTest {
        feed.announcementText = "latest: soon"
        val u = updater(); u.check(); advanceUntilIdle()
        assertEquals(fail(UpdateError.AnnouncementUnreadable), u.state.value)
    }

    @Test fun check_secondCallWhileCheckingIsIgnored() = runTest {
        val u = updater(); u.check(); u.check(); advanceUntilIdle()
        assertEquals(1, feed.announcementReads)
    }

    @Test fun nothingDownloadsWithoutConfirmation() = runTest {
        val u = updater(); u.check(); advanceUntilIdle()
        assertTrue(u.state.value is UpdateState.Available)
        assertEquals(0, feed.downloads)
        assertEquals(0, files.prepared)
    }

    @Test fun confirmDownload_ignoredOutsideAvailable() = runTest {
        val u = updater(); u.confirmDownload(); advanceUntilIdle()
        assertEquals(UpdateState.Idle, u.state.value)
        assertEquals(0, feed.downloads)
    }

    // --- US2: download ---------------------------------------------------------------------
    @Test fun download_happyPath() = runTest {
        val u = updater(); u.check(); advanceUntilIdle()
        val seen = mutableListOf<UpdateState>()
        val collector = launchCollect(u, seen)
        u.confirmDownload(); advanceUntilIdle()
        collector.cancel()
        assertTrue(seen.any { it is UpdateState.Downloading && it.done == 2L })
        assertTrue(seen.contains(UpdateState.Verifying))
        val ready = u.state.value as UpdateState.ReadyToInstall
        assertTrue(ready.file.exists())
        assertEquals(1, files.prepared)
    }

    private fun TestScope.launchCollect(u: AppUpdater, into: MutableList<UpdateState>) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { u.state.collect { into += it } }

    @Test fun cancelMidDownload_returnsToIdleAndDeletesPart() = runTest {
        val u = updater(); u.check(); advanceUntilIdle()
        feed.betweenParts = { written -> if (written == 2) { u.cancel(); kotlinx.coroutines.yield() } }
        u.confirmDownload(); advanceUntilIdle()
        assertEquals(UpdateState.Idle, u.state.value)
        assertTrue(files.deleteAlls >= 1)
        assertTrue(files.dir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun dismissOnReady_deletesFile() = runTest {
        val u = updater(); u.check(); advanceUntilIdle(); u.confirmDownload(); advanceUntilIdle()
        val before = files.deleteAlls
        u.dismiss()
        assertEquals(UpdateState.Idle, u.state.value)
        assertEquals(before + 1, files.deleteAlls)
    }

    @Test fun disconnect_cancelsAndWipes() = runTest {
        val u = updater(); u.check(); advanceUntilIdle(); u.confirmDownload(); advanceUntilIdle()
        u.onDisconnected()
        assertEquals(UpdateState.Idle, u.state.value)
        assertTrue(files.deleteAlls >= 1)
    }

    // --- US3: failures ---------------------------------------------------------------------
    @Test fun everyFeedErrorEndsInFailed() = runTest {
        for (e in listOf(UpdateError.Offline, UpdateError.AnnouncementUnreadable, UpdateError.NotConnected)) {
            feed.announcementFailure = UpdateException(e)
            val u = updater(); u.check(); advanceUntilIdle()
            assertEquals(fail(e), u.state.value)
        }
        feed.announcementFailure = null
        feed.releaseFailure = UpdateException(UpdateError.PackageMissing)
        val u = updater(); u.check(); advanceUntilIdle()
        assertEquals(fail(UpdateError.PackageMissing), u.state.value)
    }

    @Test fun retryFromFailedWorks() = runTest {
        feed.announcementFailure = UpdateException(UpdateError.Offline)
        val u = updater(); u.check(); advanceUntilIdle()
        assertEquals(fail(UpdateError.Offline), u.state.value)
        feed.announcementFailure = null
        u.check(); advanceUntilIdle()
        assertTrue(u.state.value is UpdateState.Available)
    }

    @Test fun storageLow_beforeDownload_createsNoFile() = runTest {
        files.hasSpace = false
        val u = updater(); u.check(); advanceUntilIdle(); u.confirmDownload(); advanceUntilIdle()
        assertEquals(fail(UpdateError.StorageLow, 4L + 100), u.state.value)
        assertEquals(0, feed.downloads)
        assertEquals(0, files.dir.listFiles()!!.size)
    }

    @Test fun downloadFailure_discardsPart() = runTest {
        val u = updater(); u.check(); advanceUntilIdle()
        feed.betweenParts = { throw UpdateException(UpdateError.Offline) }
        u.confirmDownload(); advanceUntilIdle()
        assertEquals(fail(UpdateError.Offline), u.state.value)
        assertEquals(1, files.discarded.size)
    }

    @Test fun unexpectedException_becomesDownloadFailed() = runTest {
        val u = updater(); u.check(); advanceUntilIdle()
        feed.betweenParts = { throw java.io.IOException("disk") }
        u.confirmDownload(); advanceUntilIdle()
        assertEquals(fail(UpdateError.DownloadFailed), u.state.value)
    }

    @Test fun damagedDownload_neverReachesReady() = runTest {
        files.finalizeFailure = UpdateException(UpdateError.PackageInvalid)
        val u = updater(); u.check(); advanceUntilIdle(); u.confirmDownload(); advanceUntilIdle()
        assertEquals(fail(UpdateError.PackageInvalid), u.state.value)
        assertTrue(files.dir.listFiles()!!.isEmpty())
    }

    @Test fun releaseHandleIsNotPrinted() {
        val r = ReleasePackage(3, "a.apk", 10, handle = "SECRET-HANDLE")
        assertFalse(r.toString().contains("SECRET"))
        assertNull(null)
    }
}
