package com.smaznet.teleplay.domain.proxy

import com.smaznet.teleplay.data.proxy.ProxyBlob
import com.smaznet.teleplay.data.proxy.ProxyFlagPrefs
import com.smaznet.teleplay.data.proxy.ProxyStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AddResult {
    class Added(val proxy: Proxy) : AddResult
    class Duplicate(val existing: Proxy) : AddResult
    data object LimitReached : AddResult
    class Invalid(val reason: String) : AddResult
}

sealed interface EnableResult { data object Ok : EnableResult; data object NeedsProxy : EnableResult }

/** The route the transport layer should take right now. */
sealed interface ActiveRoute {
    data object Direct : ActiveRoute
    class Through(val proxy: Proxy) : ActiveRoute
}

/**
 * Saved proxies plus the on/off switch and the selected proxy. Saved proxies survive disconnect; only
 * [delete] removes one. [routeChanges] fires only when the route the network layer uses actually changes.
 */
class ProxyRepository(
    private val store: ProxyStore,
    private val prefs: ProxyFlagPrefs,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var blob: ProxyBlob = store.load()
    private val _proxies = MutableStateFlow(blob.items)
    private val _state = MutableStateFlow(ProxyState(prefs.enabled && blob.selectedId != null, blob.selectedId))
    private val _routeChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    val proxies: StateFlow<List<Proxy>> = _proxies.asStateFlow()
    val state: StateFlow<ProxyState> = _state.asStateFlow()
    val routeChanges: SharedFlow<Unit> = _routeChanges.asSharedFlow()

    /** The route for the next connect. */
    fun route(): ActiveRoute {
        val s = _state.value
        if (!s.active) return ActiveRoute.Direct
        val p = _proxies.value.firstOrNull { it.id == s.selectedId } ?: return ActiveRoute.Direct
        return ActiveRoute.Through(p)
    }

    fun get(id: Long): Proxy? = _proxies.value.firstOrNull { it.id == id }

    fun selected(): Proxy? = _state.value.selectedId?.let(::get)

    fun add(draft: ProxyDraft): AddResult = synchronized(lock) {
        ProxyLimits.problem(draft)?.let { return AddResult.Invalid(it) }
        val key = Proxy.dedupeKey(draft.kind, draft.server, draft.port, draft.username, draft.password, draft.secret)
        blob.items.firstOrNull { it.dedupeKey() == key }?.let { return AddResult.Duplicate(it) }
        if (blob.items.size >= ProxyLimits.MAX_PROXIES) return AddResult.LimitReached
        val p = Proxy(blob.nextId, draft.kind, draft.server, draft.port, draft.name, draft.username, draft.password, draft.secret)
        persist(ProxyBlob(blob.items + p, blob.selectedId, blob.nextId + 1))
        AddResult.Added(p)
    }

    /** Replaces the proxy's fields; result is [AddResult.Duplicate] if it would equal another saved proxy. */
    fun update(id: Long, draft: ProxyDraft): AddResult = synchronized(lock) {
        ProxyLimits.problem(draft)?.let { return AddResult.Invalid(it) }
        val old = blob.items.firstOrNull { it.id == id } ?: return AddResult.Invalid("missing")
        val key = Proxy.dedupeKey(draft.kind, draft.server, draft.port, draft.username, draft.password, draft.secret)
        blob.items.firstOrNull { it.id != id && it.dedupeKey() == key }?.let { return AddResult.Duplicate(it) }
        val p = Proxy(id, draft.kind, draft.server, draft.port, draft.name, draft.username, draft.password, draft.secret)
        val wasActive = _state.value.active && _state.value.selectedId == id
        val before = old.dedupeKey()
        persist(ProxyBlob(blob.items.map { if (it.id == id) p else it }, blob.selectedId, blob.nextId), fireRoute = wasActive && before != key)
        AddResult.Added(p)
    }

    /** Removes one proxy. Deleting the selected one switches the proxy off and clears the selection. */
    fun delete(id: Long) = synchronized(lock) {
        if (blob.items.none { it.id == id }) return
        val wasSelected = blob.selectedId == id
        val wasActive = _state.value.active && wasSelected
        persist(ProxyBlob(blob.items.filter { it.id != id }, if (wasSelected) null else blob.selectedId, blob.nextId), fireRoute = wasActive)
    }

    /** Selects [id]. Does not turn the proxy on; if it is on, the route changes to the new proxy. */
    fun select(id: Long) = synchronized(lock) {
        if (blob.items.none { it.id == id } || blob.selectedId == id) return
        val wasOn = _state.value.enabled
        persist(ProxyBlob(blob.items, id, blob.nextId), fireRoute = wasOn)
    }

    fun setEnabled(on: Boolean): EnableResult = synchronized(lock) {
        if (on && blob.selectedId == null) return EnableResult.NeedsProxy
        if (_state.value.enabled == on) return EnableResult.Ok
        prefs.enabled = on
        _state.value = ProxyState(on, blob.selectedId)
        _routeChanges.tryEmit(Unit)
        EnableResult.Ok
    }

    /** Saves a test result without touching the selection or the switch. */
    fun recordTest(id: Long, result: ProxyTestResult?) = synchronized(lock) {
        if (blob.items.none { it.id == id }) return
        persist(ProxyBlob(blob.items.map { if (it.id == id) it.withTest(result, clock()) else it }, blob.selectedId, blob.nextId))
    }

    private fun persist(next: ProxyBlob, fireRoute: Boolean = false) {
        blob = next
        store.save(next)
        val enabled = _state.value.enabled && next.selectedId != null
        if (enabled != prefs.enabled) prefs.enabled = enabled
        _proxies.value = next.items
        _state.value = ProxyState(enabled, next.selectedId)
        if (fireRoute) _routeChanges.tryEmit(Unit)
    }
}
