package com.smaznet.teleplay.mtproto

import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.mtproto.session.DcConnection
import com.smaznet.teleplay.mtproto.session.RpcException
import com.smaznet.teleplay.mtproto.tl.generated.TL_LAYER
import com.smaznet.teleplay.mtproto.tl.TlException
import com.smaznet.teleplay.mtproto.tl.TlFunction
import com.smaznet.teleplay.mtproto.tl.TlReader
import com.smaznet.teleplay.mtproto.tl.generated.F_initConnection
import com.smaznet.teleplay.mtproto.tl.generated.F_invokeWithLayer
import kotlinx.coroutines.delay
import java.io.IOException

/** Static client description sent with the first call on each connection. */
class ClientInfo(
    val apiId: Int,
    val deviceModel: String = "Android TV",
    val systemVersion: String = "Android",
    val appVersion: String = "1.0",
    val langCode: String = "en",
)

/**
 * Invokes API methods: wraps the first call per connection in `invokeWithLayer(initConnection(..))`,
 * maps `rpc_error`s to [TelegramError], waits out FLOOD_WAIT, follows *_MIGRATE_X, and surfaces
 * FILE_REFERENCE_EXPIRED. Multiple requests may be in flight concurrently.
 */
class Rpc(
    private val connectionFor: suspend (dcId: Int) -> DcConnection,
    var homeDc: Int,
    private val client: ClientInfo,
    private val sleep: suspend (millis: Long) -> Unit = { delay(it) },
    /** Called with the wait in seconds when a flood wait longer than 5 s is being served. */
    private val onFloodWait: (Int) -> Unit = {},
    private val maxFloodRetries: Int = 3,
) {
    suspend fun <R> invoke(fn: TlFunction<R>, dcId: Int = homeDc): R {
        var dc = dcId
        var floodRetries = 0
        var migrations = 0
        while (true) {
            val conn = connectionFor(dc)
            try {
                val query: TlFunction<*> = if (conn.initialized) fn else wrap(fn)
                val bytes = conn.call(query)
                conn.initialized = true
                return fn.readResult(TlReader(bytes))
            } catch (e: RpcException) {
                val msg = e.rpcMessage
                val flood = FLOOD.matchEntire(msg)
                val migrate = MIGRATE.matchEntire(msg)
                when {
                    flood != null -> {
                        val secs = flood.groupValues[1].toInt()
                        if (floodRetries++ >= maxFloodRetries) throw TelegramError.RateLimited(secs)
                        if (secs > 5) onFloodWait(secs)
                        sleep(secs * 1000L)
                    }
                    migrate != null && migrations++ < 3 -> {
                        val target = migrate.groupValues[2].toInt()
                        if (migrate.groupValues[1] != "FILE") homeDc = target
                        dc = target
                    }
                    else -> throw map(e)
                }
            } catch (e: TlException) {
                throw TelegramError.Unknown(-1, "unparseable response")
            } catch (e: IOException) {
                throw TelegramError.Offline(e)
            }
        }
    }

    private fun wrap(fn: TlFunction<*>) = F_invokeWithLayer(
        TL_LAYER,
        F_initConnection(
            api_id = client.apiId,
            device_model = client.deviceModel,
            system_version = client.systemVersion,
            app_version = client.appVersion,
            system_lang_code = client.langCode,
            lang_pack = "",
            lang_code = client.langCode,
            query = fn,
        ),
    )

    private fun map(e: RpcException): TelegramError {
        val m = e.rpcMessage
        return when {
            m == "ACCESS_TOKEN_INVALID" || m == "ACCESS_TOKEN_EXPIRED" -> TelegramError.TokenRejected()
            m == "AUTH_KEY_UNREGISTERED" || m == "AUTH_KEY_INVALID" -> TelegramError.AuthKeyLost()
            m.startsWith("FILE_REFERENCE_") -> TelegramError.FileReferenceExpired()
            m == "LOCATION_INVALID" || m == "FILE_ID_INVALID" -> TelegramError.FileUnavailable()
            else -> TelegramError.Unknown(e.code, m)
        }
    }

    private companion object {
        val FLOOD = Regex("(?:FLOOD_WAIT|SLOWMODE_WAIT)_(\\d+)")
        val MIGRATE = Regex("(PHONE|USER|NETWORK|FILE|STATS)_MIGRATE_(\\d+)")
    }
}
