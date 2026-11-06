package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.data.secure.SecretStore
import com.smaznet.teleplay.mtproto.session.DcSessionData

/** Cursor of the update catch-up loop (data-model UpdateState). */
data class UpdateState(val pts: Int, val qts: Int, val date: Int, val seq: Int)

interface UpdateStateStore {
    fun load(): UpdateState?
    fun save(state: UpdateState)
    fun clear()
}

/** Persists a refreshed file reference for a stored video. */
fun interface FileReferenceStore {
    fun update(videoId: Long, fileReference: ByteArray)
}

/** Per-DC sessions (auth keys) and the home DC id. Implementations must keep these secret. */
interface SessionStore {
    fun load(dcId: Int): DcSessionData?
    fun save(data: DcSessionData)
    fun clearAll()
    fun clear(dcId: Int)
    fun loadHomeDc(): Int?
    fun saveHomeDc(dcId: Int)
}

interface TokenStore {
    fun load(): String?
    fun save(token: String)
    fun clear()
}

/** [SessionStore] backed by the Keystore-encrypted [SecretStore]. */
class SecretSessionStore(private val secrets: SecretStore) : SessionStore {
    override fun load(dcId: Int): DcSessionData? =
        secrets.get("dcsession_$dcId")?.let { runCatching { DcSessionData.fromBytes(it) }.getOrNull() }

    override fun save(data: DcSessionData) = secrets.put("dcsession_${data.dcId}", data.toBytes())

    override fun clear(dcId: Int) = secrets.remove("dcsession_$dcId")

    override fun clearAll() {
        for (dc in 1..5) clear(dc)
        secrets.remove("home_dc")
    }

    override fun loadHomeDc(): Int? = secrets.get("home_dc")?.toString(Charsets.UTF_8)?.toIntOrNull()

    override fun saveHomeDc(dcId: Int) = secrets.put("home_dc", dcId.toString().toByteArray())
}

/** [TokenStore] backed by [SecretStore]. */
class SecretTokenStore(private val secrets: SecretStore) : TokenStore {
    override fun load(): String? = secrets.get("bot_token")?.toString(Charsets.UTF_8)
    override fun save(token: String) = secrets.put("bot_token", token.toByteArray(Charsets.UTF_8))
    override fun clear() = secrets.remove("bot_token")
}
