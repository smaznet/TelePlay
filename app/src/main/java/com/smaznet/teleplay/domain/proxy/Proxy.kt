package com.smaznet.teleplay.domain.proxy

enum class ProxyKind { SOCKS5, MTPROTO }

/** An MTProto proxy secret in one of its three forms (research R4). The key bytes are a secret. */
sealed interface MtSecret {
    val key: ByteArray

    class Plain(override val key: ByteArray) : MtSecret {
        init { require(key.size == KEY_SIZE) }
        override fun toString() = "MtSecret.Plain(redacted)"
    }

    /** `dd` prefix: random padding per frame. */
    class Padded(override val key: ByteArray) : MtSecret {
        init { require(key.size == KEY_SIZE) }
        override fun toString() = "MtSecret.Padded(redacted)"
    }

    /** `ee` prefix: fake TLS towards [domain]. */
    class FakeTls(override val key: ByteArray, val domain: String) : MtSecret {
        init { require(key.size == KEY_SIZE); require(domain.isNotEmpty() && domain.length <= MAX_HOST) }
        override fun toString() = "MtSecret.FakeTls(redacted)"
    }

    /** The bytes that identify this secret for de-duplication, kind included. */
    fun identity(): String = when (this) {
        is Plain -> "p:" + key.hex()
        is Padded -> "d:" + key.hex()
        is FakeTls -> "e:" + key.hex() + ":" + domain.lowercase()
    }

    companion object {
        const val KEY_SIZE = 16
        const val MAX_HOST = 253
    }
}

/** A proxy before it has an id (parsed from a link, a form or the phone page). */
class ProxyDraft(
    val kind: ProxyKind,
    val server: String,
    val port: Int,
    val name: String? = null,
    val username: String? = null,
    val password: String? = null,
    val secret: MtSecret? = null,
) {
    override fun toString() = "ProxyDraft($kind, redacted)"
}

/** Outcome of testing one proxy (never carries proxy details). */
sealed interface ProxyTestResult {
    data class Working(val latencyMs: Long) : ProxyTestResult
    data object AuthRejected : ProxyTestResult
    data object SecretRejected : ProxyTestResult
    data object NotReachable : ProxyTestResult
    data object TimedOut : ProxyTestResult

    val isWorking: Boolean get() = this is Working
}

/** A saved route to Telegram. Credentials and secrets are secrets: [toString] prints neither. */
class Proxy(
    val id: Long,
    val kind: ProxyKind,
    val server: String,
    val port: Int,
    val name: String?,
    val username: String?,
    val password: String?,
    val secret: MtSecret?,
    val lastTest: ProxyTestResult? = null,
    val lastTestAt: Long = 0L,
) {
    /** The display name; defaults to `server:port`. */
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: "$server:$port"

    /** Same proxy for de-duplication purposes (data-model: kind + lowercase server + port + credentials/secret). */
    fun dedupeKey(): String = dedupeKey(kind, server, port, username, password, secret)

    fun withTest(result: ProxyTestResult?, at: Long) =
        Proxy(id, kind, server, port, name, username, password, secret, result, at)

    override fun toString() = "Proxy(id=$id, $kind, redacted)"

    companion object {
        fun dedupeKey(kind: ProxyKind, server: String, port: Int, user: String?, pass: String?, secret: MtSecret?): String =
            when (kind) {
                ProxyKind.SOCKS5 -> "s|${server.lowercase()}|$port|${user.orEmpty()}|${pass.orEmpty()}"
                ProxyKind.MTPROTO -> "m|${server.lowercase()}|$port|${secret?.identity().orEmpty()}"
            }
    }
}

/** What the viewer chose: proxying on or off, and which saved proxy to use. */
data class ProxyState(val enabled: Boolean = false, val selectedId: Long? = null) {
    val active: Boolean get() = enabled && selectedId != null
}

/** Field limits from data-model.md. */
object ProxyLimits {
    const val MAX_PROXIES = 200
    const val MAX_NAME = 40
    const val MAX_CREDENTIAL_BYTES = 255
    const val MAX_HOST = 253

    /** Why a draft is unacceptable, or null when it is valid. */
    fun problem(d: ProxyDraft): String? {
        if (d.server.isEmpty() || d.server.length > MAX_HOST) return "server"
        if (!isHostOrIp(d.server)) return "server"
        if (d.port !in 1..65535) return "port"
        if (d.name != null && d.name.length > MAX_NAME) return "name"
        when (d.kind) {
            ProxyKind.SOCKS5 -> {
                if ((d.username == null) != (d.password == null)) return "credentials"
                if (d.username != null && d.username.isEmpty()) return "credentials"
                if ((d.username?.toByteArray()?.size ?: 0) > MAX_CREDENTIAL_BYTES) return "credentials"
                if ((d.password?.toByteArray()?.size ?: 0) > MAX_CREDENTIAL_BYTES) return "credentials"
                if (d.secret != null) return "secret"
            }
            ProxyKind.MTPROTO -> {
                if (d.secret == null) return "secret"
                if (d.username != null || d.password != null) return "credentials"
            }
        }
        return null
    }

    private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")
    private val IPV6 = Regex("^[0-9A-Fa-f:.]+$")

    fun isHostOrIp(s: String): Boolean = HOST.matches(s) || (s.contains(':') && IPV6.matches(s))
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
