package com.smaznet.teleplay.fakes

import com.smaznet.teleplay.data.telegram.BotMessenger
import com.smaznet.teleplay.domain.remote.RemoteButton
import com.smaznet.teleplay.domain.remote.RemoteFailure
import com.smaznet.teleplay.domain.remote.RemoteSendException

/** Records every call; failures can be scripted per call kind. */
class FakeBotMessenger : BotMessenger {
    sealed class Call {
        data class Send(val id: Int, val text: String, val keyboard: List<List<RemoteButton>>?) : Call()
        data class Edit(val id: Int, val text: String, val keyboard: List<List<RemoteButton>>?) : Call()
        data class Pin(val id: Int) : Call()
        data class Unpin(val id: Int) : Call()
        data class Document(val id: Int, val fileName: String, val mime: String, val bytes: ByteArray, val caption: String) : Call()
        data class SetCommands(val commands: List<Pair<String, String>>) : Call()
        data class Answer(val queryId: Long, val toast: String?) : Call()
    }

    val calls = mutableListOf<Call>()
    private var nextId = 100

    /** Failure to return for the next call of that kind (consumed once); keys: send, edit, pin, unpin, answer. */
    val failures = mutableMapOf<String, ArrayDeque<RemoteFailure>>()
    var failAll: RemoteFailure? = null

    fun failNext(kind: String, f: RemoteFailure) { failures.getOrPut(kind) { ArrayDeque() }.addLast(f) }

    private fun fail(kind: String): RemoteFailure? = failAll ?: failures[kind]?.removeFirstOrNull()

    val sends get() = calls.filterIsInstance<Call.Send>()
    val edits get() = calls.filterIsInstance<Call.Edit>()
    val pins get() = calls.filterIsInstance<Call.Pin>()
    val unpins get() = calls.filterIsInstance<Call.Unpin>()
    val documents get() = calls.filterIsInstance<Call.Document>()
    val setCommandCalls get() = calls.filterIsInstance<Call.SetCommands>()
    val answers get() = calls.filterIsInstance<Call.Answer>()

    override suspend fun sendText(text: String, keyboard: List<List<RemoteButton>>?): Result<Int> {
        fail("send")?.let { return Result.failure(RemoteSendException(it)) }
        val id = nextId++
        calls += Call.Send(id, text, keyboard)
        return Result.success(id)
    }

    override suspend fun editText(messageId: Int, text: String, keyboard: List<List<RemoteButton>>?): Result<Unit> {
        fail("edit")?.let { return Result.failure(RemoteSendException(it)) }
        calls += Call.Edit(messageId, text, keyboard)
        return Result.success(Unit)
    }

    override suspend fun pin(messageId: Int): Result<Unit> {
        fail("pin")?.let { return Result.failure(RemoteSendException(it)) }
        calls += Call.Pin(messageId)
        return Result.success(Unit)
    }

    override suspend fun unpin(messageId: Int): Result<Unit> {
        fail("unpin")?.let { return Result.failure(RemoteSendException(it)) }
        calls += Call.Unpin(messageId)
        return Result.success(Unit)
    }

    override suspend fun answerCallback(queryId: Long, toast: String?): Result<Unit> {
        fail("answer")?.let { return Result.failure(RemoteSendException(it)) }
        calls += Call.Answer(queryId, toast)
        return Result.success(Unit)
    }

    override suspend fun sendDocument(fileName: String, mime: String, bytes: ByteArray, caption: String): Result<Int> {
        fail("document")?.let { return Result.failure(RemoteSendException(it)) }
        val id = nextId++
        calls += Call.Document(id, fileName, mime, bytes, caption)
        return Result.success(id)
    }

    override suspend fun setCommands(commands: List<Pair<String, String>>): Result<Unit> {
        fail("commands")?.let { return Result.failure(RemoteSendException(it)) }
        calls += Call.SetCommands(commands)
        return Result.success(Unit)
    }
}
