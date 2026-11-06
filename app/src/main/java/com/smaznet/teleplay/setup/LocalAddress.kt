package com.smaznet.teleplay.setup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address
import java.net.NetworkInterface

/** Supplies the TV's current site-local IPv4 address, or null when there is no local network. */
fun interface LocalAddressSource {
    fun current(): String?
}

/** Address source plus a flow that re-emits when connectivity changes. */
interface LocalAddressProvider : LocalAddressSource {
    fun changes(): Flow<String?>
}

class AndroidLocalAddress(context: Context) : LocalAddressProvider {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun current(): String? = fromActiveNetwork() ?: fromInterfaces()

    @Suppress("DEPRECATION")
    private fun fromActiveNetwork(): String? = runCatching {
        val net = cm.activeNetwork ?: return null
        cm.getLinkProperties(net)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun fromInterfaces(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    override fun changes(): Flow<String?> = callbackFlow {
        trySend(current())
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(current()) }
            override fun onLost(network: Network) { trySend(current()) }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) { trySend(current()) }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { trySend(current()) }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val registered = runCatching { cm.registerNetworkCallback(request, cb) }.isSuccess
        awaitClose { if (registered) runCatching { cm.unregisterNetworkCallback(cb) } }
    }.distinctUntilChanged()
}
