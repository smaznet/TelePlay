package com.smaznet.teleplay.domain.playlist

import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private fun entry(id: Int = 1, progress: Boolean = true) = BackupEntry(
        messageId = id, documentId = 5_123_456_789_012_345_678L + id, chatUserId = 123456789, title = "Movie \"A\"\\ \n é 🎬.mkv",
        durationSec = 6120, receivedAt = 1_793_600_000_000,
        positionMs = if (progress) 1_800_000 else null, durationMs = if (progress) 6_120_000 else null,
        completed = if (progress) false else null, progressUpdatedAt = if (progress) 1_793_650_000_000 else null,
    )

    private fun file(vararg e: BackupEntry) = BackupFile(1, 1_793_700_000_000, 7_000_000_001, e.toList())
    private fun text(b: ByteArray) = b.toString(Charsets.UTF_8)
    private fun ok(r: DecodeResult) = (r as DecodeResult.Ok)
    private fun reason(r: DecodeResult) = (r as DecodeResult.Rejected).reason
    private fun decode(s: String) = BackupCodec.decode(s.toByteArray())

    @Test fun roundTripKeepsEveryField() {
        val f = file(entry(1), entry(2, progress = false))
        val d = ok(BackupCodec.decode(BackupCodec.encode(f)))
        assertEquals(f, d.file)
        assertEquals(0, d.invalidEntries)
    }

    @Test fun entryWithoutProgressOmitsAllProgressFields() {
        val t = text(BackupCodec.encode(file(entry(1, progress = false))))
        for (k in listOf("positionMs", "durationMs", "completed", "progressUpdatedAt")) assertFalse(k, t.contains(k))
    }

    @Test fun headerFields() {
        val t = text(BackupCodec.encode(file()))
        assertTrue(t.contains("\"app\":\"teleplay\""))
        assertTrue(t.contains("\"version\":1"))
    }

    @Test fun outputUsesOnlySchemaKeys() {
        val t = text(BackupCodec.encode(file(entry(1), entry(2, false))))
        val keys = Regex("\"([A-Za-z]+)\":").findAll(t).map { it.groupValues[1] }.toSet() - setOf("A") // the title text has quotes
        val allowed = setOf(
            "app", "version", "createdAt", "botUserId", "videos", "messageId", "documentId", "chatUserId", "title",
            "durationSec", "receivedAt", "positionMs", "durationMs", "completed", "progressUpdatedAt",
        )
        assertTrue("unexpected keys ${keys - allowed}", allowed.containsAll(keys))
        for (secret in listOf("accessHash", "fileReference", "token", "pairing", "session", "dcId")) assertFalse(secret, t.contains(secret))
    }

    @Test fun fileNameIsUtcTimestamp() {
        assertEquals("teleplay-backup-20260803-135000.json", BackupCodec.fileName(1_785_765_000_000))
        assertTrue(BackupCodec.fileName(1).matches(Regex("teleplay-backup-\\d{8}-\\d{6}\\.json")))
    }

    // --- decode rejections -----------------------------------------------------------------

    @Test fun wrongApp() = assertEquals(RejectReason.NOT_BACKUP, reason(decode("""{"app":"other","version":1,"videos":[]}""")))
    @Test fun notJson() = assertEquals(RejectReason.NOT_BACKUP, reason(decode("hello")))
    @Test fun empty() = assertEquals(RejectReason.NOT_BACKUP, reason(BackupCodec.decode(ByteArray(0))))
    @Test fun jsonArrayIsNotABackup() = assertEquals(RejectReason.NOT_BACKUP, reason(decode("[1,2]")))
    @Test fun truncatedJson() =
        assertEquals(RejectReason.NOT_BACKUP, reason(decode("""{"app":"teleplay","version":1,"videos":[{"messageId":1""")))
    @Test fun newerVersion() = assertEquals(RejectReason.NEWER_VERSION, reason(decode("""{"app":"teleplay","version":2,"videos":[]}""")))
    @Test fun missingVersion() = assertEquals(RejectReason.DAMAGED, reason(decode("""{"app":"teleplay","videos":[]}""")))
    @Test fun missingVideos() = assertEquals(RejectReason.DAMAGED, reason(decode("""{"app":"teleplay","version":1}""")))
    @Test fun videosNotAnArray() = assertEquals(RejectReason.DAMAGED, reason(decode("""{"app":"teleplay","version":1,"videos":{}}""")))

    @Test fun tooManyEntries() {
        val items = (1..5001).joinToString(",") { """{"messageId":$it,"documentId":$it}""" }
        assertEquals(RejectReason.TOO_LARGE, reason(decode("""{"app":"teleplay","version":1,"videos":[$items]}""")))
    }

    @Test fun exactlyMaxEntriesIsFine() {
        val items = (1..5000).joinToString(",") { """{"messageId":$it,"documentId":$it}""" }
        assertEquals(5000, ok(decode("""{"app":"teleplay","version":1,"videos":[$items]}""")).file.videos.size)
    }

    @Test fun oversizeBytes() =
        assertEquals(RejectReason.TOO_LARGE, reason(BackupCodec.decode(ByteArray(BackupCodec.MAX_BYTES + 1) { ' '.code.toByte() })))

    @Test fun unknownFieldsAreIgnored() {
        val d = ok(decode("""{"app":"teleplay","version":1,"extra":{"a":[1,2,{"b":null}]},"videos":[{"messageId":3,"documentId":9,"title":"x","future":true}]}"""))
        assertEquals(3, d.file.videos.single().messageId)
        assertEquals("x", d.file.videos.single().title)
    }

    @Test fun partialProgressIsTreatedAsNone() {
        val d = ok(decode("""{"app":"teleplay","version":1,"videos":[{"messageId":3,"documentId":9,"positionMs":5}]}"""))
        assertFalse(d.file.videos.single().hasProgress)
        assertNull(d.file.videos.single().positionMs)
    }

    @Test fun minorityOfInvalidEntriesIsCounted() {
        val d = ok(decode("""{"app":"teleplay","version":1,"videos":[{"messageId":1,"documentId":1},{"messageId":2,"documentId":2},{"messageId":0,"documentId":3}]}"""))
        assertEquals(2, d.file.videos.size)
        assertEquals(1, d.invalidEntries)
    }

    @Test fun majorityOfInvalidEntriesIsDamaged() =
        assertEquals(
            RejectReason.DAMAGED,
            reason(decode("""{"app":"teleplay","version":1,"videos":[{"messageId":1,"documentId":1},{"messageId":-2,"documentId":2},{"documentId":3}]}""")),
        )

    @Test fun emptyPlaylistDecodes() = assertEquals(0, ok(decode("""{"app":"teleplay","version":1,"videos":[]}""")).file.videos.size)

    @Test fun escapesAndUnicodeRoundTrip() {
        val t = "tab\t quote\" slash\\ nl\n ctrl\u0001 uni é 日本 🎬"
        val d = ok(BackupCodec.decode(BackupCodec.encode(file(entry(1).copy(title = t)))))
        assertEquals(t, d.file.videos.single().title)
    }

    @Test fun decimalNumbersAreRejected() =
        assertEquals(RejectReason.NOT_BACKUP, reason(decode("""{"app":"teleplay","version":1.5,"videos":[]}""")))
}
