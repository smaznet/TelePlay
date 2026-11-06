package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.domain.update.ReleasePackage
import com.smaznet.teleplay.domain.update.UpdateError
import com.smaznet.teleplay.domain.update.UpdateException
import com.smaznet.teleplay.mtproto.tl.TlFunction
import com.smaznet.teleplay.mtproto.tl.TlReader
import com.smaznet.teleplay.mtproto.tl.TlWriter
import com.smaznet.teleplay.mtproto.tl.generated.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Answers calls from recorded TL bytes (tools/tlgen/fixtures.py) and a fake document. */
private class FakeChannelRpc(
    var postFixture: String = "channel_post_announcement.bin",
    var resolveFixture: String = "resolved_peer_channel.bin",
    var document: ByteArray = ByteArray(0),
    var expireOnPart: Int = -1,
) : ChannelRpc {
    val calls = mutableListOf<String>()
    val fileOffsets = mutableListOf<Long>()
    val fileLimits = mutableListOf<Int>()
    val fileDcs = mutableListOf<Int?>()
    var failWith: Throwable? = null
    private var expired = false

    private fun fixture(name: String) = TlReader(javaClass.getResourceAsStream("/tl/$name")!!.readBytes())

    @Suppress("UNCHECKED_CAST")
    override suspend fun <R> invoke(fn: TlFunction<R>, dcId: Int?): R {
        failWith?.let { throw it }
        return when (fn) {
            is F_contacts_resolveUsername -> { calls += "resolve:${fn.username}"; fn.readResult(fixture(resolveFixture)) as R }
            is F_channels_getMessages -> {
                val ch = fn.channel as C_inputChannel
                calls += "get:${ch.channel_id}:${ch.access_hash}:${(fn.id.single() as C_inputMessageID).id}"
                fn.readResult(fixture(postFixture)) as R
            }
            is F_upload_getFile -> {
                calls += "file"
                fileOffsets += fn.offset; fileLimits += fn.limit; fileDcs += dcId
                if (fileOffsets.size - 1 == expireOnPart && !expired) { expired = true; throw TelegramError.FileReferenceExpired() }
                val from = fn.offset.toInt().coerceAtMost(document.size)
                val to = (from + fn.limit).coerceAtMost(document.size)
                val bytes = document.copyOfRange(from, to)
                C_upload_file(C_storage_fileUnknown(), 0, bytes) as R
            }
            else -> error("unexpected call $fn")
        }
    }
}

class ChannelPostReaderTest {
    private fun failure(block: suspend () -> Unit): UpdateError {
        try {
            kotlinx.coroutines.runBlocking { block() }
        } catch (e: UpdateException) {
            return e.failure.error
        }
        fail("expected UpdateException")
        error("unreachable")
    }

    @Test
    fun announcement_resolvesThenReadsPostTwo() = runTest {
        val rpc = FakeChannelRpc()
        val text = ChannelPostReader(rpc).readPostText(2)
        assertEquals("latest:\ncode: 2\nhttps://t.me/TelePlayUpdate/3", text)
        assertEquals(listOf("resolve:TelePlayUpdate", "get:3932023718:2677590764230059536:2"), rpc.calls)
    }

    @Test
    fun channelIsCachedAfterFirstResolve() = runTest {
        val rpc = FakeChannelRpc()
        val reader = ChannelPostReader(rpc)
        reader.readPostText(2)
        reader.readPostText(2)
        assertEquals(1, rpc.calls.count { it.startsWith("resolve") })
        assertEquals(2, rpc.calls.count { it.startsWith("get") })
    }

    @Test
    fun resolvedChatMustBeThatBroadcastChannel() {
        // The fixture channel is "TelePlayUpdate"; asking for another name must be rejected.
        val rpc = FakeChannelRpc()
        assertEquals(UpdateError.AnnouncementUnreadable, failure { ChannelPostReader(rpc, username = "SomeoneElse").readPostText(2) })
    }

    @Test
    fun emptyPostIsUnreadable() {
        val rpc = FakeChannelRpc(postFixture = "channel_post_empty.bin")
        assertEquals(UpdateError.AnnouncementUnreadable, failure { ChannelPostReader(rpc).readPostText(2) })
    }

    @Test
    fun release_readsApkDocument() = runTest {
        val rpc = FakeChannelRpc(postFixture = "channel_post_release.bin")
        val r = ChannelPostReader(rpc).readRelease(3)
        assertEquals(3, r.postId)
        assertEquals("app-release.apk", r.fileName)
        assertEquals(2144427L, r.sizeBytes)
    }

    @Test
    fun release_withoutDocumentIsMissing() {
        val rpc = FakeChannelRpc(postFixture = "channel_post_announcement.bin") // text only, post 2
        assertEquals(UpdateError.PackageMissing, failure { ChannelPostReader(rpc).readRelease(2) })
    }

    @Test
    fun release_emptyMessageIsMissing() {
        val rpc = FakeChannelRpc(postFixture = "channel_post_empty.bin")
        assertEquals(UpdateError.PackageMissing, failure { ChannelPostReader(rpc).readRelease(3) })
    }

    private fun docBytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun download_usesFixedPartsAndReportsProgress() = runTest {
        val size = ChannelPostReader.PART_SIZE * 2 + 1000
        val rpc = FakeChannelRpc(postFixture = "channel_post_release.bin", document = docBytes(size))
        val reader = ChannelPostReader(rpc)
        val release = reader.readRelease(3).let { ReleasePackage(it.postId, it.fileName, size.toLong(), it.handle) }
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        reader.download(release, out) { progress += it }
        assertEquals(listOf(0L, 524288L, 1048576L), rpc.fileOffsets)
        assertTrue(rpc.fileLimits.all { it == 524288 })
        assertEquals(listOf<Int?>(4, 4, 4), rpc.fileDcs)
        assertArrayEquals(docBytes(size), out.toByteArray())
        assertEquals(listOf(524288L, 1048576L, size.toLong()), progress)
    }

    @Test
    fun download_stopsOnShortPart() = runTest {
        val rpc = FakeChannelRpc(postFixture = "channel_post_release.bin", document = docBytes(1000))
        val reader = ChannelPostReader(rpc)
        val release = reader.readRelease(3)
        val out = ByteArrayOutputStream()
        reader.download(release, out) {}
        assertEquals(1, rpc.fileOffsets.size)
        assertEquals(1000, out.size())
    }

    @Test
    fun download_expiredReferenceRereadsPostOnceThenRetries() = runTest {
        val size = ChannelPostReader.PART_SIZE + 10
        val rpc = FakeChannelRpc(postFixture = "channel_post_release.bin", document = docBytes(size), expireOnPart = 1)
        val reader = ChannelPostReader(rpc)
        val release = reader.readRelease(3).let { ReleasePackage(it.postId, it.fileName, size.toLong(), it.handle) }
        val out = ByteArrayOutputStream()
        reader.download(release, out) {}
        assertArrayEquals(docBytes(size), out.toByteArray())
        // the post is read again after the expiry (resolve is cached, so only one more get)
        assertEquals(2, rpc.calls.count { it.startsWith("get") })
    }

    // --- error mapping (contracts/telegram-api-additions.md) -----------------------------------
    private fun mapped(e: Throwable, fallback: UpdateError = UpdateError.DownloadFailed) = ChannelPostReader.map(e, fallback).failure.error

    @Test
    fun errorMapping() {
        assertEquals(UpdateError.Offline, mapped(TelegramError.Offline()))
        assertEquals(UpdateError.NotConnected, mapped(TelegramError.AuthKeyLost()))
        assertEquals(UpdateError.NotConnected, mapped(TelegramError.TokenRejected()))
        assertEquals(UpdateError.DownloadFailed, mapped(TelegramError.RateLimited(40)))
        assertEquals(UpdateError.DownloadFailed, mapped(TelegramError.FileUnavailable()))
        for (m in listOf("USERNAME_NOT_OCCUPIED", "USERNAME_INVALID", "CHANNEL_PRIVATE", "CHANNEL_INVALID")) {
            assertEquals(m, UpdateError.AnnouncementUnreadable, mapped(TelegramError.Unknown(400, m)))
        }
        assertEquals(UpdateError.PackageMissing, mapped(TelegramError.Unknown(400, "MSG_ID_INVALID"), UpdateError.PackageMissing))
        assertEquals(UpdateError.PackageMissing, mapped(java.io.IOException("x"), UpdateError.PackageMissing))
    }

    @Test
    fun reader_mapsRpcFailures() {
        val rpc = FakeChannelRpc().also { it.failWith = TelegramError.Offline() }
        assertEquals(UpdateError.Offline, failure { ChannelPostReader(rpc).readPostText(2) })
        val rpc2 = FakeChannelRpc().also { it.failWith = TelegramError.Unknown(400, "CHANNEL_PRIVATE") }
        assertEquals(UpdateError.AnnouncementUnreadable, failure { ChannelPostReader(rpc2).readPostText(2) })
    }
}
