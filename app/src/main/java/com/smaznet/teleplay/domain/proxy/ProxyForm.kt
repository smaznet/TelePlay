package com.smaznet.teleplay.domain.proxy

/** What the add/edit form holds, as typed. Parsing is here so it is testable without the UI. */
data class ProxyForm(
    val kind: ProxyKind = ProxyKind.SOCKS5,
    val server: String = "",
    val port: String = "",
    val name: String = "",
    val username: String = "",
    val password: String = "",
    val secret: String = "",
) {
    /** Redacted: credentials and secrets must never reach a log. */
    override fun toString() = "ProxyForm($kind)"
}

enum class FormError { SERVER, PORT, CREDENTIALS, SECRET, NAME }

sealed interface FormResult {
    class Ok(val draft: ProxyDraft) : FormResult
    class Bad(val error: FormError) : FormResult
}

/** The three MTProto secret kinds, for the "detected" label under the secret field. */
enum class SecretKind { PLAIN, PADDED, FAKE_TLS }

object ProxyFormLogic {
    /** A pasted proxy link as a filled-in form, or null if [text] is not one valid link. */
    fun fromLink(text: String): ProxyForm? {
        val link = ProxyLinkParser.extractLinks(text).firstOrNull() ?: text.trim()
        val d = (ProxyLinkParser.parse(link) as? ParsedLink.Valid)?.draft ?: return null
        return fromDraft(d)
    }

    fun fromDraft(d: ProxyDraft, secretText: String? = null) = ProxyForm(
        kind = d.kind, server = d.server, port = d.port.toString(), name = d.name.orEmpty(),
        username = d.username.orEmpty(), password = d.password.orEmpty(),
        secret = secretText ?: d.secret?.let(::secretText).orEmpty(),
    )

    /** The secret as hex, the form every client accepts. */
    fun secretText(s: MtSecret): String = when (s) {
        is MtSecret.Plain -> s.key.hex()
        is MtSecret.Padded -> "dd" + s.key.hex()
        is MtSecret.FakeTls -> "ee" + s.key.hex() + s.domain.toByteArray(Charsets.US_ASCII).hex()
    }

    fun secretKind(text: String): SecretKind? = when (ProxyLinkParser.parseSecret(text)) {
        is MtSecret.Plain -> SecretKind.PLAIN
        is MtSecret.Padded -> SecretKind.PADDED
        is MtSecret.FakeTls -> SecretKind.FAKE_TLS
        null -> null
    }

    fun coverDomain(text: String): String? = (ProxyLinkParser.parseSecret(text) as? MtSecret.FakeTls)?.domain

    fun toDraft(f: ProxyForm): FormResult {
        val server = f.server.trim()
        if (server.isEmpty() || !ProxyLimits.isHostOrIp(server) || server.length > ProxyLimits.MAX_HOST) return FormResult.Bad(FormError.SERVER)
        val port = f.port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return FormResult.Bad(FormError.PORT)
        val name = f.name.trim().takeIf { it.isNotEmpty() }
        if (name != null && name.length > ProxyLimits.MAX_NAME) return FormResult.Bad(FormError.NAME)
        val draft = when (f.kind) {
            ProxyKind.SOCKS5 -> {
                val user = f.username.takeIf { it.isNotEmpty() }
                val pass = f.password.takeIf { it.isNotEmpty() }
                if ((user == null) != (pass == null)) return FormResult.Bad(FormError.CREDENTIALS)
                ProxyDraft(ProxyKind.SOCKS5, server, port, name, user, pass)
            }
            ProxyKind.MTPROTO -> {
                val secret = ProxyLinkParser.parseSecret(f.secret) ?: return FormResult.Bad(FormError.SECRET)
                ProxyDraft(ProxyKind.MTPROTO, server, port, name, secret = secret)
            }
        }
        ProxyLimits.problem(draft)?.let { return FormResult.Bad(FormError.CREDENTIALS) }
        return FormResult.Ok(draft)
    }
}
