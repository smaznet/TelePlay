package com.smaznet.teleplay.data

import com.smaznet.teleplay.data.telegram.*
import com.smaznet.teleplay.domain.GatewayState
import com.smaznet.teleplay.domain.IncomingMessage
import com.smaznet.teleplay.domain.TelegramError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class MemStore(var s: UpdateState? = null) : UpdateStateStore {
    val saved = mutableListOf<UpdateState>()
    override fun load() = s
    override fun save(state: UpdateState) { s = state; saved += state }
    override fun clear() { s = null }
}

private class FakeApi(val diffs: ArrayDeque<Difference> = ArrayDeque()) : UpdatesApi {
    var stateCalls = 0
    val diffRequests = mutableListOf<UpdateState>()
    var failWith: Exception? = null
    override suspend fun getState(): UpdateState { stateCalls++; return UpdateState(10, 1, 100, 5) }
    override suspend fun getDifference(from: UpdateState): Difference {
        failWith?.let { failWith = null; throw it }
        diffRequests += from
        return diffs.removeFirst()
    }
}

private fun msg(id: Int) = IncomingMessage(id, 7L, 1L, "t$id", null)

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateLoopTest {
    private val out = mutableListOf<IncomingMessage>()
    private fun loop(api: UpdatesApi, store: UpdateStateStore, states: MutableList<GatewayState> = mutableListOf()) =
        UpdateLoop(api, store, { out += it }, { states += it }, pollMs = 60_000)

    @Test
    fun firstRun_getsStateAndPersistsIt() = runTest {
        val store = MemStore(); val api = FakeApi()
        loop(api, store).syncOnce()
        assertEquals(1, api.stateCalls)
        assertEquals(UpdateState(10, 1, 100, 5), store.s)
        assertEquals(0, api.diffRequests.size)
    }

    @Test
    fun difference_advancesState_andEmits() = runTest {
        val store = MemStore(UpdateState(10, 1, 100, 5))
        val api = FakeApi(ArrayDeque(listOf(Difference.Batch(listOf(msg(1), msg(2)), UpdateState(12, 2, 120, 6), true))))
        loop(api, store).syncOnce()
        assertEquals(listOf(1, 2), out.map { it.messageId })
        assertEquals(UpdateState(12, 2, 120, 6), store.s)
        assertEquals(UpdateState(10, 1, 100, 5), api.diffRequests.single())
    }

    @Test
    fun slice_loopsUntilFinalDifference() = runTest {
        val store = MemStore(UpdateState(10, 1, 100, 5))
        val api = FakeApi(ArrayDeque(listOf(
            Difference.Batch(listOf(msg(1)), UpdateState(11, 1, 110, 5), false),
            Difference.Batch(listOf(msg(2)), UpdateState(12, 1, 120, 5), false),
            Difference.Batch(listOf(msg(3)), UpdateState(13, 1, 130, 5), true),
        )))
        loop(api, store).syncOnce()
        assertEquals(listOf(1, 2, 3), out.map { it.messageId })
        assertEquals(listOf(10, 11, 12), api.diffRequests.map { it.pts })
        assertEquals(3, store.saved.size)
        assertEquals(13, store.s!!.pts)
    }

    @Test
    fun empty_stopsAndStoresDateAndSeq() = runTest {
        val store = MemStore(UpdateState(10, 1, 100, 5))
        val api = FakeApi(ArrayDeque(listOf(Difference.Empty(200, 9))))
        loop(api, store).syncOnce()
        assertEquals(UpdateState(10, 1, 200, 9), store.s)
        assertEquals(0, out.size)
    }

    @Test
    fun persistedState_resumesAfterRestart() = runTest {
        val store = MemStore()
        val api1 = FakeApi(ArrayDeque(listOf(Difference.Batch(listOf(msg(1)), UpdateState(20, 1, 200, 5), true))))
        val l1 = loop(api1, store)
        l1.syncOnce() // first run: getState only
        l1.syncOnce() // applies the batch
        // "restart": a new loop with the same store must not call getState again
        val api2 = FakeApi(ArrayDeque(listOf(Difference.Empty(300, 5))))
        loop(api2, store).syncOnce()
        assertEquals(0, api2.stateCalls)
        assertEquals(20, api2.diffRequests.single().pts)
    }

    @Test
    fun tooLong_restartsFromNewPts() = runTest {
        val store = MemStore(UpdateState(10, 1, 100, 5))
        val api = FakeApi(ArrayDeque(listOf(Difference.TooLong(500), Difference.Empty(101, 5))))
        loop(api, store).syncOnce()
        assertEquals(listOf(10, 500), api.diffRequests.map { it.pts })
    }

    @Test
    fun run_pollsEveryMinute_andOnPoke_andReportsOffline() = runTest {
        val store = MemStore(UpdateState(10, 1, 100, 5))
        val api = FakeApi(ArrayDeque(List(10) { Difference.Empty(100, 5) }))
        val states = mutableListOf<GatewayState>()
        val l = loop(api, store, states)
        api.failWith = TelegramError.Offline()
        val job = launch { l.run() }
        runCurrent()
        assertEquals(listOf(GatewayState.OFFLINE), states)
        advanceTimeBy(1_100); runCurrent()
        assertEquals(1, api.diffRequests.size)
        advanceTimeBy(59_000); runCurrent()
        assertEquals(1, api.diffRequests.size)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, api.diffRequests.size)
        l.poke(); runCurrent()
        assertEquals(3, api.diffRequests.size)
        job.cancel()
    }

    @Test
    fun run_tokenRejected_stopsWithRejected() = runTest {
        val api = FakeApi().also { it.failWith = TelegramError.TokenRejected() }
        val states = mutableListOf<GatewayState>()
        loop(api, MemStore(UpdateState(1, 1, 1, 1)), states).run()
        assertEquals(listOf(GatewayState.REJECTED), states)
        assertNull(null)
    }
}
