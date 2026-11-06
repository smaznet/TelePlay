package com.smaznet.teleplay.setup

import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.domain.TelegramError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.Socket

/** Constitution principle IV: the token and the setup secret are never logged, echoed or exposed in text forms. */
class SetupNoLoggingTest {
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

    @Test fun setupSourcesDoNotLog() {
        val root = sourceRoot()
        val files = File(root, "setup").walkTopDown().filter { it.extension == "kt" }.toList() +
            File(root, "ui/token/QrSetupPanel.kt")
        assertTrue("scanned some sources", files.size >= 6)
        for (f in files) {
            val text = f.readText().lineSequence()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
                .joinToString("\n")
            for (token in forbidden) assertFalse("${f.name} must not contain $token", text.contains(token))
            assertFalse("${f.name} must not call print()", bareCalls.containsMatchIn(text))
        }
    }

    private fun exchange(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write(request.replace("{host}", "127.0.0.1:$port").toByteArray())
            s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }

    @Test fun sentinelTokenAndSecretNeverAppearInOutputOrResponses() = runBlocking {
        val sentinel = "987654321:SENTINEL_token_value_ABCDEFGHIJKL"
        val session = SetupSession()
        var calls = 0
        val server = SetupServer(
            session = session,
            submit = {
                calls++
                if (calls == 1) Result.failure(TelegramError.TokenRejected()) else Result.success(BotInfo(1, "bot"))
            },
            addressSource = { "127.0.0.1" },
        )
        val origOut = System.out
        val origErr = System.err
        val captured = ByteArrayOutputStream()
        val sink = PrintStream(captured, true)
        System.setOut(sink)
        System.setErr(sink)
        val responses = StringBuilder()
        try {
            val port = server.start()
            val body = "{\"token\":\"$sentinel\"}"
            val post = "POST /s/${session.secret}/token HTTP/1.1\r\nHost: {host}\r\nContent-Length: ${body.length}\r\n\r\n$body"
            responses.append(exchange(port, "GET /s/${session.secret} HTTP/1.1\r\nHost: {host}\r\n\r\n"))
            responses.append(exchange(port, post)) // rejected
            responses.append(exchange(port, post)) // accepted
            responses.append(exchange(port, post)) // used
            responses.append(exchange(port, "GET /s/wrong HTTP/1.1\r\nHost: {host}\r\n\r\n"))
        } finally {
            server.stop()
            System.setOut(origOut)
            System.setErr(origErr)
        }
        val output = captured.toString()
        for ((what, text) in listOf("output" to output, "responses" to responses.toString())) {
            assertFalse("token must not appear in $what", text.contains(sentinel))
        }
        assertFalse("secret must not appear in output", output.contains(session.secret))
        assertFalse("secret must not be echoed in responses", responses.toString().contains(session.secret))
        assertFalse(session.toString().contains(session.secret))
        val ui = SetupUiState(SetupStatus.WAITING, "http://1.2.3.4:5/s/${session.secret}", "1.2.3.4:5", session.secret)
        assertFalse(ui.toString().contains(session.secret))
        assertEquals(2, calls)
    }
}
