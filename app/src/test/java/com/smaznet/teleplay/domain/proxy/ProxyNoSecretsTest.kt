package com.smaznet.teleplay.domain.proxy

import com.smaznet.teleplay.data.proxy.ProxyBlob
import com.smaznet.teleplay.domain.msg
import com.smaznet.teleplay.fakes.FakeBotMessenger
import com.smaznet.teleplay.fakes.FakeTelegramGateway
import com.smaznet.teleplay.setup.SetupUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** Constitution principle IV: proxy credentials and secrets never reach a log, a bot message, a backup or a string form. */
class ProxyNoSecretsTest {
    private val forbidden = listOf("android.util.Log", "Log.d(", "Log.e(", "Log.i(", "Log.w(", "Log.v(", "println(", "Timber", "printStackTrace")
    private val bareCalls = Regex("(?<![A-Za-z0-9_.])print\\(")

    private fun sourceRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, "app/src/main/java/com/smaznet/teleplay").takeIf { it.isDirectory }?.let { return it }
            File(dir, "src/main/java/com/smaznet/teleplay").takeIf { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("source root not found")
    }

    @Test fun proxySourcesDoNotLog() {
        val root = sourceRoot()
        val files = listOf("domain/proxy", "data/proxy", "mtproto/transport", "ui/proxy").flatMap { d ->
            File(root, d).walkTopDown().filter { it.extension == "kt" }.toList()
        }
        assertTrue("scanned some sources", files.size >= 15)
        for (f in files) {
            val text = f.readText().lineSequence()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
                .joinToString("\n")
            for (token in forbidden) assertFalse("${f.name} must not contain $token", text.contains(token))
            assertFalse("${f.name} must not call print()", bareCalls.containsMatchIn(text))
        }
    }

    private val sentinelSecret = ByteArray(16) { 0x5a }
    private val sentinelHex = "5a".repeat(16)

    private fun sensitive() = listOf(
        Proxy(1, ProxyKind.SOCKS5, "sentinel-host.example", 4455, "named", "sentinel-user", "sentinel-pass", null),
        Proxy(2, ProxyKind.MTPROTO, "mt-sentinel.example", 4466, null, null, null, MtSecret.FakeTls(sentinelSecret, "cover-sentinel.example")),
        Proxy(3, ProxyKind.MTPROTO, "mt2-sentinel.example", 4477, null, null, null, MtSecret.Padded(sentinelSecret)),
    )

    @Test fun stringFormsAreRedacted() {
        val all = sensitive().map { it.toString() } + sensitive().map { it.secret?.toString().orEmpty() } +
            ProxyState(true, 1).toString() + ProxyBlob(sensitive(), 1, 4).toString() + ProxyForm(ProxyKind.SOCKS5, "sentinel-host.example", "1", "n", "sentinel-user", "sentinel-pass", sentinelHex).toString() +
            ProxyDraft(ProxyKind.SOCKS5, "sentinel-host.example", 1, null, "sentinel-user", "sentinel-pass").toString() +
            SetupUiState(proxyAdded = "sentinel-host.example").toString()
        for (s in all) for (leak in listOf("sentinel", "4455", "4466", sentinelHex, "5a5a")) assertFalse("$s leaks $leak", s.contains(leak))
    }

    @Test fun botRepliesNeverCarryProxyDetails() = runBlocking {
        val repo = newRepo()
        val messenger = FakeBotMessenger()
        val commands = ProxyCommands({ 42 }, ProxyImport(repo), FakeTelegramGateway(), messenger, { true })
        commands.handle(msg(text = "/proxies https://t.me/socks?server=sentinel-host.example&port=4455&user=sentinel-user&pass=sentinel-pass " +
            "tg://proxy?server=mt-sentinel.example&port=4466&secret=ee${sentinelHex}636f7665722e6578616d706c65"))
        val all = messenger.sends.joinToString("\n") { it.text }
        for (leak in listOf("sentinel", "4455", "4466", sentinelHex, "cover")) assertFalse("reply leaks $leak", all.contains(leak))
    }

    @Test fun stdoutAndStderrStayCleanWhileProxiesAreUsed() = runBlocking {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val so = System.out; val se = System.err
        System.setOut(PrintStream(out)); System.setErr(PrintStream(err))
        try {
            val repo = newRepo()
            for (p in sensitive()) {
                repo.add(ProxyDraft(p.kind, p.server, p.port, p.name, p.username, p.password, p.secret))
            }
            repo.select(1); repo.setEnabled(true)
            repo.recordTest(1, ProxyTestResult.AuthRejected)
            ProxyImport(repo).import("tg://socks?server=other.example&port=1")
        } finally { System.setOut(so); System.setErr(se) }
        assertEquals("", out.toString() + err.toString())
    }

    @Test fun theBackupFileCodeCannotSeeProxies() {
        // The playlist backup (BackupCodec, PlaylistTransfer) must never carry proxy details (FR-016): it has no access to them.
        val root = sourceRoot()
        val files = File(root, "domain/playlist").walkTopDown().filter { it.extension == "kt" }.toList() +
            File(root, "data/repo").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val text = f.readText()
            assertFalse("${f.name} must not reach proxies", text.contains("domain.proxy") || text.contains("data.proxy"))
        }
    }

    @Test fun theBlobIsTheOnlyPlaceSecretsAreWritten() {
        val bytes = com.smaznet.teleplay.data.proxy.SecretProxyStore.encode(ProxyBlob(sensitive(), 1, 4))
        // The store holds them on purpose (SecretStore encrypts the blob); nothing else serialises a proxy.
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("sentinel-pass"))
    }
}
