package com.smaznet.teleplay.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.smaznet.teleplay.BuildConfig
import com.smaznet.teleplay.domain.CacheStats
import com.smaznet.teleplay.domain.ConnectionState
import com.smaznet.teleplay.domain.PairingChallenge
import com.smaznet.teleplay.domain.Video
import com.smaznet.teleplay.domain.remote.PlaybackCommand
import com.smaznet.teleplay.ui.library.LibraryScreen
import com.smaznet.teleplay.ui.pairing.PairingScreen
import com.smaznet.teleplay.ui.settings.SettingsScreen
import com.smaznet.teleplay.ui.settings.UpdateDialog
import com.smaznet.teleplay.domain.update.UpdateState
import com.smaznet.teleplay.ui.proxy.FailureAction
import com.smaznet.teleplay.ui.proxy.ProxyScreen
import com.smaznet.teleplay.ui.proxy.ProxySwitchLogic
import com.smaznet.teleplay.ui.proxy.ToggleAction
import com.smaznet.teleplay.ui.token.RevokedScreen
import com.smaznet.teleplay.ui.token.TokenScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Slot for the Player screen, supplied by the caller. */
typealias PlayerSlot = @Composable (videoId: Long, startFromBeginning: Boolean, onBack: () -> Unit) -> Unit

/**
 * App navigation: a simple back stack. The start destination comes from ConnectionRepository.state, and later
 * state changes (revoked, signed out, paired...) re-root the stack via [reconcile].
 * Back pops the stack; at a root screen it falls through to the system (exit app).
 */
@Composable
fun TelePlayNavHost(
    container: AppContainer,
    playerRoute: PlayerSlot,
    modifier: Modifier = Modifier,
) {
    val state by container.connections.state.collectAsState()
    val stack = remember { mutableStateListOf<Route>(startRoute(state)) }
    val current = stack.last()
    val currentNow by rememberUpdatedState(current)
    var lastPlayedId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state) {
        reconcile(state, currentNow)?.let { stack.clear(); stack.add(it) }
    }
    // Skip requests from the Telegram remote swap the video being played (no wrap-around is decided by the hub).
    LaunchedEffect(container) {
        container.playbackHub.commands.collect { cmd ->
            if (cmd is PlaybackCommand.SkipTo && currentNow is Route.Player) {
                stack[stack.lastIndex] = Route.Player(cmd.videoId, false)
            }
        }
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    when (val route = current) {
        // A fresh host per screen instance: leaving the screen stops its server, and a new visit gets a new secret.
        Route.Token -> {
            val setupHost = remember { container.createSetupHost() }
            TokenScreen(
                onSubmit = container::submitToken, modifier = modifier, setupHost = setupHost,
                onAddProxy = { stack.add(Route.Proxies(startWithAdd = container.proxies.proxies.value.isEmpty(), enableOnAdd = true)) },
            )
        }

        Route.Revoked -> {
            val setupHost = remember { container.createSetupHost() }
            RevokedScreen(
                onEnterNewToken = { stack.clear(); stack.add(Route.Token) },
                modifier = modifier,
                setupHost = setupHost,
            )
        }

        Route.Pairing -> {
            val challenge by container.pairing.challenge.collectAsState()
            val connection by container.connections.connection.collectAsState()
            LaunchedEffect(Unit) { withContext(Dispatchers.IO) { container.pairing.ensureChallenge() } }
            PairingScreen(
                challenge = challenge as PairingChallenge?,
                botName = connection?.botName,
                onRegenerate = { scope.launch(Dispatchers.IO) { container.pairing.startPairing() } },
                onDisconnect = { scope.launch { runCatching { container.disconnect() } } },
                onLeave = { stack.clear(); stack.add(Route.Token) },
                modifier = modifier,
            )
        }

        Route.Library -> {
            val videosFlow = remember(container) { container.videos.videos.flowOn(Dispatchers.IO) }
            val continueFlow = remember(container) { container.videos.continueWatching().flowOn(Dispatchers.IO) }
            val videos by videosFlow.collectAsState(initial = null)
            val cont by continueFlow.collectAsState(initial = null)
            val proxyState by container.proxies.state.collectAsState()
            val proxyList by container.proxies.proxies.collectAsState()
            val switchUi = ProxySwitchLogic.ui(proxyState, proxyList.firstOrNull { it.id == proxyState.selectedId }, proxyList.size, state)
            LibraryScreen(
                videos = videos,
                continueWatching = cont,
                progressOf = { id -> container.videos.progress(id).flowOn(Dispatchers.IO) },
                thumbnail = container.thumbnails,
                offline = state == ConnectionState.OFFLINE,
                lastPlayedId = lastPlayedId,
                onPlay = { video: Video, fromStart: Boolean ->
                    scope.launch {
                        if (fromStart) withContext(Dispatchers.IO) { container.videos.startOver(video.id) }
                        stack.add(Route.Player(video.id, fromStart))
                    }
                },
                onRefresh = container.refresh,
                onOpenSettings = { stack.add(Route.Settings) },
                modifier = modifier,
                proxySwitch = switchUi,
                onToggleProxy = { action ->
                    when (action) {
                        ToggleAction.ENABLE -> container.proxies.setEnabled(true)
                        ToggleAction.DISABLE -> container.proxies.setEnabled(false)
                        ToggleAction.OPEN_ADD -> stack.add(Route.Proxies(startWithAdd = true))
                        ToggleAction.OPEN_LIST -> stack.add(Route.Proxies())
                        ToggleAction.OPEN_FAILURE_MENU -> Unit
                    }
                },
                onProxyFailureAction = { action ->
                    when (action) {
                        FailureAction.RETRY -> scope.launch { container.refresh() }
                        FailureAction.CHOOSE_ANOTHER -> stack.add(Route.Proxies())
                        FailureAction.GO_DIRECT -> container.proxies.setEnabled(false)
                    }
                },
            )
        }

        is Route.Proxies -> ProxyScreen(
            repository = container.proxies,
            tester = container.proxyTester,
            health = container.proxyHealth,
            modifier = modifier,
            startWithAdd = route.startWithAdd,
            enableOnAdd = route.enableOnAdd,
        )

        Route.Settings -> {
            val notifyAdded by container.remoteSettings.notifyAdded.collectAsState()
            val liveRemote by container.remoteSettings.liveRemote.collectAsState()
            var stats by remember { mutableStateOf<CacheStats?>(null) }
            var cleared by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { stats = withContext(Dispatchers.IO) { runCatching { container.cacheControl.usage() }.getOrNull() } }
            val updateState by container.updater.state.collectAsState()
            var installBlocked by remember { mutableStateOf(false) }
            // Leaving Settings (Back, disconnect) must not leave a download running.
            DisposableEffect(Unit) { onDispose { container.updater.dismiss() } }
            SettingsScreen(
                cacheStats = stats,
                cacheClearedNotice = cleared,
                onClearCache = {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { container.cacheControl.clear() } }
                        stats = withContext(Dispatchers.IO) { runCatching { container.cacheControl.usage() }.getOrNull() }
                        cleared = true
                    }
                },
                onResetPairing = { scope.launch(Dispatchers.IO) { container.pairing.reset() } },
                onDisconnect = { scope.launch { runCatching { container.disconnect() } } },
                modifier = modifier,
                notifyAdded = notifyAdded,
                onNotifyAddedChange = container.remoteSettings::setNotifyAdded,
                liveRemote = liveRemote,
                onLiveRemoteChange = container.remoteSettings::setLiveRemote,
                updatesAvailable = BuildConfig.UPDATES_ENABLED,
                updateEnabled = container.connections.storedToken() != null,
                onCheckUpdate = container.updater::check,
                installedVersionName = BuildConfig.VERSION_NAME,
                proxyCount = container.proxies.proxies.collectAsState().value.size,
                proxyInUse = container.proxies.state.collectAsState().value.takeIf { it.active }?.let { container.proxies.selected()?.label },
                onOpenProxies = { stack.add(Route.Proxies()) },
            )
            val ready = updateState as? UpdateState.ReadyToInstall
            if (BuildConfig.UPDATES_ENABLED && updateState != UpdateState.Idle) {
                UpdateDialog(
                    state = updateState,
                    installBlocked = installBlocked && ready != null,
                    onUpdate = container.updater::confirmDownload,
                    onRetry = container.updater::check,
                    onInstall = {
                        if (ready != null) {
                            installBlocked = !container.installer.canInstall() || !container.installer.install(ready.file)
                        }
                    },
                    onOpenSettings = { container.installer.openPermissionSettings() },
                    onDismiss = { installBlocked = false; container.updater.dismiss() },
                )
            }
        }

        // key(): a new video (remote skip) must dispose the old player screen and its controller first.
        is Route.Player -> key(route.videoId, route.startFromBeginning) {
            playerRoute(route.videoId, route.startFromBeginning) {
                lastPlayedId = route.videoId
                if (stack.size > 1) stack.removeAt(stack.lastIndex)
            }
        }
    }
}
