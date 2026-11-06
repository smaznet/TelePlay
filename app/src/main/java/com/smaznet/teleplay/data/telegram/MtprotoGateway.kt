package com.smaznet.teleplay.data.telegram

import com.smaznet.teleplay.domain.*
import com.smaznet.teleplay.domain.remote.CallbackQuery
import com.smaznet.teleplay.domain.update.ReleasePackage
import com.smaznet.teleplay.domain.update.UpdateFeed
import com.smaznet.teleplay.mtproto.ClientInfo
import com.smaznet.teleplay.mtproto.Rpc
import com.smaznet.teleplay.mtproto.tl.generated.*
import com.smaznet.teleplay.mtproto.transport.TransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.OutputStream

class GatewayConfig(val apiId: Int, val apiHash: String)

/**
 * Real [TelegramGateway] over MTProto. Wiring: provide the stores (secret ones via
 * [SecretSessionStore]/[SecretTokenStore]), a [wipeCallbacks] list that erases the database and the
 * chunk cache, call [resume] on app start and [onForeground] when the app returns to the foreground.
 * [incomingMessages] has a single consumer (messages are queued until collected).
 */
class MtprotoGateway(
    private val config: GatewayConfig,
    factory: TransportFactory,
    private val scope: CoroutineScope,
    private val sessions: SessionStore,
    private val tokens: TokenStore,
    private val updateStates: UpdateStateStore,
    private val fileRefs: FileReferenceStore,
    private val directory: DcDirectory = DcDirectory(),
    private val wipeCallbacks: List<suspend () -> Unit> = emptyList(),
    private val pollIntervalMs: Long = 60_000,
) : TelegramGateway, UpdateFeed {

    private val state = MutableStateFlow(GatewayState.CONNECTING)
    private val messages = Channel<IncomingMessage>(Channel.UNLIMITED)
    private val callbacks = MutableSharedFlow<CallbackQuery>(extraBufferCapacity = 64)
    private val hashes = MutableSharedFlow<Pair<Long, Long>>(extraBufferCapacity = 64)

    @Volatile private var loop: UpdateLoop? = null
    private var loopJob: Job? = null

    private val client = ClientInfo(config.apiId)
    private val pool = DcPool(
        factory, scope, directory, sessions, tokens, config.apiId, config.apiHash,
        onUpdatesPushed = { loop?.poke() },
        onPushedPayload = ::onPushed,
    )
    private val raw = Rpc({ pool.raw(it) }, DcDirectory.DEFAULT_DC, client)
    private val rpc = Rpc({ pool.authorized(it) }, DcDirectory.DEFAULT_DC, client)
    private val reader = FileReader(rpc) { fetchFreshReference(it) }
    private val thumbs by lazy { ThumbnailLoader(reader) }

    /** Read-only update-channel access (constitution v2.2.0); each call re-authenticates once like the others. */
    private val channelReader by lazy {
        ChannelPostReader(object : ChannelRpc {
            override suspend fun <R> invoke(fn: com.smaznet.teleplay.mtproto.tl.TlFunction<R>, dcId: Int?): R =
                withReauth { if (dcId == null) rpc.invoke(fn) else rpc.invoke(fn, dcId) }
        })
    }

    init {
        pool.authRpc = raw
        sessions.loadHomeDc()?.let { setHome(it) }
    }

    override val connectionState: Flow<GatewayState> get() = state
    override val incomingMessages: Flow<IncomingMessage> get() = messages.receiveAsFlow()
    override val callbackQueries: Flow<CallbackQuery> get() = callbacks
    override val userAccessHashes: Flow<Pair<Long, Long>> get() = hashes

    /** Sender for the remote; [peer] yields the authorized user's (id, access hash) or null. */
    fun botMessenger(peer: () -> Pair<Long, Long>?): BotMessenger = RpcBotMessenger(rpc, peer)

    private fun onPushed(body: ByteArray) {
        val r = PushedUpdates.parse(body) ?: return
        r.callbacks.forEach { callbacks.tryEmit(it) }
        r.userHashes.forEach { (id, h) -> hashes.tryEmit(id to h) }
    }

    /** Thumbnail loader sharing this gateway's connections. */
    fun thumbnailLoader(): ThumbnailLoader = thumbs

    /** Restarts the update loop for a previously stored token. False when signed out. */
    fun resume(): Boolean {
        if (tokens.load() == null) return false
        startLoop()
        return true
    }

    fun onForeground() { loop?.poke() }

    /** The proxy route changed: reconnect on the new route, keeping keys, state and the update loop. */
    fun onProxyChanged() {
        pool.resetConnections()
        loop?.poke()
    }

    private fun setHome(dc: Int) {
        raw.homeDc = dc
        rpc.homeDc = dc
    }

    override suspend fun signInWithBotToken(token: String): Result<BotInfo> {
        val t = BotSignIn.normalize(token) ?: return Result.failure(TelegramError.TokenRejected())
        state.value = GatewayState.CONNECTING
        return try {
            stopLoop()
            pool.closeAll()
            sessions.clearAll()
            updateStates.clear()
            setHome(DcDirectory.DEFAULT_DC)
            val info = BotSignIn(raw, config.apiId, config.apiHash).signIn(t)
            tokens.save(t)
            sessions.saveHomeDc(raw.homeDc)
            setHome(raw.homeDc)
            pool.setHome(raw.homeDc)
            state.value = GatewayState.READY
            startLoop()
            Result.success(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TelegramError) {
            state.value = if (e is TelegramError.TokenRejected) GatewayState.REJECTED else GatewayState.OFFLINE
            Result.failure(e)
        } catch (e: Exception) {
            state.value = GatewayState.OFFLINE
            Result.failure(TelegramError.Offline(e))
        }
    }

    private fun startLoop() {
        stopLoop()
        pool.setHome(rpc.homeDc)
        val l = UpdateLoop(
            api = RpcUpdatesApi(rpc), store = updateStates,
            emit = { m -> m.senderAccessHash?.let { hashes.tryEmit(m.senderUserId to it) }; messages.send(m) },
            onState = { state.value = it },
            recover = { reauth() },
            pollMs = pollIntervalMs,
        )
        loop = l
        loopJob = scope.launch {
            launch { try { directory.refresh(rpc) } catch (_: Exception) { } }
            l.run()
        }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        loop = null
    }

    /** Creates a fresh key on the home DC and signs in again with the stored token. */
    private suspend fun reauth(): Boolean {
        val token = tokens.load() ?: return false
        val dc = rpc.homeDc
        pool.dropKey(dc)
        return try {
            raw.invoke(F_auth_importBotAuthorization(0, config.apiId, config.apiHash, token), dc)
            pool.setHome(dc)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun <R> withReauth(block: suspend () -> R): R = try {
        block()
    } catch (e: TelegramError.AuthKeyLost) {
        if (!reauth()) {
            state.value = GatewayState.REVOKED
            throw e
        }
        block()
    }

    override suspend fun readAnnouncementText(): String = channelReader.readPostText(ANNOUNCEMENT_POST_ID)

    override suspend fun readRelease(postId: Int): ReleasePackage = channelReader.readRelease(postId)

    override suspend fun download(release: ReleasePackage, sink: OutputStream, onProgress: (Long) -> Unit) =
        channelReader.download(release, sink, onProgress)

    override suspend fun refreshFileReference(video: Video): Video {
        val ref = fetchFreshReference(video)
        fileRefs.update(video.id, ref)
        return video.withReference(ref)
    }

    private suspend fun fetchFreshReference(video: Video): ByteArray = withReauth {
        val res = rpc.invoke(F_messages_getMessages(listOf(C_inputMessageID(video.messageId))))
        val list = when (res) {
            is C_messages_messages -> res.messages
            is C_messages_messagesSlice -> res.messages
            is C_messages_channelMessages -> res.messages
            is C_messages_messagesNotModified -> emptyList()
        }
        val doc = list.asSequence()
            .mapNotNull { it as? C_message }
            .filter { it.id == video.messageId }
            .mapNotNull { (it.media as? C_messageMediaDocument)?.document as? C_document }
            .firstOrNull { it.id == video.documentId }
            ?: throw TelegramError.FileUnavailable()
        doc.file_reference.also { fileRefs.update(video.id, it) }
    }

    private suspend fun rawMessages(ids: List<Int>): Pair<List<T_Message>, List<T_User>> = withReauth {
        when (val res = rpc.invoke(F_messages_getMessages(ids.map { C_inputMessageID(it) }))) {
            is C_messages_messages -> res.messages to res.users
            is C_messages_messagesSlice -> res.messages to res.users
            is C_messages_channelMessages -> res.messages to res.users
            is C_messages_messagesNotModified -> emptyList<T_Message>() to emptyList()
        }
    }

    override suspend fun fetchMessages(ids: List<Int>): List<IncomingMessage> =
        ids.chunked(100).flatMap { batch ->
            val (list, users) = rawMessages(batch)
            val hashes = PushedUpdates.userHashes(users)
            list.mapNotNull { it.toIncoming(hashes) }
        }

    override suspend fun fetchDocument(messageId: Int): IncomingDocument? =
        rawMessages(listOf(messageId)).first
            .firstOrNull { it is C_message && it.id == messageId }
            ?.let { ((it as C_message).media as? C_messageMediaDocument)?.document }
            ?.let { documentToIncomingDocument(it) }

    override suspend fun downloadDocument(messageId: Int, doc: IncomingDocument, maxBytes: Int): ByteArray =
        withReauth {
            reader.readDocument(doc, maxBytes) {
                fetchDocument(messageId)?.fileReference ?: throw TelegramError.FileUnavailable()
            }
        }

    override suspend fun readChunk(video: Video, chunkIndex: Int): ByteArray =
        withReauth { reader.readChunk(video, chunkIndex) }

    override suspend fun signOutAndWipe() {
        stopLoop()
        pool.closeAll()
        tokens.clear()
        sessions.clearAll()
        updateStates.clear()
        wipeCallbacks.forEach { it() }
        thumbs.clear()
    }

    private fun Video.withReference(ref: ByteArray) = Video(
        id, chatUserId, messageId, documentId, accessHash, ref, dcId, title, durationSec, sizeBytes,
        mimeType, width, height, thumbSize, receivedAt, availability,
    )

    private companion object {
        /** The announcement is always post 2 of the update channel. */
        const val ANNOUNCEMENT_POST_ID = 2
    }
}
