package com.smaznet.teleplay.domain.remote

import com.smaznet.teleplay.data.repo.IngestResult
import com.smaznet.teleplay.data.telegram.BotMessenger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tells the authorized user in their private chat that videos were added (FR-001, FR-003, FR-004).
 * Results arriving within [windowMs] of the first one are sent as a single message; a lone video gets
 * its own message. A lost notice (failure, process death) is acceptable and never retried.
 */
class AddedNotifier(
    private val messenger: BotMessenger,
    private val settings: RemoteSettings,
    private val scope: CoroutineScope,
    private val windowMs: Long = 10_000,
) {
    private val lock = Any()
    private val added = ArrayList<String>()
    private val duplicates = ArrayList<String>()
    private var timer: Job? = null

    fun offer(result: IngestResult) {
        if (!settings.notifyAdded.value) return
        synchronized(lock) {
            when (result) {
                is IngestResult.Added -> added += result.title
                is IngestResult.Duplicate -> duplicates += result.title
                is IngestResult.Redelivered, IngestResult.Ignored -> return
            }
            if (timer == null) timer = scope.launch { delay(windowMs); flush() }
        }
    }

    private suspend fun flush() {
        val a: List<String>
        val d: List<String>
        synchronized(lock) {
            a = added.toList(); d = duplicates.toList()
            added.clear(); duplicates.clear()
            timer = null
        }
        if (!settings.notifyAdded.value) return
        val text = when {
            a.size == 1 && d.isEmpty() -> MessageFormatter.addedSingle(a[0])
            a.isEmpty() && d.size == 1 -> MessageFormatter.duplicateSingle(d[0])
            a.isEmpty() && d.isEmpty() -> return
            else -> MessageFormatter.addedBatch(a, d)
        }
        try {
            messenger.sendText(text, null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
