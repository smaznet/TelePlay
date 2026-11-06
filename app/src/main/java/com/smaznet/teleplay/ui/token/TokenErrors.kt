package com.smaznet.teleplay.ui.token

import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.mtproto.transport.ProxyException
import com.smaznet.teleplay.setup.SubmitBusyException

sealed interface TokenError {
    data object Empty : TokenError
    data object Invalid : TokenError
    data object Offline : TokenError
    /** Telegram is unreachable through the proxy that is switched on. */
    data object Proxy : TokenError
    data object Busy : TokenError
    data class RateLimited(val seconds: Int) : TokenError
    data object Other : TokenError
}

/** Maps a failure to a user-facing category. Never includes the token or the raw error text. */
fun tokenErrorFor(e: Throwable): TokenError = when (e) {
    is TelegramError.TokenRejected -> TokenError.Invalid
    is TelegramError.Offline -> if (e.cause is ProxyException) TokenError.Proxy else TokenError.Offline
    is TelegramError.RateLimited -> TokenError.RateLimited(e.retryAfterSec)
    is SubmitBusyException -> TokenError.Busy
    else -> TokenError.Other
}
