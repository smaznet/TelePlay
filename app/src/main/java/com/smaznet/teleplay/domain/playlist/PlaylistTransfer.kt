package com.smaznet.teleplay.domain.playlist

import com.smaznet.teleplay.data.repo.RestoreResult
import com.smaznet.teleplay.data.repo.VideoRepository
import com.smaznet.teleplay.data.telegram.BotMessenger
import com.smaznet.teleplay.data.telegram.TelegramGateway
import com.smaznet.teleplay.domain.IncomingDocument
import com.smaznet.teleplay.domain.TelegramError
import kotlinx.coroutines.CancellationException

data class ImportReport(
    val restored: Int,
    val alreadyPresent: Int,
    val notFound: Int,
    val changed: Int,
    val unreachable: Int,
    val stoppedEarly: Boolean,
    val botMismatch: Boolean,
) {
    val skipped: Int get() = notFound + changed + unreachable
}

sealed class ExportResult {
    object Empty : ExportResult()
    class Sent(val count: Int) : ExportResult()
    object TooLarge : ExportResult()
    object Failed : ExportResult()
}

sealed class ImportOutcome {
    class Done(val report: ImportReport) : ImportOutcome()
    class Rejected(val reason: RejectReason) : ImportOutcome()
    object FileUnavailable : ImportOutcome()
}

/** Export and import use cases (specs/005). Blocking SQLite work happens inside [VideoRepository]. */
class PlaylistTransfer(
    private val videos: VideoRepository,
    private val messenger: BotMessenger,
    private val gateway: TelegramGateway,
    private val botUserId: () -> Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun export(): ExportResult {
        val snapshot = videos.snapshot()
        if (snapshot.isEmpty()) return ExportResult.Empty
        if (snapshot.size > BackupCodec.MAX_ENTRIES) return ExportResult.TooLarge
        val createdAt = now()
        val file = BackupFile(
            BackupCodec.VERSION, createdAt, botUserId(),
            snapshot.map { (v, p) ->
                BackupEntry(
                    messageId = v.messageId, documentId = v.documentId, chatUserId = v.chatUserId, title = v.title,
                    durationSec = v.durationSec, receivedAt = v.receivedAt,
                    positionMs = p?.positionMs, durationMs = p?.durationMs, completed = p?.completed, progressUpdatedAt = p?.updatedAt,
                )
            },
        )
        val bytes = BackupCodec.encode(file)
        if (bytes.size > BackupCodec.MAX_BYTES) return ExportResult.TooLarge
        val sent = messenger.sendDocument(
            BackupCodec.fileName(createdAt), "application/json", bytes, PlaylistMessages.exported(snapshot.size),
        )
        return if (sent.isSuccess) ExportResult.Sent(snapshot.size) else ExportResult.Failed
    }

    /** Restores the backup in [doc] (attached to message [messageId]). Validates the whole file before any write. */
    suspend fun import(messageId: Int, doc: IncomingDocument): ImportOutcome {
        if (doc.size > BackupCodec.MAX_BYTES) return ImportOutcome.Rejected(RejectReason.TOO_LARGE)
        val bytes = try {
            gateway.downloadDocument(messageId, doc, BackupCodec.MAX_BYTES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TelegramError.Unsupported) {
            return ImportOutcome.Rejected(RejectReason.TOO_LARGE)
        } catch (e: TelegramError.FileUnavailable) {
            return ImportOutcome.FileUnavailable
        }
        val decoded = BackupCodec.decode(bytes)
        if (decoded is DecodeResult.Rejected) return ImportOutcome.Rejected(decoded.reason)
        decoded as DecodeResult.Ok

        var restored = 0
        var present = 0
        var notFound = 0
        var changed = decoded.invalidEntries
        var unreachable = 0
        var stoppedEarly = false

        val entries = decoded.file.videos
        for (batch in entries.chunked(BATCH)) {
            val byId = try {
                gateway.fetchMessages(batch.map { it.messageId }).associateBy { it.messageId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramError.RateLimited) {
                stoppedEarly = true
                break
            } catch (e: TelegramError.Offline) {
                throw e
            } catch (e: Exception) {
                unreachable += batch.size
                continue
            }
            for (entry in batch) {
                val msg = byId[entry.messageId]
                if (msg == null) { notFound++; continue }
                try {
                    when (videos.restore(entry, msg)) {
                        RestoreResult.RESTORED -> restored++
                        RestoreResult.ALREADY_PRESENT -> present++
                        RestoreResult.CHANGED -> changed++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    unreachable++
                }
            }
        }
        val mismatch = decoded.file.botUserId != 0L && decoded.file.botUserId != botUserId()
        return ImportOutcome.Done(ImportReport(restored, present, notFound, changed, unreachable, stoppedEarly, mismatch))
    }

    private companion object {
        const val BATCH = 100
    }
}
