package com.smaznet.teleplay.domain.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One update session at a time: check the announcement, ask, download, verify, hand over. Nothing runs
 * unless the viewer starts it ([check], [confirmDownload]) and nothing is installed here. Every failure
 * ends in [UpdateState.Failed]; a failure or cancel never reaches the rest of the app because all work
 * is caught inside the launched job.
 */
class AppUpdater(
    private val feed: UpdateFeed,
    private val files: UpdateFilePort,
    private val installedVersion: () -> Int,
    private val isConnected: () -> Boolean,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val progressIntervalMs: Long = 250,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private var session = 0

    /** Starts a check. Ignored while another check, download or prompt is active. */
    fun check() {
        val id: Int
        synchronized(lock) {
            when (_state.value) {
                UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Failed -> Unit
                else -> return
            }
            id = ++session
            if (!isConnected()) {
                _state.value = UpdateState.Failed(UpdateFailure(UpdateError.NotConnected))
                return
            }
            _state.value = UpdateState.Checking
            job = scope.launch { runCheck(id) }
        }
    }

    private suspend fun runCheck(id: Int) {
        try {
            val announcement = Announcement.parse(feed.readAnnouncementText())
                ?: throw UpdateException(UpdateError.AnnouncementUnreadable)
            val installed = installedVersion()
            if (announcement.versionCode <= installed) return publish(id, UpdateState.UpToDate(installed))
            val release = feed.readRelease(announcement.releasePostId)
            publish(id, UpdateState.Available(announcement, release))
        } catch (e: CancellationException) {
            throw e
        } catch (e: UpdateException) {
            publish(id, UpdateState.Failed(e.failure))
        } catch (e: Exception) {
            publish(id, UpdateState.Failed(UpdateFailure(UpdateError.AnnouncementUnreadable)))
        }
    }

    /** Starts the download after the viewer said yes. Only valid from [UpdateState.Available]. */
    fun confirmDownload() {
        val id: Int
        val available: UpdateState.Available
        synchronized(lock) {
            available = _state.value as? UpdateState.Available ?: return
            id = ++session
            _state.value = UpdateState.Downloading(0, available.release.sizeBytes)
            job = scope.launch { runDownload(id, available) }
        }
    }

    private suspend fun runDownload(id: Int, available: UpdateState.Available) {
        val release = available.release
        var part: PartFile? = null
        try {
            files.prepare()
            if (!files.hasSpaceFor(release.sizeBytes)) {
                return publish(id, UpdateState.Failed(UpdateFailure(UpdateError.StorageLow, files.neededSpace(release.sizeBytes))))
            }
            part = files.openPart(available.announcement.versionCode)
            var lastPublish = 0L
            part.stream.use { out ->
                feed.download(release, out) { done ->
                    val now = nowMillis()
                    if (done >= release.sizeBytes || now - lastPublish >= progressIntervalMs) {
                        lastPublish = now
                        publish(id, UpdateState.Downloading(done, release.sizeBytes))
                    }
                }
            }
            publish(id, UpdateState.Verifying)
            val file = files.finalize(part.file, release, available.announcement.versionCode)
            publish(id, UpdateState.ReadyToInstall(file))
        } catch (e: CancellationException) {
            part?.let { files.discard(it.file) }
            throw e
        } catch (e: UpdateException) {
            part?.let { files.discard(it.file) }
            publish(id, UpdateState.Failed(e.failure))
        } catch (e: Exception) {
            part?.let { files.discard(it.file) }
            publish(id, UpdateState.Failed(UpdateFailure(UpdateError.DownloadFailed)))
        }
    }

    /** Stops whatever is running, removes any downloaded file and returns to [UpdateState.Idle]. */
    fun cancel() {
        val hadFile: Boolean
        synchronized(lock) {
            session++
            job?.cancel()
            job = null
            hadFile = _state.value.let { it is UpdateState.Downloading || it is UpdateState.Verifying || it is UpdateState.ReadyToInstall }
            _state.value = UpdateState.Idle
        }
        if (hadFile) files.deleteAll()
    }

    /** Closes the dialog: cancels active work, otherwise just resets. */
    fun dismiss() = cancel()

    /** The bot was disconnected: cancel everything and wipe the download directory. */
    fun onDisconnected() {
        cancel()
        files.deleteAll()
    }

    /** Only the current session may change the state, so a cancelled job can never overwrite [UpdateState.Idle]. */
    private fun publish(id: Int, s: UpdateState) {
        synchronized(lock) { if (id == session) _state.value = s }
    }
}
