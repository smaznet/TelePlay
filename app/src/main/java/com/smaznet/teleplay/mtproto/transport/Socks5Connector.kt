package com.smaznet.teleplay.mtproto.transport

import com.smaznet.teleplay.domain.proxy.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

/**
 * SOCKS5 client (RFC 1928, with RFC 1929 username/password). Returns a socket already tunnelled to
 * [host]:[port]; the caller then speaks its own protocol over it. Never logs.
 */
object Socks5Connector {
    suspend fun connect(proxy: Proxy, host: String, port: Int, timeoutMs: Int = TcpTransport.CONNECT_TIMEOUT_MS): Socket = withContext(Dispatchers.IO) {
        val s = try {
            TcpTransport.openSocket(proxy.server, proxy.port, timeoutMs)
        } catch (e: IOException) {
            throw ProxyException(ProxyFailure.UNREACHABLE, e)
        }
        try {
            handshake(s, proxy, host, port)
            s.soTimeout = TcpTransport.READ_TIMEOUT_MS
            s
        } catch (e: ProxyException) {
            try { s.close() } catch (_: IOException) {}
            throw e
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            throw ProxyException(ProxyFailure.HANDSHAKE_FAILED, e)
        }
    }

    private fun handshake(s: Socket, proxy: Proxy, host: String, port: Int) {
        val out = s.getOutputStream()
        val inp = DataInputStream(s.getInputStream())
        val user = proxy.username
        val pass = proxy.password
        val withAuth = user != null && pass != null

        out.write(if (withAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        out.flush()
        if (inp.readUnsignedByte() != 5) throw ProxyException(ProxyFailure.HANDSHAKE_FAILED)
        when (inp.readUnsignedByte()) {
            0x00 -> Unit
            0x02 -> {
                if (!withAuth) throw ProxyException(ProxyFailure.AUTH_REJECTED)
                val u = user!!.toByteArray(Charsets.UTF_8)
                val p = pass!!.toByteArray(Charsets.UTF_8)
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                out.flush()
                inp.readUnsignedByte()
                if (inp.readUnsignedByte() != 0) throw ProxyException(ProxyFailure.AUTH_REJECTED)
            }
            else -> throw ProxyException(ProxyFailure.AUTH_REJECTED) // 0xff: no acceptable method
        }

        out.write(connectRequest(host, port))
        out.flush()
        if (inp.readUnsignedByte() != 5) throw ProxyException(ProxyFailure.HANDSHAKE_FAILED)
        val reply = inp.readUnsignedByte()
        inp.readUnsignedByte() // reserved
        val skip = when (inp.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            3 -> inp.readUnsignedByte()
            else -> throw ProxyException(ProxyFailure.HANDSHAKE_FAILED)
        }
        inp.readFully(ByteArray(skip + 2))
        when (reply) {
            0 -> Unit
            2, 5 -> throw ProxyException(ProxyFailure.REFUSED)
            else -> throw ProxyException(ProxyFailure.UNREACHABLE)
        }
    }

    internal fun connectRequest(host: String, port: Int): ByteArray {
        val head = byteArrayOf(5, 1, 0)
        val addr: ByteArray = literalAddress(host)?.let { a ->
            byteArrayOf(if (a.size == 4) 1 else 4) + a
        } ?: host.toByteArray(Charsets.US_ASCII).let { h -> byteArrayOf(3, h.size.toByte()) + h }
        return head + addr + byteArrayOf((port ushr 8).toByte(), port.toByte())
    }

    private fun literalAddress(host: String): ByteArray? {
        val v4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)
        val v6 = host.contains(':')
        if (!v4 && !v6) return null
        return try { InetAddress.getByName(host).address } catch (_: Exception) { null }
    }
}
