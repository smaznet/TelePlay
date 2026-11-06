package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.mtproto.Rpc
import com.smaznet.teleplay.mtproto.tl.generated.C_auth_authorization
import com.smaznet.teleplay.mtproto.tl.generated.C_user
import com.smaznet.teleplay.mtproto.tl.generated.F_auth_importBotAuthorization

/**
 * `auth.importBotAuthorization` on the bootstrap DC. [rpc] follows `USER_MIGRATE_X` (and updates its
 * `homeDc`). The token is never logged or included in errors.
 */
class BotSignIn(private val rpc: Rpc, private val apiId: Int, private val apiHash: String) {
    suspend fun signIn(token: String): BotInfo {
        val t = normalize(token) ?: throw TelegramError.TokenRejected()
        val res = rpc.invoke(F_auth_importBotAuthorization(0, apiId, apiHash, t))
        val auth = res as? C_auth_authorization ?: throw TelegramError.Unknown(-1, "unexpected authorization")
        val user = auth.user as? C_user ?: throw TelegramError.Unknown(-1, "unexpected user")
        val name = user.first_name?.takeIf { it.isNotBlank() } ?: user.username ?: "bot"
        return BotInfo(user.id, name)
    }

    companion object {
        private val FORMAT = Regex("\\d{3,20}:[A-Za-z0-9_-]{10,}")

        /** Trimmed token, or null when it is not `digits:string`. */
        fun normalize(raw: String): String? = raw.trim().takeIf { FORMAT.matches(it) }
    }
}
