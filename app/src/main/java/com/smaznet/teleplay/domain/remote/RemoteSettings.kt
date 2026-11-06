package com.smaznet.teleplay.domain.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tiny key/value seam so settings are testable without Android. */
interface BooleanPrefs {
    fun get(key: String, default: Boolean): Boolean
    fun put(key: String, value: Boolean)
}

class SharedBooleanPrefs(context: Context) : BooleanPrefs {
    private val prefs = context.getSharedPreferences("teleplay_remote", Context.MODE_PRIVATE)
    override fun get(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun put(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
}

/** The two independent switches of the Telegram remote (both on by default). */
class RemoteSettings(private val prefs: BooleanPrefs) {
    private val _notifyAdded = MutableStateFlow(prefs.get(NOTIFY_ADDED, true))
    private val _liveRemote = MutableStateFlow(prefs.get(LIVE_REMOTE, true))

    val notifyAdded: StateFlow<Boolean> = _notifyAdded.asStateFlow()
    val liveRemote: StateFlow<Boolean> = _liveRemote.asStateFlow()

    fun setNotifyAdded(on: Boolean) { prefs.put(NOTIFY_ADDED, on); _notifyAdded.value = on }
    fun setLiveRemote(on: Boolean) { prefs.put(LIVE_REMOTE, on); _liveRemote.value = on }

    private companion object {
        const val NOTIFY_ADDED = "notify_added"
        const val LIVE_REMOTE = "live_remote"
    }
}
