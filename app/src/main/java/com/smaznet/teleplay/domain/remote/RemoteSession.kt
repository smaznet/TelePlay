package com.smaznet.teleplay.domain.remote

import com.smaznet.teleplay.data.db.RemoteMessageStore
import com.smaznet.teleplay.data.telegram.BotMessenger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

/**
 * Owns the single pinned "Now playing" message (spec US2/US3, data-model.md state machine).
 *
 * A session starts with the first playback snapshot, reuses one message for every video in it, edits it in
 * place every [refreshMs] while content changes, and ends by editing it to a final state, removing the
 * buttons and unpinning it (the message stays in the chat). Every Telegram failure is absorbed here:
 * nothing in this class can affect playback.
 *
 * [peerUserId] returns the authorized user's id only when messaging is possible (paired and access hash
 * known), otherwise null.
 */
class RemoteSession(
    private val hub: PlaybackHub,
    private val messenger: BotMessenger,
    private val store: RemoteMessageStore,
    private val settings: RemoteSettings,
    private val scope: CoroutineScope,
    private val peerUserId: () -> Long?,
    private val now: () -> Long = System::currentTimeMillis,
    private val newSessionId: () -> String = ::randomSessionId,
    private val refreshMs: Long = 30_000,
    /** A snapshot that disappears (e.g. while skipping) only ends the session if no new one shows up in time. */
    private val finalizeGraceMs: Long = 4_000,
    private val minEditGapMs: Long = 1_000,
    /** After a failed send, wait this long before trying to start the session again. */
    private val retryAfterFailureMs: Long = 30_000,
) {
    private class Active(
        val sessionId: String,
        val messageId: Int,
        var title: String,
        var lastText: String,
        var lastState: PlayState,
        var lastVideoId: Long,
        var lastEditAt: Long,
        var pinned: Boolean,
        var finished: Boolean = false,
    )

    private val mutex = Mutex()
    private var active: Active? = null
    private var tickerJob: Job? = null
    private var pendingRefresh: Job? = null
    private var finalizeJob: Job? = null
    private var blocked = false
    private var retryNotBefore = 0L
    private var started = false

    @Volatile private var sessionIdSnapshot: String? = null

    /** The id embedded in the current message's buttons; null when no session is active. */
    fun currentSessionId(): String? = sessionIdSnapshot

    /** Starts observing the player and the settings. Idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch { hub.snapshot.collect { onSnapshot(it) } }
        scope.launch {
            settings.liveRemote.collect { on -> if (!on) shutdown(unpin = true) }
        }
    }

    /** Schedules one coalesced refresh (used after an accepted button press, giving the player time to react). */
    fun requestRefresh(delayMs: Long = minEditGapMs) { scheduleRefresh(delayMs) }

    /** A press on a message from an earlier session: finalize it if it is our stored leftover. */
    suspend fun onStalePress(q: CallbackQuery) {
        mutex.withLock {
            val row = store.get() ?: return
            if (row.messageId == q.messageId && active?.messageId != q.messageId) finalizeLeftover(row.messageId, row.pinned)
        }
    }

    /**
     * Ends everything: finalizes the current or leftover message as "Stopped" (and unpins it when [unpin]),
     * clears the stored row, stops all timers. Used on disconnect, pairing reset and when the remote is turned off.
     */
    suspend fun shutdown(unpin: Boolean) {
        mutex.withLock {
            cancelTimers()
            val a = active
            active = null
            sessionIdSnapshot = null
            if (a != null) {
                runSafely { messenger.editText(a.messageId, MessageFormatter.finalText("", finished = false), null) }
                if (unpin && a.pinned) runSafely { messenger.unpin(a.messageId) }
            } else store.get()?.let { row ->
                runSafely { messenger.editText(row.messageId, MessageFormatter.finalText("", finished = false), null) }
                if (unpin && row.pinned) runSafely { messenger.unpin(row.messageId) }
            }
            runSafely { store.clear() }
        }
    }

    // --- snapshot handling -----------------------------------------------------------------

    private suspend fun onSnapshot(s: PlaybackSnapshot?) {
        mutex.withLock {
            if (s == null || s.state == PlayState.ENDED) {
                val a = active
                if (a == null) { blocked = false; return }
                if (s != null) { a.finished = true; a.lastState = s.state }
                if (finalizeJob?.isActive != true) {
                    finalizeJob = scope.launch { delay(finalizeGraceMs); finalizeSession() }
                }
                return
            }
            finalizeJob?.cancel(); finalizeJob = null
            if (!settings.liveRemote.value || blocked) return
            val a = active
            if (a == null) {
                if (now() >= retryNotBefore) startSession(s)
                return
            }
            a.finished = false
            a.title = s.title
            if (s.videoId != a.lastVideoId || s.state != a.lastState) scheduleRefresh(0)
        }
    }

    private suspend fun startSession(s: PlaybackSnapshot) {
        val chat = peerUserId() ?: return
        store.get()?.let { finalizeLeftover(it.messageId, it.pinned) }
        val sessionId = newSessionId()
        val text = MessageFormatter.nowPlaying(s)
        val sent = try {
            messenger.sendText(text, MessageFormatter.keyboard(sessionId, s))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val id = sent.getOrNull()
        if (id == null) {
            onFailure(sent.exceptionOrNull())
            retryNotBefore = now() + retryAfterFailureMs
            return
        }
        val pinned = runSafely { messenger.pin(id) }.isSuccess
        val a = Active(sessionId, id, s.title, text, s.state, s.videoId, now(), pinned)
        active = a
        sessionIdSnapshot = sessionId
        runSafely { store.put(RemoteMessage(chat, id, sessionId, text.hashCode(), pinned, now())) }
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(refreshMs)
                refresh()
            }
        }
    }

    // --- refresh -------------------------------------------------------------------------------

    private fun scheduleRefresh(delayMs: Long) {
        if (pendingRefresh?.isActive == true) return
        pendingRefresh = scope.launch {
            val gap = active?.let { minEditGapMs - (now() - it.lastEditAt) } ?: 0L
            delay(maxOf(delayMs, gap, 0L))
            refresh()
        }
    }

    private suspend fun refresh() {
        mutex.withLock {
            val a = active ?: return
            val s = hub.snapshot.value ?: return
            if (s.state == PlayState.ENDED) return
            val text = MessageFormatter.nowPlaying(s)
            a.lastState = s.state
            a.lastVideoId = s.videoId
            a.title = s.title
            if (text == a.lastText) return // nothing visible changed (also covers long pauses)
            val r = runSafely { messenger.editText(a.messageId, text, MessageFormatter.keyboard(a.sessionId, s)) }
            if (r.isSuccess) {
                a.lastText = text
                a.lastEditAt = now()
                runSafely { store.put(RemoteMessage(peerUserId() ?: return@runSafely, a.messageId, a.sessionId, text.hashCode(), a.pinned, now())) }
            } else {
                handleEditFailure(r.exceptionOrNull(), s)
            }
        }
    }

    private suspend fun handleEditFailure(e: Throwable?, s: PlaybackSnapshot) {
        when ((e as? RemoteSendException)?.failure) {
            RemoteFailure.GONE -> {
                // Deleted by the user: forget it and start a fresh message from the current state.
                cancelTimers(keepFinalize = true)
                active = null
                sessionIdSnapshot = null
                runSafely { store.clear() }
                startSession(s)
            }
            RemoteFailure.BLOCKED -> block()
            else -> Unit // FLOOD / OFFLINE / OTHER: skip this update; the next tick supersedes it
        }
    }

    // --- ending ----------------------------------------------------------------------------------

    private suspend fun finalizeSession() {
        mutex.withLock {
            val a = active ?: return
            cancelTimers(keepFinalize = true)
            active = null
            sessionIdSnapshot = null
            blocked = false
            runSafely { messenger.editText(a.messageId, MessageFormatter.finalText(a.title, a.finished), null) }
            if (a.pinned) runSafely { messenger.unpin(a.messageId) }
            runSafely { store.clear() }
        }
    }

    /** Leftover from a run that never finished (app killed): mark it stopped and unpin it. Caller holds the lock. */
    private suspend fun finalizeLeftover(messageId: Int, pinned: Boolean) {
        runSafely { messenger.editText(messageId, MessageFormatter.finalText("", finished = false), null) }
        if (pinned) runSafely { messenger.unpin(messageId) }
        runSafely { store.clear() }
    }

    private fun cancelTimers(keepFinalize: Boolean = false) {
        tickerJob?.cancel(); tickerJob = null
        pendingRefresh?.cancel(); pendingRefresh = null
        if (!keepFinalize) { finalizeJob?.cancel(); finalizeJob = null }
    }

    private fun block() {
        blocked = true // until the next session (the snapshot disappearing resets it)
        cancelTimers(keepFinalize = true)
        active = null
        sessionIdSnapshot = null
    }

    private fun onFailure(e: Throwable?) {
        if ((e as? RemoteSendException)?.failure == RemoteFailure.BLOCKED) blocked = true
    }

    private suspend fun <T> runSafely(block: suspend () -> T): Result<T> = try {
        val r = block()
        if (r is Result<*>) {
            @Suppress("UNCHECKED_CAST")
            r as Result<T>
        } else Result.success(r)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val random = SecureRandom()

        /** 8 random chars `[A-Za-z0-9]`. */
        fun randomSessionId(): String = buildString { repeat(8) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
    }
}
