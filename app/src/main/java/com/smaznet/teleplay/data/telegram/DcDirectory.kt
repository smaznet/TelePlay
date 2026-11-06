package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.mtproto.Rpc
import com.smaznet.teleplay.mtproto.tl.generated.C_config
import com.smaznet.teleplay.mtproto.tl.generated.F_help_getConfig

class DcAddress(val host: String, val port: Int = 443)

/**
 * Data-center addresses. Bootstrap IPv4 addresses are the production DCs listed in the official
 * documentation (https://core.telegram.org/mtproto/DC); `help.getConfig` refreshes them.
 */
class DcDirectory(initial: Map<Int, DcAddress> = BOOTSTRAP) {
    private val map = HashMap(initial)

    @Synchronized
    fun address(dcId: Int): DcAddress = map[dcId] ?: map.getValue(DEFAULT_DC)

    @Synchronized
    fun put(dcId: Int, address: DcAddress) { map[dcId] = address }

    /** Replaces addresses with the plain IPv4 TCP ones from `help.getConfig`. */
    suspend fun refresh(rpc: Rpc) {
        val cfg = rpc.invoke(F_help_getConfig()) as C_config
        synchronized(this) {
            for (o in cfg.dc_options) {
                val c = o as? com.smaznet.teleplay.mtproto.tl.generated.C_dcOption ?: continue
                if (c.ipv6 || c.cdn || c.media_only || c.tcpo_only) continue
                map[c.id] = DcAddress(c.ip_address, c.port)
            }
        }
    }

    companion object {
        const val DEFAULT_DC = 2
        val BOOTSTRAP: Map<Int, DcAddress> = mapOf(
            1 to DcAddress("149.154.175.53"),
            2 to DcAddress("149.154.167.51"),
            3 to DcAddress("149.154.175.100"),
            4 to DcAddress("149.154.167.91"),
            5 to DcAddress("91.108.56.130"),
        )
    }
}
