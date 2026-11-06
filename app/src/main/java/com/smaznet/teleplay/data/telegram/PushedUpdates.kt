package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.domain.remote.CallbackQuery
import com.smaznet.teleplay.mtproto.tl.TlReader
import com.smaznet.teleplay.mtproto.tl.generated.*

/**
 * Extracts what the remote needs from a pushed `Updates` body: bot callback queries (which
 * `updates.getDifference` does not replay) and the access hashes of the users listed alongside.
 * Anything else is ignored; parse failures return null so the caller falls back to the plain poke.
 */
object PushedUpdates {
    class Result(val callbacks: List<CallbackQuery>, val userHashes: Map<Long, Long>)

    fun parse(body: ByteArray): Result? = try {
        val (updates, users) = when (val u = T_Updates.read(TlReader(body))) {
            is C_updateShort -> listOf(u.update) to emptyList()
            is C_updates -> u.updates to u.users
            is C_updatesCombined -> u.updates to u.users
            else -> null
        } ?: return null
        val callbacks = updates.mapNotNull { up ->
            (up as? C_updateBotCallbackQuery)?.let {
                if (it.peer !is C_peerUser) null
                else CallbackQuery(it.query_id, it.user_id, it.msg_id, it.data?.toString(Charsets.UTF_8).orEmpty())
            }
        }
        Result(callbacks, userHashes(users))
    } catch (e: Exception) {
        null
    }

    /** `user id -> access hash` for full (non-min) user objects. */
    fun userHashes(users: List<T_User>): Map<Long, Long> =
        users.mapNotNull { (it as? C_user)?.takeIf { u -> !u.min }?.let { u -> u.access_hash?.let { h -> u.id to h } } }.toMap()
}
