package com.smaznet.teleplay.mtproto.transport

import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * How far this device's clock is from real time, for the fake-TLS handshake only. A fake-TLS proxy rejects (and
 * answers like an ordinary website to) a client whose timestamp is a few minutes off, and TV clocks often are.
 * The offset is learned once, from the HTTP `Date` header of the proxy's cover site, then kept.
 */
object ProxyClock {
    @Volatile
    var offsetSeconds: Long = 0
        private set

    /** Called whenever the offset changes, so it can be persisted. */
    @Volatile
    var onChanged: (Long) -> Unit = {}

    /** Server time in seconds for [host], or null when it cannot be learned. Replaceable in tests. */
    @Volatile
    var estimator: (host: String) -> Long? = ::httpDate

    fun restore(seconds: Long) { offsetSeconds = seconds }

    fun nowSeconds(): Long = System.currentTimeMillis() / 1000 + offsetSeconds

    /** Learns the offset from [hosts] (first one that answers). True when the offset changed noticeably. */
    fun learn(hosts: List<String>): Boolean {
        for (h in hosts) {
            val server = estimator(h) ?: continue
            val offset = server - System.currentTimeMillis() / 1000
            if (Math.abs(offset - offsetSeconds) <= 2) return false
            offsetSeconds = offset
            onChanged(offset)
            return true
        }
        return false
    }

    /** The `Date` header of `http://host/` (no redirect followed), falling back to `https://host/`. */
    fun httpDate(host: String): Long? {
        for (scheme in listOf("http", "https")) {
            try {
                val c = URL("$scheme://$host/").openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"
                c.instanceFollowRedirects = false
                c.connectTimeout = 4_000
                c.readTimeout = 4_000
                try {
                    val date = c.getHeaderField("Date") ?: continue
                    val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
                    return f.parse(date)!!.time / 1000
                } finally { c.disconnect() }
            } catch (_: Exception) {
            }
        }
        return null
    }
}
