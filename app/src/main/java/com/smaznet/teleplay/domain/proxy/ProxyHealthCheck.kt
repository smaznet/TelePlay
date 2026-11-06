package com.smaznet.teleplay.domain.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Progress of a health check: each finished proxy, then the totals. */
data class HealthProgress(val checked: Int, val total: Int, val working: Int, val last: Pair<Long, ProxyTestResult>?) {
    val done: Boolean get() = checked == total
}

/**
 * Tests every saved proxy (a few at a time) and records each result. Only records: it never selects a proxy or
 * switches the proxy on or off (FR-017). Cancelling the collector cancels the run.
 */
class ProxyHealthCheck(
    private val tester: ProxyTesting,
    private val repository: ProxyRepository,
    private val concurrency: Int = 4,
) {
    fun run(proxies: List<Proxy> = repository.proxies.value): Flow<HealthProgress> = channelFlow {
        val total = proxies.size
        if (total == 0) { send(HealthProgress(0, 0, 0, null)); return@channelFlow }
        send(HealthProgress(0, total, 0, null))
        val gate = Semaphore(concurrency)
        var checked = 0
        var working = 0
        val lock = Any()
        coroutineScope {
            for (p in proxies) launch {
                gate.withPermit {
                    val r = try { tester.test(p) } catch (e: CancellationException) { throw e } catch (_: Exception) { ProxyTestResult.NotReachable }
                    repository.recordTest(p.id, r)
                    val progress = synchronized(lock) {
                        checked++
                        if (r.isWorking) working++
                        HealthProgress(checked, total, working, p.id to r)
                    }
                    send(progress)
                }
            }
        }
    }
}
