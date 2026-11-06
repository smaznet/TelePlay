package com.smaznet.teleplay.domain.proxy

import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.domain.msg
import com.smaznet.teleplay.fakes.FakeBotMessenger
import com.smaznet.teleplay.fakes.FakeTelegramGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private const val HEX = "0102030405060708090a0b0c0d0e0f10"

class ProxyImportTest {
    private fun link(i: Int) = "https://t.me/socks?server=10.0.${i / 250}.${i % 250 + 1}&port=1080"

    @Test fun countsAddedDuplicateAndInvalid() {
        val repo = newRepo()
        val text = """
            ${link(1)}
            ${link(2)}
            ${link(1)}
            tg://proxy?server=a.example.org&port=443&secret=$HEX
            tg://proxy?server=bad.example.org&port=443&secret=zzzz
            tg://socks?server=1.1.1.1&port=0
        """.trimIndent()
        val c = ProxyImport(repo).import(text)
        assertEquals(ImportCounts(added = 3, duplicate = 1, invalid = 2, skipped = 0), c)
        assertEquals(3, repo.proxies.value.size)
    }

    @Test fun processesAtMost100LinksAndReportsTheRestAsSkipped() {
        val repo = newRepo()
        val text = (1..130).joinToString("\n") { link(it) }
        val c = ProxyImport(repo).import(text)
        assertEquals(100, c.added); assertEquals(30, c.skipped)
        assertEquals(100, repo.proxies.value.size)
    }

    @Test fun theSavedLimitCountsAsSkipped() {
        val repo = newRepo()
        repeat(199) { repo.add(socksDraft(port = 2000 + it)) }
        val c = ProxyImport(repo).import("${link(1)}\n${link(2)}\n${link(3)}")
        assertEquals(1, c.added); assertEquals(2, c.skipped)
    }

    @Test fun neverEnablesOrSelectsAnythingEvenWhenNothingIsSelected() {
        val repo = newRepo()
        ProxyImport(repo).import("${link(1)}\n${link(2)}")
        assertEquals(ProxyState(false, null), repo.state.value)
        assertEquals(ActiveRoute.Direct, repo.route())
    }

    @Test fun leavesAnExistingSelectionAndSwitchAlone() {
        val repo = newRepo()
        val a = (repo.add(socksDraft(server = "9.9.9.9")) as AddResult.Added).proxy
        repo.select(a.id); repo.setEnabled(true)
        ProxyImport(repo).import(link(1))
        assertEquals(ProxyState(true, a.id), repo.state.value)
        repo.setEnabled(false)
        ProxyImport(repo).import(link(2))
        assertEquals(ProxyState(false, a.id), repo.state.value)
    }

    @Test fun textWithoutLinksChangesNothing() {
        val repo = newRepo()
        assertEquals(ImportCounts(0, 0, 0, 0), ProxyImport(repo).import("hello, here is nothing https://example.com"))
        assertTrue(repo.proxies.value.isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyCommandsTest {
    private class T(var authorized: Long? = 42, username: String? = null, off: Boolean = true) {
        val repo = newRepo()
        val gateway = FakeTelegramGateway()
        val messenger = FakeBotMessenger()
        var off = off
        val commands = ProxyCommands({ authorized }, ProxyImport(repo), gateway, messenger, { this.off }, { username })
        val texts get() = messenger.sends.map { it.text }
    }

    private val a = "https://t.me/socks?server=1.1.1.1&port=1080"
    private val b = "tg://proxy?server=mt.example.org&port=443&secret=$HEX"

    @Test fun commandWithLinksInTheSameMessage() = runTest {
        val t = T()
        assertTrue(t.commands.handle(msg(text = "/proxies $a $b")))
        assertEquals(2, t.repo.proxies.value.size)
        assertEquals(listOf("Proxy import finished\nAdded: 2\nAlready saved: 0\nInvalid: 0\n" + ProxyMessages.SAVED_BUT_OFF), t.texts)
    }

    @Test fun linksOnNewLinesAfterTheCommand() = runTest {
        val t = T()
        t.commands.handle(msg(text = "/proxies\n$a\n$b"))
        assertEquals(2, t.repo.proxies.value.size)
    }

    @Test fun commandAsAReplyToAMessageWithLinks() = runTest {
        val t = T()
        t.gateway.messagesById[7] = msg(id = 7, text = "free proxies:\n$a\n$b\n$a")
        t.commands.handle(msg(id = 9, text = "/proxies", replyTo = 7))
        assertEquals(listOf(listOf(7)), t.gateway.fetchBatches)
        assertEquals(2, t.repo.proxies.value.size)
        assertTrue(t.texts.single().contains("Already saved: 1"))
    }

    @Test fun noLinksAnywhereGivesUsageAndChangesNothing() = runTest {
        val t = T()
        t.commands.handle(msg(text = "/proxies"))
        t.commands.handle(msg(text = "/proxies hello there"))
        assertEquals(listOf(ProxyMessages.USAGE, ProxyMessages.USAGE), t.texts)
        assertTrue(t.repo.proxies.value.isEmpty())
    }

    @Test fun aReplyToAMessageWithoutLinksGivesUsage() = runTest {
        val t = T()
        t.gateway.messagesById[7] = msg(id = 7, text = "no links here")
        t.commands.handle(msg(text = "/proxies", replyTo = 7))
        assertEquals(listOf(ProxyMessages.USAGE), t.texts)
    }

    @Test fun repliedMessageThatCannotBeFetched() = runTest {
        val t = T()
        t.commands.handle(msg(text = "/proxies", replyTo = 7)) // not in the fake's map
        t.gateway.messagesById[8] = msg(id = 8, text = a)
        t.gateway.fetchFailure = TelegramError.Offline()
        t.gateway.fetchFailureAt = 2
        t.commands.handle(msg(text = "/proxies", replyTo = 8))
        assertEquals(listOf(ProxyMessages.CANNOT_READ, ProxyMessages.CANNOT_READ), t.texts)
        assertTrue(t.repo.proxies.value.isEmpty())
    }

    @Test fun strangersAreNeverAnsweredOrServed() = runTest {
        val t = T()
        assertFalse(t.commands.handle(msg(sender = 7, text = "/proxies $a")))
        assertTrue(t.messenger.calls.isEmpty())
        assertTrue(t.gateway.fetchBatches.isEmpty())
        assertTrue(t.repo.proxies.value.isEmpty())
    }

    @Test fun beforePairingNothingIsRecognised() = runTest {
        val t = T(authorized = null)
        assertFalse(t.commands.recognise(msg(text = "/proxies $a")))
    }

    @Test fun linksWithoutTheCommandAreNeverReadOrStored() = runTest {
        val t = T()
        assertFalse(t.commands.handle(msg(text = a)))
        assertFalse(t.commands.handle(msg(text = "please add $a $b")))
        assertTrue(t.repo.proxies.value.isEmpty())
        assertTrue(t.messenger.calls.isEmpty())
    }

    @Test fun otherCommandsAreNotOurs() = runTest {
        val t = T()
        for (c in listOf("/export $a", "/import $a", "/help", "/proxy $a", "/proxiesx $a")) assertFalse(c, t.commands.recognise(msg(text = c)))
    }

    @Test fun botNameSuffixMustMatch() = runTest {
        val t = T(username = "MyBot")
        assertTrue(t.commands.recognise(msg(text = "/proxies@mybot $a")))
        assertFalse(t.commands.recognise(msg(text = "/proxies@otherbot $a")))
        assertTrue(T().commands.recognise(msg(text = "/PROXIES $a")))
    }

    @Test fun theSavedButOffLineAppearsOnlyWhenRelevant() = runTest {
        val on = T(off = false)
        on.commands.handle(msg(text = "/proxies $a"))
        assertFalse(on.texts.single().contains("turned off"))
        val none = T()
        none.commands.handle(msg(text = "/proxies tg://socks?server=1.1.1.1&port=0"))
        assertFalse(none.texts.single().contains("turned off"))
    }

    @Test fun skippedLineShowsWhenOverTheCap() = runTest {
        val t = T()
        val links = (1..105).joinToString(" ") { "tg://socks?server=10.0.0.$it&port=1" }
        t.commands.handle(msg(text = "/proxies $links"))
        assertTrue(t.texts.single().contains("Skipped: 5 (limit reached)"))
    }

    @Test fun onlyOneImportAtATime() = runTest {
        val t = T()
        val gate = CompletableDeferred<Unit>()
        t.gateway.messagesById[7] = msg(id = 7, text = a)
        t.gateway.fetchGate = gate
        val first = launch { t.commands.handle(msg(id = 9, text = "/proxies", replyTo = 7)) }
        runCurrent()
        t.commands.handle(msg(id = 10, text = "/proxies $b"))
        assertEquals(listOf(ProxyMessages.BUSY), t.texts)
        gate.complete(Unit)
        first.join()
        assertEquals(1, t.repo.proxies.value.size) // only the replied-to link: the busy one was refused
    }

    @Test fun repliesNeverContainProxyDetails() = runTest {
        val t = T()
        t.commands.handle(msg(text = "/proxies https://t.me/socks?server=secret-host.example&port=4455&user=bob&pass=hunter2 $b"))
        val reply = t.texts.joinToString("\n")
        for (leak in listOf("secret-host", "4455", "bob", "hunter2", "mt.example.org", HEX, "443")) assertFalse(leak, reply.contains(leak))
    }

    @Test fun importNeverTurnsAnythingOn() = runTest {
        val t = T()
        t.commands.handle(msg(text = "/proxies $a"))
        assertEquals(ProxyState(false, null), t.repo.state.value)
    }
}
