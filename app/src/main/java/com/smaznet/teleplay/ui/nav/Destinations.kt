package com.smaznet.teleplay.ui.nav

import com.smaznet.teleplay.domain.ConnectionState

sealed interface Route {
    data object Token : Route
    data object Pairing : Route
    data object Library : Route
    data object Settings : Route
    data class Proxies(val startWithAdd: Boolean = false, val enableOnAdd: Boolean = false) : Route
    data object Revoked : Route
    data class Player(val videoId: Long, val startFromBeginning: Boolean) : Route
}

/** Start destination for a persisted connection state. */
fun startRoute(state: ConnectionState): Route = when (state) {
    ConnectionState.SIGNED_OUT, ConnectionState.REJECTED -> Route.Token
    ConnectionState.AWAITING_PAIRING -> Route.Pairing
    ConnectionState.READY, ConnectionState.OFFLINE, ConnectionState.CONNECTING -> Route.Library
    ConnectionState.REVOKED -> Route.Revoked
}

/**
 * Global reaction to a connection state change: the new root route, or null to stay where we are.
 * CONNECTING never moves the user (a sign-in attempt must keep showing the Token screen).
 */
fun reconcile(state: ConnectionState, current: Route): Route? = when (state) {
    ConnectionState.CONNECTING -> null
    ConnectionState.SIGNED_OUT -> Route.Token.takeIf { current != Route.Token }
    ConnectionState.AWAITING_PAIRING -> Route.Pairing.takeIf { current != Route.Pairing }
    ConnectionState.READY, ConnectionState.OFFLINE ->
        Route.Library.takeIf { current == Route.Token || current == Route.Pairing || current == Route.Revoked }
    ConnectionState.REVOKED, ConnectionState.REJECTED ->
        Route.Revoked.takeIf { current != Route.Revoked && current != Route.Token }
}
