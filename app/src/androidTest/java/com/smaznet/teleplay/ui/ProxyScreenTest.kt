package com.smaznet.teleplay.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smaznet.teleplay.data.proxy.ProxyBlob
import com.smaznet.teleplay.data.proxy.ProxyFlagPrefs
import com.smaznet.teleplay.data.proxy.ProxyStore
import com.smaznet.teleplay.data.proxy.SecretProxyStore
import com.smaznet.teleplay.domain.proxy.AddResult
import com.smaznet.teleplay.domain.proxy.ProxyDraft
import com.smaznet.teleplay.domain.proxy.ProxyHealthCheck
import com.smaznet.teleplay.domain.proxy.ProxyKind
import com.smaznet.teleplay.domain.proxy.ProxyRepository
import com.smaznet.teleplay.domain.proxy.ProxyTestResult
import com.smaznet.teleplay.domain.proxy.ProxyTesting
import com.smaznet.teleplay.ui.proxy.ProxyScreen
import com.smaznet.teleplay.ui.theme.TelePlayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Proxies screen with the D-pad only: focus rules, add, select, test, health check, delete. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ProxyScreenTest {
    @get:Rule val rule = createComposeRule()

    private class MemStore : ProxyStore {
        var blob = ProxyBlob.EMPTY
        override fun load() = blob
        override fun save(blob: ProxyBlob) { this.blob = blob }
        override fun snapshot() = SecretProxyStore.encode(blob)
        override fun restore(snapshot: ByteArray?) {}
    }

    private class Prefs : ProxyFlagPrefs { override var enabled = false }

    private val repo = ProxyRepository(MemStore(), Prefs())
    private val tester = ProxyTesting { p -> if (p.server.startsWith("good")) ProxyTestResult.Working(42) else ProxyTestResult.NotReachable }

    private fun show(startWithAdd: Boolean = false, enableOnAdd: Boolean = false) = rule.setContent {
        TelePlayTheme { ProxyScreen(repo, tester, ProxyHealthCheck(tester, repo), startWithAdd = startWithAdd, enableOnAdd = enableOnAdd) }
    }

    private fun addSocks(server: String) = repo.add(ProxyDraft(ProxyKind.SOCKS5, server, 1080))

    @Test fun theToggleSwitchesTheProxyOnlyWhenOneIsSelected() {
        addSocks("good1.example")
        show()
        rule.onNodeWithTag("proxy_toggle").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithTag("proxy_need_select").assertIsDisplayed()
        assertTrue(!repo.state.value.enabled)
        repo.select(repo.proxies.value[0].id)
        rule.onNodeWithTag("proxy_toggle").performKeyInput { pressKey(Key.DirectionCenter) }
        assertTrue(repo.state.value.active)
    }

    @Test fun emptyListFocusesAddProxy() {
        show()
        rule.onNodeWithTag("proxy_add").assertIsFocused()
    }

    @Test fun firstProxyIsFocusedWhenThereAreProxies() {
        addSocks("good1.example"); addSocks("bad1.example")
        show()
        rule.onNodeWithTag("proxy_row_0").assertIsFocused()
    }

    @Test fun healthCheckAboveTheListReportsHowManyWork() {
        addSocks("good1.example"); addSocks("bad1.example"); addSocks("good2.example")
        show()
        rule.onNodeWithTag("proxy_row_0").performKeyInput { pressKey(Key.DirectionUp) }
        rule.onNodeWithTag("proxy_check_all").assertIsFocused()
        rule.onNodeWithTag("proxy_check_all").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitUntil(5_000) { repo.proxies.value.all { it.lastTest != null } }
        rule.onNodeWithTag("proxy_check_summary").assertTextContains("2 of 3 working", substring = true)
        assertEquals(2, repo.proxies.value.count { it.lastTest?.isWorking == true })
        assertEquals("selection untouched", null, repo.state.value.selectedId)
        assertTrue(!repo.state.value.enabled)
    }

    @Test fun selectAProxyFromItsMenu() {
        addSocks("good1.example")
        show()
        rule.onNodeWithTag("proxy_row_0").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithTag("proxy_action_select").assertIsFocused()
        rule.onNodeWithTag("proxy_action_select").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(repo.proxies.value[0].id, repo.state.value.selectedId)
        assertTrue("selecting does not switch the proxy on", !repo.state.value.enabled)
    }

    @Test fun testAProxyFromItsMenu() {
        addSocks("good1.example")
        show()
        rule.onNodeWithTag("proxy_row_0").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithTag("proxy_action_test").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitUntil(5_000) { repo.proxies.value[0].lastTest != null }
        assertEquals(ProxyTestResult.Working(42), repo.proxies.value[0].lastTest)
    }

    @Test fun deleteAsksForConfirmationAndRemovesTheProxy() {
        addSocks("good1.example")
        show()
        rule.onNodeWithTag("proxy_row_0").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithTag("proxy_action_delete").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithTag("dialog_cancel").assertIsFocused() // safe default
        rule.onNodeWithTag("dialog_confirm").performKeyInput { pressKey(Key.DirectionCenter) }
        assertTrue(repo.proxies.value.isEmpty())
    }

    @Test fun addFormSavesAProxyAndSelectsTheFirstOne() {
        show(startWithAdd = true)
        rule.onNodeWithTag("proxy_form_type").assertIsFocused()
        rule.onNodeWithTag("proxy_field_server").performTextInput("1.2.3.4")
        rule.onNodeWithTag("proxy_field_port").performTextInput("1080")
        rule.onNodeWithTag("proxy_form_save").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, repo.proxies.value.size)
        assertEquals(repo.proxies.value[0].id, repo.state.value.selectedId)
        rule.onNodeWithTag("proxy_add").assertIsDisplayed()
    }

    @Test fun duringSetupAnAddedProxyIsAlsoSwitchedOn() {
        show(startWithAdd = true, enableOnAdd = true)
        rule.onNodeWithTag("proxy_field_server").performTextInput("1.2.3.4")
        rule.onNodeWithTag("proxy_field_port").performTextInput("1080")
        rule.onNodeWithTag("proxy_form_save").performKeyInput { pressKey(Key.DirectionCenter) }
        assertTrue(repo.state.value.active)
    }

    @Test fun pastingALinkFillsTheFormAndDetectsTheSecretKind() {
        show(startWithAdd = true)
        rule.onNodeWithTag("proxy_field_link").performTextInput("tg://proxy?server=mt.example.org&port=443&secret=dd0102030405060708090a0b0c0d0e0f10")
        rule.onNodeWithTag("proxy_secret_kind").assertTextContains("padding", substring = true, ignoreCase = true)
    }

    @Test fun invalidFormShowsAnErrorAndSavesNothing() {
        show(startWithAdd = true)
        rule.onNodeWithTag("proxy_form_save").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithTag("proxy_form_error").assertIsDisplayed()
        assertTrue(repo.proxies.value.isEmpty())
    }
}
