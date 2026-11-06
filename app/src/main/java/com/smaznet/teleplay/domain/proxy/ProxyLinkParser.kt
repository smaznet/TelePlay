package com.smaznet.teleplay.domain.proxy

import java.net.URLDecoder

sealed interface ParsedLink {
    class Valid(val draft: ProxyDraft) : ParsedLink
    class Invalid(val reason: String) : ParsedLink
}

/**
 * Proxy links (contracts/proxy-links.md): `t.me/socks`, `t.me/proxy`, `tg://socks`, `tg://proxy`.
 * Never throws and never logs; a bad link is [ParsedLink.Invalid].
 */
object ProxyLinkParser {
    private val LINK = Regex(
        "(?:https?://(?:t|telegram)\\.(?:me|dog)/|tg://)(?:socks|proxy)\\?[^\\s<>\"'()\\[\\]]+",
        RegexOption.IGNORE_CASE,
    )

    /** Every proxy-looking link in free text, in order. */
    fun extractLinks(text: String): List<String> =
        LINK.findAll(text).map { it.value.trimEnd('.', ',', ';', ')') }.toList()

    fun parse(link: String): ParsedLink {
        val s = link.trim()
        val lower = s.lowercase()
        val (kind, query) = when {
            Regex("^https?://(t|telegram)\\.(me|dog)/socks\\?").containsMatchIn(lower) -> ProxyKind.SOCKS5 to s.substringAfter('?')
            Regex("^https?://(t|telegram)\\.(me|dog)/proxy\\?").containsMatchIn(lower) -> ProxyKind.MTPROTO to s.substringAfter('?')
            lower.startsWith("tg://socks?") -> ProxyKind.SOCKS5 to s.substringAfter('?')
            lower.startsWith("tg://proxy?") -> ProxyKind.MTPROTO to s.substringAfter('?')
            else -> return ParsedLink.Invalid("scheme")
        }
        val params = HashMap<String, String>()
        for (pair in query.substringBefore('#').split('&')) {
            if (pair.isEmpty()) continue
            val k = decode(pair.substringBefore('=')).lowercase()
            val v = decode(pair.substringAfter('=', ""))
            if (k !in params) params[k] = v
        }
        val server = params["server"]?.trim().orEmpty()
        val port = params["port"]?.trim()?.toIntOrNull() ?: return ParsedLink.Invalid("port")
        val draft = when (kind) {
            ProxyKind.SOCKS5 -> {
                val user = params["user"]?.takeIf { it.isNotEmpty() }
                val pass = params["pass"]?.takeIf { it.isNotEmpty() }
                if ((user == null) != (pass == null)) return ParsedLink.Invalid("credentials")
                ProxyDraft(kind, server, port, username = user, password = pass)
            }
            ProxyKind.MTPROTO -> {
                val secret = parseSecret(params["secret"].orEmpty()) ?: return ParsedLink.Invalid("secret")
                ProxyDraft(kind, server, port, secret = secret)
            }
        }
        ProxyLimits.problem(draft)?.let { return ParsedLink.Invalid(it) }
        return ParsedLink.Valid(draft)
    }

    /** Hex or base64url secret → [MtSecret], or null when it is not one of the three valid shapes. */
    fun parseSecret(raw: String): MtSecret? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val bytes = decodeHex(text) ?: decodeBase64Url(text) ?: return null
        return when {
            bytes.size == MtSecret.KEY_SIZE -> MtSecret.Plain(bytes)
            bytes.size == MtSecret.KEY_SIZE + 1 && bytes[0] == 0xdd.toByte() -> MtSecret.Padded(bytes.copyOfRange(1, 17))
            bytes.size > MtSecret.KEY_SIZE + 1 && bytes[0] == 0xee.toByte() -> {
                val domainBytes = bytes.copyOfRange(17, bytes.size)
                if (domainBytes.any { it < 0x21 || it > 0x7e }) return null
                val domain = String(domainBytes, Charsets.US_ASCII)
                if (!ProxyLimits.isHostOrIp(domain)) return null
                MtSecret.FakeTls(bytes.copyOfRange(1, 17), domain)
            }
            else -> null
        }
    }

    private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private fun decodeHex(s: String): ByteArray? {
        if (s.length % 2 != 0 || !s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** minSdk 21 has no java.util.Base64, so decode by hand. */
    private fun decodeBase64Url(s: String): ByteArray? {
        val t = s.replace('+', '-').replace('/', '_').trimEnd('=')
        if (t.isEmpty() || t.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream()
        var acc = 0
        var bits = 0
        for (c in t) {
            val v = B64.indexOf(c)
            if (v < 0) return null
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) { bits -= 8; out.write((acc shr bits) and 0xff) }
        }
        return out.toByteArray()
    }
}
