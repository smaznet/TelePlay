package com.smaznet.teleplay.domain

import com.smaznet.teleplay.data.repo.ConnectionRepository
import com.smaznet.teleplay.data.repo.VideoRepository
import com.smaznet.teleplay.fakes.FakeTelegramGateway
import com.smaznet.teleplay.fakes.InMemoryConnectionStore
import com.smaznet.teleplay.fakes.InMemoryProgressStore
import com.smaznet.teleplay.fakes.InMemoryTokenVault
import com.smaznet.teleplay.fakes.InMemoryVideoStore

fun msg(
    id: Int = 1, sender: Long = 42, date: Long = 1_700_000_000, text: String? = null,
    video: IncomingVideo? = null, replyTo: Int? = null, document: IncomingDocument? = null,
) = IncomingMessage(id, sender, date, text, video, null, replyTo, document)

fun vid(
    doc: Long = 100, name: String? = "movie.mp4", mime: String = "video/mp4", w: Int = 1280, h: Int = 720,
) = IncomingVideo(doc, 7, byteArrayOf(1), 2, name, mime, 1000, 60, w, h, "m")

/** Wired set of fakes sharing one clock. */
class Env(var time: Long = 1_000_000_000L, authorized: Long? = null) {
    val gateway = FakeTelegramGateway()
    val connStore = InMemoryConnectionStore(
        BotConnection(1, "bot", 2, ConnectionState.AWAITING_PAIRING, authorized, null, null),
    )
    val tokens = InMemoryTokenVault("123:valid")
    val connections = ConnectionRepository(connStore, tokens, gateway).also { it.restore() }
    val videoStore = InMemoryVideoStore()
    val progressStore = InMemoryProgressStore()
    val videos = VideoRepository(
        videoStore, progressStore, { connections.connection.value?.authorizedUserId }, { time },
    )
    val pairing = PairingService(connections, videos, { time })
}
