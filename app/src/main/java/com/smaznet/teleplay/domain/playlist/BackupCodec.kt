package com.smaznet.teleplay.domain.playlist

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One video in a backup (contracts/backup-file.md). Progress fields are all present or all null. */
data class BackupEntry(
    val messageId: Int,
    val documentId: Long,
    val chatUserId: Long,
    val title: String,
    val durationSec: Int,
    val receivedAt: Long,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val completed: Boolean? = null,
    val progressUpdatedAt: Long? = null,
) {
    val hasProgress: Boolean get() = positionMs != null && durationMs != null && completed != null && progressUpdatedAt != null
}

data class BackupFile(val version: Int, val createdAt: Long, val botUserId: Long, val videos: List<BackupEntry>)

enum class RejectReason { NOT_BACKUP, NEWER_VERSION, DAMAGED, TOO_LARGE }

sealed class DecodeResult {
    /** [invalidEntries] counts entries dropped for a missing or non-positive id. */
    class Ok(val file: BackupFile, val invalidEntries: Int) : DecodeResult()
    class Rejected(val reason: RejectReason) : DecodeResult()
}

/**
 * Reads and writes the playlist backup: UTF-8 JSON with a fixed whitelist of keys, so nothing secret can
 * leak into it. A tiny reader/writer is used on purpose: `org.json` is not available in JVM unit tests and
 * the format is flat.
 */
object BackupCodec {
    const val VERSION = 1
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_ENTRIES = 5000
    const val APP = "teleplay"

    fun fileName(createdAt: Long): String =
        "teleplay-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(createdAt)) + ".json"

    fun encode(file: BackupFile): ByteArray {
        val sb = StringBuilder(256 + file.videos.size * 170)
        sb.append("{\"app\":\"").append(APP).append("\",\"version\":").append(file.version)
            .append(",\"createdAt\":").append(file.createdAt)
            .append(",\"botUserId\":").append(file.botUserId).append(",\"videos\":[")
        file.videos.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append("{\"messageId\":").append(e.messageId)
                .append(",\"documentId\":").append(e.documentId)
                .append(",\"chatUserId\":").append(e.chatUserId)
                .append(",\"title\":"); quote(sb, e.title)
            sb.append(",\"durationSec\":").append(e.durationSec).append(",\"receivedAt\":").append(e.receivedAt)
            if (e.hasProgress) {
                sb.append(",\"positionMs\":").append(e.positionMs).append(",\"durationMs\":").append(e.durationMs)
                    .append(",\"completed\":").append(e.completed).append(",\"progressUpdatedAt\":").append(e.progressUpdatedAt)
            }
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size > MAX_BYTES) return DecodeResult.Rejected(RejectReason.TOO_LARGE)
        val root = try {
            Json(bytes.toString(Charsets.UTF_8)).parseDocument() as? Map<*, *>
        } catch (_: JsonError) {
            null
        } ?: return DecodeResult.Rejected(RejectReason.NOT_BACKUP)
        if (root["app"] != APP) return DecodeResult.Rejected(RejectReason.NOT_BACKUP)
        val version = (root["version"] as? Long)?.toInt() ?: return DecodeResult.Rejected(RejectReason.DAMAGED)
        if (version > VERSION) return DecodeResult.Rejected(RejectReason.NEWER_VERSION)
        if (version < 1) return DecodeResult.Rejected(RejectReason.DAMAGED)
        val list = root["videos"] as? List<*> ?: return DecodeResult.Rejected(RejectReason.DAMAGED)
        if (list.size > MAX_ENTRIES) return DecodeResult.Rejected(RejectReason.TOO_LARGE)
        val entries = ArrayList<BackupEntry>(list.size)
        var invalid = 0
        for (item in list) {
            val e = (item as? Map<*, *>)?.let(::entry)
            if (e == null) invalid++ else entries += e
        }
        if (list.isNotEmpty() && invalid * 2 > list.size) return DecodeResult.Rejected(RejectReason.DAMAGED)
        return DecodeResult.Ok(
            BackupFile(version, (root["createdAt"] as? Long) ?: 0L, (root["botUserId"] as? Long) ?: 0L, entries),
            invalid,
        )
    }

    private fun entry(m: Map<*, *>): BackupEntry? {
        val messageId = (m["messageId"] as? Long)?.takeIf { it in 1..Int.MAX_VALUE } ?: return null
        val documentId = (m["documentId"] as? Long)?.takeIf { it != 0L } ?: return null
        val pos = m["positionMs"] as? Long
        val dur = m["durationMs"] as? Long
        val done = m["completed"] as? Boolean
        val upd = m["progressUpdatedAt"] as? Long
        val all = pos != null && dur != null && done != null && upd != null
        return BackupEntry(
            messageId = messageId.toInt(), documentId = documentId,
            chatUserId = (m["chatUserId"] as? Long) ?: 0L,
            title = (m["title"] as? String).orEmpty(),
            durationSec = ((m["durationSec"] as? Long) ?: 0L).toInt(),
            receivedAt = (m["receivedAt"] as? Long) ?: 0L,
            positionMs = if (all) pos else null, durationMs = if (all) dur else null,
            completed = if (all) done else null, progressUpdatedAt = if (all) upd else null,
        )
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    internal class JsonError : Exception()

    /** Minimal strict JSON reader: objects, arrays, strings, integers, booleans and null (other numbers are rejected). */
    private class Json(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            val v = value(0)
            ws()
            if (i != s.length) throw JsonError()
            return v
        }

        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun value(depth: Int): Any? {
            if (depth > 8) throw JsonError()
            ws()
            if (i >= s.length) throw JsonError()
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw JsonError()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonError()
            i += word.length
            return v
        }

        private fun num(): Long {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            if (i < s.length && (s[i] == '.' || s[i] == 'e' || s[i] == 'E')) throw JsonError()
            return s.substring(start, i).toLongOrNull() ?: throw JsonError()
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonError()
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw JsonError()
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonError()
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw JsonError())
                                i += 4
                            }
                            else -> throw JsonError()
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out += value(depth + 1)
                ws()
                if (i >= s.length) throw JsonError()
                when (s[i++]) { ',' -> continue; ']' -> return out; else -> throw JsonError() }
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = HashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw JsonError()
                val k = str()
                ws()
                if (i >= s.length || s[i++] != ':') throw JsonError()
                out[k] = value(depth + 1)
                ws()
                if (i >= s.length) throw JsonError()
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> throw JsonError() }
            }
        }
    }
}
