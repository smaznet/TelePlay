package com.smaznet.teleplay.data.telegram

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/** Calls [onAvailable] whenever a network with internet access becomes available (e.g. after being offline). */
class ConnectivityWatcher(context: Context, private val onAvailable: () -> Unit) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onAvailable()
    }

    fun start() {
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (_: RuntimeException) {
            // Some TV firmwares limit callbacks; the 60 s poll still recovers.
        }
    }

    fun stop() {
        try { cm.unregisterNetworkCallback(callback) } catch (_: RuntimeException) {}
    }
}
