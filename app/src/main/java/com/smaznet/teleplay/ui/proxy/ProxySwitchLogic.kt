package com.smaznet.teleplay.ui.proxy

import com.smaznet.teleplay.domain.ConnectionState
import com.smaznet.teleplay.domain.proxy.Proxy
import com.smaznet.teleplay.domain.proxy.ProxyState

enum class ProxyIndicator { OFF, CONNECTING, CONNECTED, FAILED }

/** What the Home switch shows. [name] is the selected proxy's label, never its address or credentials. */
data class ProxySwitchUi(val on: Boolean, val name: String?, val indicator: ProxyIndicator, val hasProxies: Boolean)

enum class ToggleAction { ENABLE, DISABLE, OPEN_ADD, OPEN_LIST, OPEN_FAILURE_MENU }

enum class FailureAction { RETRY, CHOOSE_ANOTHER, GO_DIRECT }

/** Pure rules for the Home switch (contracts/ui.md), kept apart from Compose so they can be unit tested. */
object ProxySwitchLogic {
    fun ui(state: ProxyState, selected: Proxy?, savedCount: Int, connection: ConnectionState): ProxySwitchUi {
        val indicator = when {
            !state.active -> ProxyIndicator.OFF
            connection == ConnectionState.CONNECTING -> ProxyIndicator.CONNECTING
            connection == ConnectionState.OFFLINE -> ProxyIndicator.FAILED
            else -> ProxyIndicator.CONNECTED
        }
        return ProxySwitchUi(state.active, selected?.label, indicator, savedCount > 0)
    }

    /** What pressing OK on the switch does. */
    fun onToggle(ui: ProxySwitchUi, hasSelection: Boolean): ToggleAction = when {
        ui.on && ui.indicator == ProxyIndicator.FAILED -> ToggleAction.OPEN_FAILURE_MENU
        ui.on -> ToggleAction.DISABLE
        !ui.hasProxies -> ToggleAction.OPEN_ADD
        !hasSelection -> ToggleAction.OPEN_LIST
        else -> ToggleAction.ENABLE
    }
}
