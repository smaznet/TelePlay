package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.domain.GatewayState
import com.smaznet.teleplay.domain.IncomingMessage
import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.mtproto.Rpc
import com.smaznet.teleplay.mtproto.tl.generated.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

sealed class Difference {
    class Empty(val date: Int, val seq: Int) : Difference()
    /** [last] is false for `differenceSlice` (more to fetch). */
    class Batch(val messages: List<IncomingMessage>, val state: UpdateState, val last: Boolean) : Difference()
    class TooLong(val pts: Int) : Difference()
}

/** The two update calls the loop needs; faked in tests. */
interface UpdatesApi {
    suspend fun getState(): UpdateState
    suspend fun getDifference(from: UpdateState): Difference
}

class RpcUpdatesApi(private val rpc: Rpc) : UpdatesApi {
    override suspend fun getState(): UpdateState = (rpc.invoke(F_updates_getState()) as C_updates_state).toState()

    override suspend fun getDifference(from: UpdateState): Difference {
        return when (val d = rpc.invoke(F_updates_getDifference(pts = from.pts, date = from.date, qts = from.qts))) {
            is C_updates_differenceEmpty -> Difference.Empty(d.date, d.seq)
            is C_updates_difference -> Difference.Batch(collect(d.new_messages, d.other_updates, d.users), (d.state as C_updates_state).toState(), true)
            is C_updates_differenceSlice ->
                Difference.Batch(collect(d.new_messages, d.other_updates, d.users), (d.intermediate_state as C_updates_state).toState(), false)
            is C_updates_differenceTooLong -> Difference.TooLong(d.pts)
        }
    }

    private fun collect(msgs: List<T_Message>, updates: List<T_Update>, users: List<T_User>): List<IncomingMessage> {
        val fromUpdates = updates.mapNotNull { (it as? C_updateNewMessage)?.message }
        val hashes = PushedUpdates.userHashes(users)
        return (msgs + fromUpdates).mapNotNull { it.toIncoming(hashes) }
    }

    private fun C_updates_state.toState() = UpdateState(pts, qts, date, seq)
}

/**
 * Initial `updates.getState`, then `updates.getDifference` whenever poked (socket push, app
 * foreground) or every [pollMs]. State is persisted after each applied batch, after the messages
 * were emitted (at-least-once). Only private-chat messages are mapped, so channel updates are ignored.
 */
class UpdateLoop(
    private val api: UpdatesApi,
    private val store: UpdateStateStore,
    private val emit: suspend (IncomingMessage) -> Unit,
    private val onState: (GatewayState) -> Unit = {},
    /** Tries to fix a lost auth key; true means retry. */
    private val recover: suspend () -> Boolean = { false },
    private val pollMs: Long = 60_000,
    private val maxBackoffMs: Long = 30_000,
) {
    private val trigger = Channel<Unit>(Channel.CONFLATED)

    fun poke() { trigger.trySend(Unit) }

    suspend fun syncOnce() {
        var st: UpdateState = store.load() ?: run {
            store.save(api.getState())
            return
        }
        while (true) {
            when (val d = api.getDifference(st)) {
                is Difference.Empty -> {
                    st = st.copy(date = d.date, seq = d.seq)
                    store.save(st)
                    return
                }
                is Difference.TooLong -> {
                    st = st.copy(pts = d.pts)
                    store.save(st)
                }
                is Difference.Batch -> {
                    d.messages.forEach { emit(it) }
                    st = d.state
                    store.save(st)
                    if (d.last) return
                }
            }
        }
    }

    /** Runs until cancelled or the credentials are lost. */
    suspend fun run() {
        var backoff = 1000L
        while (true) {
            try {
                syncOnce()
                onState(GatewayState.READY)
                backoff = 1000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramError.Offline) {
                onState(GatewayState.OFFLINE)
                delay(backoff)
                backoff = minOf(backoff * 2, maxBackoffMs)
                continue
            } catch (e: TelegramError.TokenRejected) {
                onState(GatewayState.REJECTED)
                return
            } catch (e: TelegramError.AuthKeyLost) {
                if (recover()) continue
                onState(GatewayState.REVOKED)
                return
            } catch (e: TelegramError.RateLimited) {
                delay(e.retryAfterSec * 1000L)
                continue
            } catch (e: Exception) {
                delay(backoff)
                backoff = minOf(backoff * 2, maxBackoffMs)
                continue
            }
            withTimeoutOrNull(pollMs) { trigger.receive() }
        }
    }
}
