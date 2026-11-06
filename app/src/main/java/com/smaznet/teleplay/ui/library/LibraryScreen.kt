package com.smaznet.teleplay.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.smaznet.teleplay.R
import com.smaznet.teleplay.domain.Availability
import com.smaznet.teleplay.domain.PlaybackProgress
import com.smaznet.teleplay.domain.Video
import com.smaznet.teleplay.ui.common.HeroButton
import com.smaznet.teleplay.ui.common.HeroChip
import com.smaznet.teleplay.ui.common.HeroTone
import com.smaznet.teleplay.ui.common.requestSafely
import com.smaznet.teleplay.ui.common.HeroIcon
import com.smaznet.teleplay.ui.common.IconButtonPadding
import com.smaznet.teleplay.ui.common.TeleIcons
import com.smaznet.teleplay.ui.proxy.FailureAction
import com.smaznet.teleplay.ui.proxy.ProxySwitch
import com.smaznet.teleplay.ui.proxy.ProxySwitchLogic
import com.smaznet.teleplay.ui.proxy.ProxySwitchUi
import com.smaznet.teleplay.ui.proxy.ToggleAction
import com.smaznet.teleplay.ui.theme.HeroContent2
import com.smaznet.teleplay.ui.theme.HeroForeground
import com.smaznet.teleplay.ui.theme.HeroForegroundMuted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private val CONTINUE_CARD_WIDTH = 320.dp
private val MAIN_CARD_WIDTH = 256.dp
private val CARD_GAP = 20.dp
private val CARD_SHAPE = RoundedCornerShape(12.dp)
private const val SCREEN_EDGE_DP = 48

/** The hero takes most of the first screen; the next shelf's title peeks in below it so the page reads as scrollable. */
private const val HERO_HEIGHT_FRACTION = 0.8f

/**
 * Library, laid out like the Apple TV app: a full-bleed hero for the video to watch next, then horizontal shelves.
 * [videos]/[continueWatching] are null while loading. Initial focus (see [initialFocus]): the video the user just came
 * back from, else the hero's Play button, else Refresh.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LibraryScreen(
    videos: List<Video>?,
    continueWatching: List<Video>?,
    progressOf: (Long) -> Flow<PlaybackProgress?>,
    thumbnail: suspend (Video) -> ImageBitmap?,
    offline: Boolean,
    lastPlayedId: Long?,
    onPlay: (Video, Boolean) -> Unit,
    onRefresh: suspend () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    proxySwitch: ProxySwitchUi? = null,
    onToggleProxy: (ToggleAction) -> Unit = {},
    onProxyFailureAction: (FailureAction) -> Unit = {},
) {
    val loaded = videos != null && continueWatching != null
    val sections = remember(videos, continueWatching) { buildSections(videos.orEmpty(), continueWatching.orEmpty()) }
    val featured = remember(sections) { sections.featured() }
    val listState = rememberLazyListState()
    val continueState = rememberLazyListState()
    val mainState = rememberLazyListState()
    val refreshFocus = remember { FocusRequester() }
    val heroFocus = remember { FocusRequester() }
    val targetFocus = remember { FocusRequester() }
    var target by remember { mutableStateOf<FocusTarget?>(null) }
    var updating by remember { mutableStateOf(false) }
    var failureMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        val t = initialFocus(sections, lastPlayedId)
        target = t
        when (t) {
            FocusTarget.Refresh -> refreshFocus.requestSafely()
            FocusTarget.Hero -> { withFrame(); heroFocus.requestSafely() }
            is FocusTarget.Continue -> {
                listState.scrollToItem(1)
                continueState.scrollToItem(sections.continueWatching.indexOfFirst { it.id == t.videoId }.coerceAtLeast(0))
                withFrame(); targetFocus.requestSafely()
            }
            is FocusTarget.Main -> {
                listState.scrollToItem(mainShelfItemIndex(sections))
                mainState.scrollToItem(sections.all.indexOfFirst { it.id == t.videoId }.coerceAtLeast(0))
                withFrame(); targetFocus.requestSafely()
            }
        }
    }

    val topBar: @Composable () -> Unit = {
        TopBar(
            updating = updating,
            offline = offline,
            refreshModifier = Modifier.focusRequester(refreshFocus),
            onRefresh = {
                if (!updating) scope.launch {
                    updating = true
                    try { onRefresh() } finally { updating = false }
                }
            },
            onOpenSettings = onOpenSettings,
            proxySwitch = proxySwitch,
            onToggleProxy = {
                val action = ProxySwitchLogic.onToggle(proxySwitch!!, hasSelection = proxySwitch.name != null)
                if (action == ToggleAction.OPEN_FAILURE_MENU) failureMenu = true else onToggleProxy(action)
            },
            failureMenu = failureMenu,
            onFailureAction = { failureMenu = false; onProxyFailureAction(it) },
            onDismissFailureMenu = { failureMenu = false },
        )
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        // No horizontal padding here: shelves pad themselves so cards scroll to the screen edge and a focused card's
        // lift is never clipped by the row's bounds.
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            if (loaded && featured != null) {
                Box(Modifier.fillParentMaxHeight(HERO_HEIGHT_FRACTION).fillMaxWidth()) {
                    Hero(featured, progressOf, thumbnail, onPlay, heroFocus)
                    topBar()
                }
            } else {
                topBar()
            }
        }
        if (!loaded) {
            item {
                Text(
                    stringResource(R.string.library_loading),
                    Modifier.padding(horizontal = SCREEN_EDGE_DP.dp, vertical = 24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = HeroForegroundMuted,
                )
            }
        } else if (sections.all.isEmpty()) {
            item {
                Column(
                    Modifier.padding(horizontal = SCREEN_EDGE_DP.dp, vertical = 24.dp).testTag("empty_state"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.library_empty_body), style = MaterialTheme.typography.bodyLarge, color = HeroForegroundMuted)
                }
            }
        } else {
            if (sections.continueWatching.isNotEmpty()) {
                item {
                    Shelf(stringResource(R.string.library_continue), continueState) {
                        items(sections.continueWatching, key = { "c${it.id}" }) { v ->
                            val isTarget = (target as? FocusTarget.Continue)?.videoId == v.id
                            VideoCard(
                                v, progressOf, thumbnail, onPlay, if (isTarget) targetFocus else null, "continue_${v.id}",
                                Modifier.width(CONTINUE_CARD_WIDTH),
                            )
                        }
                    }
                }
            }
            item {
                Shelf(stringResource(R.string.library_all), mainState) {
                    items(sections.all, key = { "v${it.id}" }) { v ->
                        val isTarget = (target as? FocusTarget.Main)?.videoId == v.id
                        VideoCard(
                            v, progressOf, thumbnail, onPlay, if (isTarget) targetFocus else null, "video_${v.id}",
                            Modifier.width(MAIN_CARD_WIDTH),
                        )
                    }
                }
            }
        }
    }
}

private suspend fun withFrame() = androidx.compose.runtime.withFrameNanos { }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopBar(
    updating: Boolean,
    offline: Boolean,
    refreshModifier: Modifier,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    proxySwitch: ProxySwitchUi?,
    onToggleProxy: () -> Unit,
    failureMenu: Boolean,
    onFailureAction: (FailureAction) -> Unit,
    onDismissFailureMenu: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = SCREEN_EDGE_DP.dp, end = SCREEN_EDGE_DP.dp, top = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.library_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
            // First focus stop of the top bar (contracts/ui.md).
            if (proxySwitch != null) ProxySwitch(proxySwitch, onToggleProxy, onFailureAction, failureMenu, onDismissFailureMenu)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (offline) HeroChip(stringResource(R.string.library_offline), tone = HeroTone.Warning)
            if (updating) HeroChip(stringResource(R.string.library_updating), modifier = Modifier.testTag("updating_indicator"))
            HeroButton(onClick = onRefresh, contentPadding = IconButtonPadding, modifier = refreshModifier.testTag("refresh_button")) {
                HeroIcon(TeleIcons.Refresh, stringResource(R.string.library_refresh))
            }
            HeroButton(onClick = onOpenSettings, contentPadding = IconButtonPadding, modifier = Modifier.testTag("settings_button")) {
                HeroIcon(TeleIcons.Settings, stringResource(R.string.library_settings))
            }
        }
    }
}

/** Full-bleed banner for the video to watch next: artwork fading to black, title, details, Play or Resume. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Hero(
    video: Video,
    progressOf: (Long) -> Flow<PlaybackProgress?>,
    thumbnail: suspend (Video) -> ImageBitmap?,
    onPlay: (Video, Boolean) -> Unit,
    playFocus: FocusRequester,
) {
    val progress by remember(video.id) { progressOf(video.id) }.collectAsState(initial = null)
    val thumb by produceState<ImageBitmap?>(null, video.id) { value = thumbnail(video) }
    val fraction = progressFraction(progress)
    val resuming = fraction > 0f
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Telegram thumbnails are small; scaled to the full banner they read as soft artwork behind the scrims.
        thumb?.let {
            Image(
                it, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize().alpha(0.85f),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color.Black, 0.5f to Color.Black.copy(alpha = 0.72f), 1f to Color.Transparent),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = SCREEN_EDGE_DP.dp, bottom = 40.dp).width(620.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(video.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatDuration(video.durationSec)} · ${formatSize(video.sizeBytes)} · ${formatDate(video.receivedAt)}",
                style = MaterialTheme.typography.bodyLarge,
                color = HeroForegroundMuted,
            )
            if (resuming) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.width(240.dp).height(4.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(50)).testTag("hero_progress")) {
                        Box(Modifier.fillMaxFraction(fraction).background(Color.White, RoundedCornerShape(50)))
                    }
                    Text(
                        formatRemaining((progress?.durationMs ?: 0L) - (progress?.positionMs ?: 0L)),
                        style = MaterialTheme.typography.labelLarge,
                        color = HeroForegroundMuted,
                    )
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroButton(
                    onClick = { if (video.isPlayable) onPlay(video, false) },
                    solid = true,
                    modifier = Modifier.focusRequester(playFocus).testTag("hero_play"),
                ) {
                    PlayGlyph(Modifier.size(18.dp))
                    Text(
                        stringResource(if (resuming) R.string.library_resume else R.string.library_play),
                        Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (resuming) {
                    HeroButton(onClick = { if (video.isPlayable) onPlay(video, true) }, modifier = Modifier.testTag("hero_start_over")) {
                        Text(stringResource(R.string.library_start_over), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

private fun Modifier.fillMaxFraction(fraction: Float): Modifier = this.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()

/** Triangle that follows the surrounding content colour, so it inverts with the focused button. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayGlyph(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.12f, 0f); lineTo(size.width, size.height / 2)
            lineTo(size.width * 0.12f, size.height); close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun Shelf(title: String, state: androidx.compose.foundation.lazy.LazyListState, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Text(
            title,
            Modifier.padding(horizontal = SCREEN_EDGE_DP.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
            // Padding inside the row (not around it) leaves room for the focus lift.
            contentPadding = PaddingValues(horizontal = SCREEN_EDGE_DP.dp, vertical = 20.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VideoCard(
    video: Video,
    progressOf: (Long) -> Flow<PlaybackProgress?>,
    thumbnail: suspend (Video) -> ImageBitmap?,
    onPlay: (Video, Boolean) -> Unit,
    focusRequester: FocusRequester?,
    tag: String,
    modifier: Modifier,
) {
    val progress by remember(video.id) { progressOf(video.id) }.collectAsState(initial = null)
    val thumb by produceState<ImageBitmap?>(null, video.id) { value = thumbnail(video) }
    val playable = video.isPlayable
    val fraction = progressFraction(progress)
    var focused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // tvOS card: artwork only, lifts and gains a white edge when focused; the title sits below it.
        Surface(
            onClick = { if (playable) onPlay(video, false) },
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .testTag(tag),
            shape = ClickableSurfaceDefaults.shape(CARD_SHAPE),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CARD_SHAPE),
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = HeroContent2,
                focusedContainerColor = HeroContent2,
                pressedContainerColor = HeroContent2,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                thumb?.let {
                    Image(
                        it, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(if (playable) 1f else 0.35f),
                    )
                }
                if (!playable) {
                    Text(
                        stringResource(
                            if (video.availability == Availability.REMOTE_GONE) R.string.library_unavailable_gone
                            else R.string.library_unavailable_format,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = HeroForeground,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp),
                    )
                }
                if (fraction > 0f) {
                    Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().height(36.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
                    )
                    Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                            .height(4.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
                            .testTag("progress_$tag"),
                    ) {
                        Box(Modifier.fillMaxFraction(fraction).background(Color.White, RoundedCornerShape(50)))
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                video.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (focused) HeroForeground else HeroForeground.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (fraction > 0f) formatRemaining((progress?.durationMs ?: 0L) - (progress?.positionMs ?: 0L))
                else "${formatDuration(video.durationSec)} · ${formatDate(video.receivedAt)}",
                style = MaterialTheme.typography.labelMedium,
                color = HeroForegroundMuted,
                maxLines = 1,
            )
        }
    }
}
