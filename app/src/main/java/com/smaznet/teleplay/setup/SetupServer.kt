package com.smaznet.teleplay.setup

import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.domain.proxy.FormResult
import com.smaznet.teleplay.domain.proxy.ParsedLink
import com.smaznet.teleplay.domain.proxy.ProxyDraft
import com.smaznet.teleplay.domain.proxy.ProxyForm
import com.smaznet.teleplay.domain.proxy.ProxyFormLogic
import com.smaznet.teleplay.domain.proxy.ProxyKind
import com.smaznet.teleplay.domain.proxy.ProxyLinkParser
import com.smaznet.teleplay.ui.token.TokenError
import com.smaznet.teleplay.ui.token.tokenErrorFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/** Thrown by a submit callback when another token submission (e.g. manual entry) is already in progress. */
class SubmitBusyException : Exception("A token submission is already in progress")

/** What happened to a proxy sent from the phone page. Carries only the display label, never details. */
sealed interface ProxySubmitResult {
    class Added(val name: String, val duplicate: Boolean = false) : ProxySubmitResult
    data object LimitReached : ProxySubmitResult
    data object Failed : ProxySubmitResult
}

/**
 * The tiny phone-facing HTTP server (contracts/setup-http.md). Single-purpose: two routes, one request per
 * connection, LAN peers only, single-use secret, per-peer throttle. It never logs: not requests, not the
 * token, not the secret.
 *
 * [session] can be swapped while running (secret rotation); previous sessions stay known so their links answer
 * 410 rather than 404.
 */
class SetupServer(
    session: SetupSession,
    private val submit: suspend (String) -> Result<BotInfo>,
    private val addressSource: LocalAddressSource,
    private val port: Int = 0,
    private val clock: () -> Long = System::currentTimeMillis,
    private val peerAllowed: (InetAddress) -> Boolean = ::isLanPeer,
    private val submitTimeoutMs: Long = SUBMIT_TIMEOUT_MS,
) {
    @Volatile
    var session: SetupSession = session
        private set

    /** Stores a proxy sent from the phone page (and, in the setup context, switches it on). Does not use up the session. */
    @Volatile
    var onProxy: suspend (ProxyDraft) -> ProxySubmitResult = { ProxySubmitResult.Failed }

    private val retired = ArrayDeque<SetupSession>()
    private val throttle = HashMap<String, ThrottleEntry>()
    private val submitLock = Mutex()
    private val workers = Semaphore(MAX_CONNECTIONS)
    private val open = HashSet<Socket>()

    private var scope: CoroutineScope? = null
    private var listener: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    var boundPort: Int = 0
        private set

    private class ThrottleEntry(var windowStart: Long, var failures: Int = 0, var blockedUntil: Long = 0L)

    /** Replaces the active session; the old one keeps answering 410. Port and listener are untouched. */
    fun rotate(next: SetupSession) {
        synchronized(retired) {
            retired.addLast(session)
            while (retired.size > MAX_RETIRED) retired.removeFirst()
        }
        session = next
    }

    /** Binds the listening socket and starts accepting. Returns the bound port. */
    @Synchronized
    fun start(): Int {
        listener?.let { return boundPort }
        val server = ServerSocket(port)
        listener = server
        boundPort = server.localPort
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        acceptJob = s.launch {
            while (true) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                synchronized(open) { open.add(socket) }
                launch {
                    workers.acquire()
                    try { handle(socket) } finally {
                        workers.release()
                        synchronized(open) { open.remove(socket) }
                        runCatching { socket.close() }
                    }
                }
            }
        }
        return boundPort
    }

    /** Closes the listener and every in-flight connection. Idempotent. */
    @Synchronized
    fun stop() {
        runCatching { listener?.close() }
        listener = null
        synchronized(open) { open.forEach { runCatching { it.close() } }; open.clear() }
        scope?.cancel()
        scope = null
        acceptJob = null
    }

    // --- request handling --------------------------------------------------------------------------

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private class HttpError(val status: Int) : Exception()

    private suspend fun handle(socket: Socket) {
        try {
            if (!peerAllowed(socket.inetAddress)) return // silent close
            socket.soTimeout = READ_TIMEOUT_MS
            val peer = socket.inetAddress.hostAddress.orEmpty()
            val out = socket.getOutputStream()
            if (isBlocked(peer)) {
                respond(out, 429, extraHeaders = mapOf("Retry-After" to "60"))
                return
            }
            val req = try {
                readRequest(socket.getInputStream())
            } catch (e: HttpError) {
                respond(out, e.status)
                return
            }
            respond(out, route(req, peer))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Timeouts and resets: drop the connection, say nothing.
        }
    }

    private class Reply(val status: Int, val contentType: String? = null, val body: ByteArray = ByteArray(0))

    private fun json(status: Int, body: String) = Reply(status, "application/json; charset=utf-8", body.toByteArray())

    private fun reason(status: Int, reason: String) = json(status, "{\"ok\":false,\"reason\":\"$reason\"}")

    private suspend fun route(req: Request, peer: String): Reply {
        val advertised = addressSource.current()?.let { "$it:$boundPort" }
        if (advertised == null || req.headers["host"] != advertised) return Reply(400)

        val segments = req.path.substringBefore('?').split('/').filter { it.isNotEmpty() }
        val isPage = req.method == "GET" && segments.size == 2 && segments[0] == "s"
        val isToken = req.method == "POST" && segments.size == 3 && segments[0] == "s" && segments[2] == "token"
        val isProxy = req.method == "POST" && segments.size == 3 && segments[0] == "s" && segments[2] == "proxy"
        if (!isPage && !isToken && !isProxy) return Reply(404)

        val candidate = segments[1]
        val target = findSession(candidate)
        if (target == null) {
            recordSecretFailure(peer)
            return Reply(404)
        }
        val (matched, isCurrent) = target
        if (!isCurrent || !matched.isUsable) {
            return if (isPage) Reply(410, "text/html; charset=utf-8", GONE_PAGE.toByteArray()) else reason(410, "expired")
        }

        if (isPage) {
            matched.onPageViewed()
            return Reply(200, "text/html; charset=utf-8", SetupPage.html.toByteArray())
        }
        if (isProxy) return submitProxy(req.body)
        return submitToken(matched, req.body)
    }

    /** Proxy submission: same secret match and throttle as the token route, but the session stays usable (research R9). */
    private suspend fun submitProxy(body: ByteArray): Reply {
        val draft = when (val d = proxyDraftFrom(body)) {
            null -> return reason(422, "format")
            else -> d
        }
        return when (val r = onProxy(draft)) {
            is ProxySubmitResult.Added -> json(200, "{\"ok\":true,\"duplicate\":${r.duplicate}}")
            ProxySubmitResult.LimitReached -> reason(422, "limit")
            ProxySubmitResult.Failed -> reason(422, "error")
        }
    }

    /** Constant-time match against the current and retired secrets (no early exit). */
    private fun findSession(candidate: String): Pair<SetupSession, Boolean>? {
        val current = session
        var hit: Pair<SetupSession, Boolean>? = null
        if (current.matches(candidate)) hit = current to true
        val old = synchronized(retired) { retired.toList() }
        for (r in old) if (r.matches(candidate) && hit == null) hit = r to false
        return hit
    }

    private suspend fun submitToken(s: SetupSession, body: ByteArray): Reply {
        val token = parseToken(body)?.trim() ?: return Reply(400)
        if (!TOKEN_SHAPE.matches(token)) return reason(422, "format")
        if (!submitLock.tryLock()) return reason(409, "busy")
        try {
            if (!s.beginSubmit()) return if (s.isUsable) reason(409, "busy") else reason(410, "expired")
            val result = try {
                withTimeout(submitTimeoutMs) { submit(token) }
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) Result.failure(IllegalStateException())
                else { s.onFailed(SetupError.OTHER); throw e }
            }
            if (result.isSuccess) {
                s.onAccepted()
                return json(200, "{\"ok\":true}")
            }
            val failure = result.exceptionOrNull()
            if (failure is SubmitBusyException) {
                s.onFailed(SetupError.BUSY)
                return reason(409, "busy")
            }
            val (error, name) = when (tokenErrorFor(failure ?: IllegalStateException())) {
                TokenError.Invalid -> SetupError.REJECTED to "rejected"
                TokenError.Offline -> SetupError.OFFLINE to "offline"
                is TokenError.RateLimited -> SetupError.RATE_LIMITED to "rate_limited"
                else -> SetupError.OTHER to "error"
            }
            s.onFailed(error)
            return reason(422, name)
        } finally {
            submitLock.unlock()
        }
    }

    // --- throttle ----------------------------------------------------------------------------------

    private fun isBlocked(peer: String): Boolean = synchronized(throttle) {
        val e = throttle[peer] ?: return false
        if (e.blockedUntil > clock()) true else {
            if (e.blockedUntil != 0L) throttle.remove(peer)
            false
        }
    }

    private fun recordSecretFailure(peer: String) = synchronized(throttle) {
        val now = clock()
        val e = throttle.getOrPut(peer) { ThrottleEntry(now) }
        if (now - e.windowStart > THROTTLE_WINDOW_MS) { e.windowStart = now; e.failures = 0 }
        e.failures++
        if (e.failures >= MAX_SECRET_FAILURES) e.blockedUntil = now + THROTTLE_WINDOW_MS
    }

    // --- HTTP plumbing -----------------------------------------------------------------------------

    private fun readRequest(input: InputStream): Request {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw HttpError(400)
            head.write(b)
            if (head.size() > MAX_HEADER_BYTES) throw HttpError(431)
            matched = if (b == TERMINATOR[matched]) matched + 1 else if (b == '\r'.code) 1 else 0
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val parts = lines.firstOrNull()?.split(' ') ?: throw HttpError(400)
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400)
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i <= 0) throw HttpError(400)
            headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        var body = ByteArray(0)
        if (parts[0] == "POST") {
            if (headers.containsKey("transfer-encoding")) throw HttpError(400)
            val len = headers["content-length"]?.toIntOrNull() ?: throw HttpError(400)
            if (len < 0) throw HttpError(400)
            if (len > MAX_BODY_BYTES) throw HttpError(413)
            body = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = input.read(body, read, len - read)
                if (n < 0) throw HttpError(400)
                read += n
            }
        }
        return Request(parts[0], parts[1], headers, body)
    }

    private fun respond(out: java.io.OutputStream, status: Int, extraHeaders: Map<String, String> = emptyMap()) =
        respond(out, Reply(status), extraHeaders)

    private fun respond(out: java.io.OutputStream, reply: Reply, extraHeaders: Map<String, String> = emptyMap()) {
        val sb = StringBuilder("HTTP/1.1 ${reply.status} ${statusText(reply.status)}\r\n")
        sb.append("Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n")
        reply.contentType?.let { sb.append("Content-Type: $it\r\n") }
        for ((k, v) in extraHeaders) sb.append("$k: $v\r\n")
        sb.append("Content-Length: ${reply.body.size}\r\n\r\n")
        try {
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(reply.body)
            out.flush()
        } catch (_: SocketException) {
        }
    }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 409 -> "Conflict"; 410 -> "Gone"
        413 -> "Payload Too Large"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"; else -> "Error"
    }

    companion object {
        const val SUBMIT_TIMEOUT_MS = 20_000L
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 4 * 1024
        private const val MAX_CONNECTIONS = 8
        private const val MAX_RETIRED = 4
        private const val MAX_SECRET_FAILURES = 5
        private const val THROTTLE_WINDOW_MS = 60_000L
        private val TERMINATOR = intArrayOf('\r'.code, '\n'.code, '\r'.code, '\n'.code)

        /** Loose bot-token shape: `<digits>:<url-safe chars>`. Real validation is Telegram's. */
        private val TOKEN_SHAPE = Regex("^[0-9]{3,20}:[A-Za-z0-9_-]{20,64}$")

        private const val GONE_PAGE = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
            "<title>TelePlay setup</title><body style=\"font:18px system-ui;padding:24px\"><h1>This link expired</h1>" +
            "<p>Look at the TV for a new code.</p></body>"

        /** Loopback and private IPv4 ranges (10/8, 172.16/12, 192.168/16, 169.254/16). */
        fun isLanPeer(a: InetAddress): Boolean =
            a.isLoopbackAddress || (a is Inet4Address && (a.isSiteLocalAddress || a.isLinkLocalAddress))

        /** A proxy from either `{"link": ...}` or the field form; null when it is not a valid SOCKS5 or MTProto proxy. */
        internal fun proxyDraftFrom(body: ByteArray): ProxyDraft? {
            val m = parseFlatJson(body) ?: return null
            m["link"]?.let { text ->
                val link = ProxyLinkParser.extractLinks(text).firstOrNull() ?: text.trim()
                return (ProxyLinkParser.parse(link) as? ParsedLink.Valid)?.draft
            }
            val kind = when (m["kind"]?.lowercase()) {
                "socks5", "socks" -> ProxyKind.SOCKS5
                "mtproto", "proxy" -> ProxyKind.MTPROTO
                else -> return null
            }
            val form = ProxyForm(
                kind = kind, server = m["server"].orEmpty(), port = m["port"].orEmpty(), name = m["name"].orEmpty(),
                username = m["user"].orEmpty(), password = m["pass"].orEmpty(), secret = m["secret"].orEmpty(),
            )
            return (ProxyFormLogic.toDraft(form) as? FormResult.Ok)?.draft
        }

        /** A flat JSON object whose values are strings or numbers, as text; null when malformed. */
        internal fun parseFlatJson(body: ByteArray): Map<String, String>? {
            val s = body.toString(Charsets.UTF_8)
            var i = 0
            fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
            fun str(): String? {
                if (i >= s.length || s[i] != '"') return null
                i++
                val sb = StringBuilder()
                while (i < s.length) {
                    val c = s[i++]
                    when (c) {
                        '"' -> return sb.toString()
                        '\\' -> {
                            if (i >= s.length) return null
                            when (val e = s[i++]) {
                                '"', '\\', '/' -> sb.append(e)
                                'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                                'u' -> {
                                    if (i + 4 > s.length) return null
                                    sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: return null)
                                    i += 4
                                }
                                else -> return null
                            }
                        }
                        else -> sb.append(c)
                    }
                }
                return null
            }
            ws()
            if (i >= s.length || s[i] != '{') return null
            i++
            val out = HashMap<String, String>()
            while (true) {
                ws()
                if (i < s.length && s[i] == '}') { i++; break }
                val key = str() ?: return null
                ws()
                if (i >= s.length || s[i] != ':') return null
                i++
                ws()
                val value = if (i < s.length && s[i] == '"') str() ?: return null else {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '.')) i++
                    if (i == start) return null
                    s.substring(start, i)
                }
                out[key] = value
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                ws()
                if (i < s.length && s[i] == '}') { i++; break }
                return null
            }
            ws()
            return if (i == s.length) out else null
        }

        /** Extracts the string value of `"token"` from a flat JSON object, or null when malformed. */
        internal fun parseToken(body: ByteArray): String? {
            val s = body.toString(Charsets.UTF_8)
            var i = 0
            fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
            fun str(): String? {
                if (i >= s.length || s[i] != '"') return null
                i++
                val sb = StringBuilder()
                while (i < s.length) {
                    val c = s[i++]
                    when (c) {
                        '"' -> return sb.toString()
                        '\\' -> {
                            if (i >= s.length) return null
                            when (val e = s[i++]) {
                                '"', '\\', '/' -> sb.append(e)
                                'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                                'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                                'u' -> {
                                    if (i + 4 > s.length) return null
                                    sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: return null)
                                    i += 4
                                }
                                else -> return null
                            }
                        }
                        else -> sb.append(c)
                    }
                }
                return null
            }
            ws()
            if (i >= s.length || s[i] != '{') return null
            i++
            var token: String? = null
            while (true) {
                ws()
                if (i < s.length && s[i] == '}') { i++; break }
                val key = str() ?: return null
                ws()
                if (i >= s.length || s[i] != ':') return null
                i++
                ws()
                val value = str() ?: return null
                if (key == "token") token = value
                ws()
                if (i < s.length && s[i] == ',') i++ else if (i < s.length && s[i] == '}') { i++; break } else return null
            }
            ws()
            return if (i == s.length) token else null
        }
    }
}
