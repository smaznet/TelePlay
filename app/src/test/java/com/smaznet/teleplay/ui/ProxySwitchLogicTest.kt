package com.smaznet.teleplay.ui

import com.smaznet.teleplay.domain.ConnectionState
import com.smaznet.teleplay.domain.proxy.Proxy
import com.smaznet.teleplay.domain.proxy.ProxyKind
import com.smaznet.teleplay.domain.proxy.ProxyState
import com.smaznet.teleplay.ui.proxy.ProxyIndicator
import com.smaznet.teleplay.ui.proxy.ProxySwitchLogic
import com.smaznet.teleplay.ui.proxy.ToggleAction
import org.junit.Assert.*
import org.junit.Test

class ProxySwitchLogicTest {
    private val p = Proxy(1, ProxyKind.SOCKS5, "1.2.3.4", 1080, "Home", null, null, null)

    private fun ui(state: ProxyState, selected: Proxy? = p, count: Int = 1, c: ConnectionState = ConnectionState.READY) =
        ProxySwitchLogic.ui(state, selected, count, c)

    @Test fun offShowsNoProxyIndicator() {
        val u = ui(ProxyState(false, 1))
        assertFalse(u.on); assertEquals(ProxyIndicator.OFF, u.indicator); assertTrue(u.hasProxies)
    }

    @Test fun onIndicatorFollowsTheConnection() {
        val on = ProxyState(true, 1)
        assertEquals(ProxyIndicator.CONNECTED, ui(on, c = ConnectionState.READY).indicator)
        assertEquals(ProxyIndicator.CONNECTING, ui(on, c = ConnectionState.CONNECTING).indicator)
        assertEquals(ProxyIndicator.FAILED, ui(on, c = ConnectionState.OFFLINE).indicator)
        assertEquals("Home", ui(on).name)
    }

    @Test fun offIsOffEvenWhenTheConnectionIsOffline() {
        assertEquals(ProxyIndicator.OFF, ui(ProxyState(false, 1), c = ConnectionState.OFFLINE).indicator)
    }

    @Test fun offToOnWithASelectionEnables() {
        val u = ui(ProxyState(false, 1))
        assertEquals(ToggleAction.ENABLE, ProxySwitchLogic.onToggle(u, hasSelection = true))
    }

    @Test fun offToOnWithNoSavedProxyOpensTheAddForm() {
        val u = ui(ProxyState(false, null), selected = null, count = 0)
        assertEquals(ToggleAction.OPEN_ADD, ProxySwitchLogic.onToggle(u, hasSelection = false))
    }

    @Test fun offToOnWithProxiesButNoSelectionOpensTheList() {
        val u = ui(ProxyState(false, null), selected = null, count = 3)
        assertEquals(ToggleAction.OPEN_LIST, ProxySwitchLogic.onToggle(u, hasSelection = false))
    }

    @Test fun onToOffDisables() {
        val u = ui(ProxyState(true, 1))
        assertEquals(ToggleAction.DISABLE, ProxySwitchLogic.onToggle(u, hasSelection = true))
    }

    @Test fun aFailingProxyOpensTheFailureMenuInsteadOfToggling() {
        val u = ui(ProxyState(true, 1), c = ConnectionState.OFFLINE)
        assertEquals(ToggleAction.OPEN_FAILURE_MENU, ProxySwitchLogic.onToggle(u, hasSelection = true))
    }
}
