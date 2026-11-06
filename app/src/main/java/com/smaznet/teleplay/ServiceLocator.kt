package com.smaznet.teleplay

import android.app.Application
import androidx.compose.ui.graphics.ImageBitmap
import com.smaznet.teleplay.data.cache.ChunkCache
import com.smaznet.teleplay.data.cache.SqliteCacheIndex
import com.smaznet.teleplay.data.db.DbChanges
import com.smaznet.teleplay.data.db.SqliteProgressStore
import com.smaznet.teleplay.data.db.SqliteUpdateStateStore
import com.smaznet.teleplay.data.db.SqliteVideoStore
import com.smaznet.teleplay.data.db.TelePlayDb
import com.smaznet.teleplay.data.repo.ConnectionRepository
import com.smaznet.teleplay.data.repo.SecretStoreTokenVault
import com.smaznet.teleplay.data.repo.SqliteConnectionStore
import com.smaznet.teleplay.data.repo.VideoRepository
import com.smaznet.teleplay.data.secure.SecretStore
import com.smaznet.teleplay.data.telegram.ConnectivityWatcher
import com.smaznet.teleplay.data.telegram.FileReferenceStore
import com.smaznet.teleplay.data.telegram.GatewayConfig
import com.smaznet.teleplay.data.telegram.MtprotoGateway
import com.smaznet.teleplay.data.telegram.SecretSessionStore
import com.smaznet.teleplay.data.telegram.SecretTokenStore
import com.smaznet.teleplay.data.telegram.TelegramGateway
import com.smaznet.teleplay.data.update.ApkInstaller
import com.smaznet.teleplay.data.update.UpdateFiles
import com.smaznet.teleplay.domain.update.AppUpdater
import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.domain.Availability
import com.smaznet.teleplay.domain.BotInfo
import com.smaznet.teleplay.domain.CacheStats
import com.smaznet.teleplay.domain.ConnectionState
import com.smaznet.teleplay.domain.DisconnectUseCase
import com.smaznet.teleplay.domain.PairingResult
import com.smaznet.teleplay.domain.PairingService
import com.smaznet.teleplay.domain.Video
import com.smaznet.teleplay.domain.playlist.CommandMenuRegistrar
import com.smaznet.teleplay.domain.playlist.PlaylistCommands
import com.smaznet.teleplay.domain.playlist.PlaylistTransfer
import com.smaznet.teleplay.domain.remote.AddedNotifier
import com.smaznet.teleplay.domain.remote.PlaybackHub
import com.smaznet.teleplay.domain.remote.RemoteCommandHandler
import com.smaznet.teleplay.domain.remote.RemoteSession
import com.smaznet.teleplay.data.db.SqliteRemoteMessageStore
import com.smaznet.teleplay.domain.remote.RemoteSettings
import com.smaznet.teleplay.domain.remote.SharedBooleanPrefs
import com.smaznet.teleplay.mtproto.transport.ProxyClock
import com.smaznet.teleplay.mtproto.transport.RoutingTransportFactory
import com.smaznet.teleplay.data.proxy.SecretProxyStore
import com.smaznet.teleplay.data.proxy.SharedProxyPrefs
import com.smaznet.teleplay.domain.proxy.AddResult
import com.smaznet.teleplay.domain.proxy.ProxyCommands
import com.smaznet.teleplay.domain.proxy.ProxyDraft
import com.smaznet.teleplay.domain.proxy.ProxyImport
import com.smaznet.teleplay.domain.proxy.ProxyHealthCheck
import com.smaznet.teleplay.setup.ProxySubmitResult
import com.smaznet.teleplay.domain.proxy.ProxyRepository
import com.smaznet.teleplay.domain.proxy.ProxyTester
import com.smaznet.teleplay.domain.proxy.ProxyTesting
import com.smaznet.teleplay.playback.PlayerController
import com.smaznet.teleplay.playback.ProgressSink
import com.smaznet.teleplay.setup.AndroidLocalAddress
import com.smaznet.teleplay.setup.SetupHost
import com.smaznet.teleplay.setup.SubmitBusyException
import com.smaznet.teleplay.ui.nav.AppContainer
import com.smaznet.teleplay.ui.nav.CacheControl
import com.smaznet.teleplay.ui.player.PlayerDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/** Manual dependency wiring. */
class ServiceLocator(private val app: Application) : AppContainer, PlayerDeps {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val secrets = SecretStore(app)
    val db = TelePlayDb(app)
    private val changes = DbChanges()
    private val videoStore = SqliteVideoStore(db, changes)
    private val progressStore = SqliteProgressStore(db, changes)
    private val updateStates = SqliteUpdateStateStore(db)
    val chunkCache = ChunkCache(File(app.cacheDir, "video-cache"), SqliteCacheIndex(db))

    private val proxyStore = SecretProxyStore(secrets)

    init {
        // The learned clock error for fake-TLS proxies survives restarts (and disconnects, like the proxies themselves).
        val clockPrefs = app.getSharedPreferences("teleplay_proxy", Application.MODE_PRIVATE)
        ProxyClock.restore(clockPrefs.getLong("clock_offset", 0L))
        ProxyClock.onChanged = { clockPrefs.edit().putLong("clock_offset", it).apply() }
    }

    /** Saved proxies and the on/off switch. They survive disconnect (research R13); only the viewer removes one. */
    override val proxies = ProxyRepository(proxyStore, SharedProxyPrefs(app))
    override val proxyTester: ProxyTesting = ProxyTester()
    override val proxyHealth = ProxyHealthCheck(proxyTester, proxies)

    val gateway = MtprotoGateway(
        config = GatewayConfig(BuildConfig.TELEGRAM_API_ID, BuildConfig.TELEGRAM_API_HASH),
        factory = RoutingTransportFactory { proxies.route() },
        scope = scope,
        sessions = SecretSessionStore(secrets),
        tokens = SecretTokenStore(secrets),
        updateStates = updateStates,
        fileRefs = FileReferenceStore { videoId, ref -> videoStore.get(videoId)?.let { videoStore.updateFileReference(it.documentId, ref) } },
    )

    override val connections = ConnectionRepository(SqliteConnectionStore(db), SecretStoreTokenVault(secrets), gateway)
    override val videos = VideoRepository(videoStore, progressStore, { connections.connection.value?.authorizedUserId })
    override val pairing = PairingService(
        connections, videos, updateStateResetter = { updateStates.clear() },
        // Called on an IO thread (Settings); bounded so a dead network cannot hold the reset hostage.
        beforeReset = { runBlocking { withTimeoutOrNull(10_000) { remoteSession.shutdown(unpin = true) } } },
    )
    override val disconnect = DisconnectUseCase(
        connections, videos,
        gatewayWiper = { gateway.signOutAndWipe() },
        // Disconnect erases every secret and the Keystore key, but saved proxies are kept: re-seal them under the new key.
        sessionWiper = { val keep = proxyStore.snapshot(); secrets.wipe(); proxyStore.restore(keep) },
        cacheWiper = { chunkCache.wipe(); updater.onDisconnected() },
        remoteWiper = { withTimeoutOrNull(10_000) { remoteSession.shutdown(unpin = true) }; Unit },
    )

    override val remoteSettings = RemoteSettings(SharedBooleanPrefs(app))

    private fun installedVersionCode(): Int {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }

    /** The only update path: reads the public update channel through the connected bot, never sends anything. */
    override val updater = AppUpdater(
        feed = gateway,
        files = UpdateFiles(
            dir = File(app.cacheDir, "updates"),
            freeSpace = { it.usableSpace },
            archiveInfo = { f ->
                app.packageManager.getPackageArchiveInfo(f.path, 0)?.let { i ->
                    i.packageName to (if (android.os.Build.VERSION.SDK_INT >= 28) i.longVersionCode.toInt() else @Suppress("DEPRECATION") i.versionCode)
                }
            },
            expectedPackage = app.packageName,
            installedVersion = ::installedVersionCode,
        ),
        installedVersion = ::installedVersionCode,
        isConnected = { connections.storedToken() != null },
        scope = scope,
    )
    override val installer = ApkInstaller(app)

    /** Sends to the authorized user's private chat only; null peer (not paired or hash unknown) means nothing is sent. */
    private val messenger = gateway.botMessenger {
        connections.connection.value?.let { c ->
            val id = c.authorizedUserId
            val hash = c.authorizedUserAccessHash
            if (id != null && hash != null) id to hash else null
        }
    }
    private val addedNotifier = AddedNotifier(messenger, remoteSettings, scope)
    private val playlistCommands = PlaylistCommands(
        authorizedUserId = { connections.connection.value?.authorizedUserId },
        transfer = PlaylistTransfer(
            videos = videos, messenger = messenger, gateway = gateway,
            botUserId = { connections.connection.value?.botUserId ?: 0L },
        ),
        gateway = gateway, messenger = messenger,
    )
    private val proxyCommands = ProxyCommands(
        authorizedUserId = { connections.connection.value?.authorizedUserId },
        importer = ProxyImport(proxies),
        gateway = gateway, messenger = messenger,
        proxyIsOff = { !proxies.state.value.enabled },
    )
    private val commandMenu = CommandMenuRegistrar(messenger)

    override val playbackHub = PlaybackHub()

    /** The authorized user's id when messaging them is possible (paired and access hash known). */
    private fun peerUserId(): Long? = connections.connection.value?.takeIf { it.authorizedUserAccessHash != null }?.authorizedUserId

    private val remoteSession = RemoteSession(
        hub = playbackHub, messenger = messenger, store = SqliteRemoteMessageStore(db),
        settings = remoteSettings, scope = scope, peerUserId = ::peerUserId,
    )
    private val remoteCommands = RemoteCommandHandler(
        hub = playbackHub, messenger = messenger,
        authorizedUserId = { connections.connection.value?.authorizedUserId },
        currentSessionId = remoteSession::currentSessionId,
        onAccepted = { remoteSession.requestRefresh() },
        onStale = remoteSession::onStalePress,
    )

    /** Marks a video REMOTE_GONE when Telegram no longer has its message or file (T064). */
    private val goneMarking = object : TelegramGateway by gateway {
        override suspend fun refreshFileReference(video: Video): Video = try {
            gateway.refreshFileReference(video)
        } catch (e: TelegramError.FileUnavailable) {
            videos.markUnavailable(video.documentId, Availability.REMOTE_GONE)
            throw e
        }
    }

    private val connectivity = ConnectivityWatcher(app) { gateway.onForeground() }

    /** Starts background work: gateway state mirroring, message routing and connectivity callbacks. */
    fun start() {
        scope.launch { connections.observeGateway() }
        scope.launch { proxies.routeChanges.collect { gateway.onProxyChanged() } }
        scope.launch { videos.videos.collect { list -> playbackHub.setLibraryOrder(list.filter { it.availability == Availability.PLAYABLE }.map { it.id }) } }
        scope.launch {
            gateway.incomingMessages.collect { msg ->
                when (connections.state.value) {
                    ConnectionState.AWAITING_PAIRING -> {
                        val r = pairing.onMessage(msg)
                        if (r is PairingResult.Paired) msg.senderAccessHash?.let { connections.saveAuthorizedAccessHash(it) }
                    }
                    ConnectionState.SIGNED_OUT, ConnectionState.REJECTED, ConnectionState.REVOKED -> {}
                    else -> {
                        // Export/import/help commands (and a backup attached to /import) are never ingested as videos.
                        // /proxies is the only place proxy links are read (constitution v2.4.0), and is not ingested either.
                        if (playlistCommands.recognise(msg) != null) scope.launch { playlistCommands.handle(msg) }
                        else if (proxyCommands.recognise(msg)) scope.launch { proxyCommands.handle(msg) }
                        else addedNotifier.offer(videos.ingestResult(msg))
                    }
                }
            }
        }
        scope.launch {
            gateway.userAccessHashes.collect { (userId, hash) ->
                if (connections.connection.value?.authorizedUserId == userId) connections.saveAuthorizedAccessHash(hash)
            }
        }
        scope.launch {
            var registered = false
            connections.state.collect { st ->
                val ready = st == ConnectionState.READY
                if (ready && !registered) commandMenu.register()
                registered = ready
            }
        }
        scope.launch { gateway.callbackQueries.collect { q -> remoteCommands.handle(q) } }
        remoteSession.start()
        if (connections.restore()) gateway.resume()
        connectivity.start()
    }

    /** Called when the UI comes to the foreground. */
    fun onForeground() = gateway.onForeground()

    // --- AppContainer ---------------------------------------------------------------------
    override val cacheControl = object : CacheControl {
        override fun usage(): CacheStats = chunkCache.usage()
        override fun clear() { chunkCache.clear() }
    }
    // A thumbnail that cannot be fetched (offline, proxy down) is simply missing; it must never take the UI down.
    override val thumbnails: suspend (Video) -> ImageBitmap? = {
        try { gateway.thumbnailLoader().load(it) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }
    override val refresh: suspend () -> Unit = { gateway.onForeground() }
    /** One token submission at a time: manual entry and the phone setup page must not run connect() concurrently. */
    private val submitLock = Mutex()

    override suspend fun submitToken(token: String): Result<BotInfo> {
        if (!submitLock.tryLock()) return Result.failure(SubmitBusyException())
        try {
            return connections.connect(token)
        } finally {
            submitLock.unlock()
        }
    }

    /**
     * A proxy from the phone page: saved, selected and switched on, because during setup the viewer has no working
     * route yet and the token cannot be checked without one (contracts/setup-http.md).
     */
    private fun addProxyFromSetup(draft: ProxyDraft): ProxySubmitResult = when (val r = proxies.add(draft)) {
        is AddResult.Added -> { proxies.select(r.proxy.id); proxies.setEnabled(true); ProxySubmitResult.Added(r.proxy.label) }
        is AddResult.Duplicate -> { proxies.select(r.existing.id); proxies.setEnabled(true); ProxySubmitResult.Added(r.existing.label, duplicate = true) }
        AddResult.LimitReached -> ProxySubmitResult.LimitReached
        is AddResult.Invalid -> ProxySubmitResult.Failed
    }

    override fun createSetupHost(): SetupHost =
        SetupHost(submit = ::submitToken, addresses = AndroidLocalAddress(app), submitProxy = { addProxyFromSetup(it) })

    // --- PlayerDeps -----------------------------------------------------------------------
    override suspend fun video(id: Long): Video? = withContext(Dispatchers.IO) { videos.video(id) }
    override suspend fun resumePositionMs(id: Long): Long? = withContext(Dispatchers.IO) { videos.resumePositionMs(id) }
    override suspend fun startOver(id: Long) = withContext(Dispatchers.IO) { videos.startOver(id) }

    override fun createController(video: Video, startPositionMs: Long): PlayerController =
        PlayerController(
            context = app,
            video = video,
            cache = chunkCache,
            gateway = goneMarking,
            progress = object : ProgressSink {
                override fun save(videoId: Long, positionMs: Long, durationMs: Long) {
                    scope.launch(Dispatchers.IO) { videos.saveProgress(videoId, positionMs, durationMs) }
                }
            },
            onUnplayable = { v -> scope.launch(Dispatchers.IO) { videos.markUnavailable(v.documentId, Availability.UNPLAYABLE_FORMAT) } },
            startPositionMs = startPositionMs,
            hub = playbackHub,
        )
}
