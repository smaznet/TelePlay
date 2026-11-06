package com.smaznet.teleplay.mtproto

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Constitution principle IV: no logging in code that handles secrets. */
class NoLoggingTest {
    private val forbidden = listOf("android.util.Log", "Log.d(", "Log.e(", "Log.i(", "Log.w(", "Log.v(", "println(", "Timber", "printStackTrace")
    private val bareCalls = listOf(Regex("(?<![A-Za-z0-9_.])print\\("))

    private fun sourceRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val c = File(dir, "app/src/main/java/com/smaznet/teleplay")
            if (c.isDirectory) return c
            val d = File(dir, "src/main/java/com/smaznet/teleplay")
            if (d.isDirectory) return d
            dir = dir.parentFile
        }
        error("source root not found")
    }

    @Test
    fun secretHandlingPackagesDoNotLog() {
        val root = sourceRoot()
        val files = listOf("mtproto", "data/secure", "domain/update", "data/update", "domain/playlist", "data/telegram/ChannelPostReader.kt")
            .flatMap { File(root, it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        assertTrue("scanned some sources", files.size > 5)
        for (f in files) {
            val text = f.readText().lineSequence().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
            for (token in forbidden) {
                assertTrue("${f.name} must not contain $token", !text.contains(token))
            }
            for (re in bareCalls) assertTrue("${f.name} must not call print()", !re.containsMatchIn(text))
        }
    }
}

class ReleasePackageNoSecretsTest {
    @Test
    fun releasePackageToStringHidesHandle() {
        val r = com.smaznet.teleplay.domain.update.ReleasePackage(3, "a.apk", 10, handle = "FILE-REFERENCE-SECRET")
        assertTrue(!r.toString().contains("SECRET"))
    }
}
