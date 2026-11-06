package com.smaznet.teleplay.ui.player

import android.view.KeyEvent as AKeyEvent
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import com.smaznet.teleplay.R
import com.smaznet.teleplay.ui.theme.HeroGlass
import com.smaznet.teleplay.ui.theme.HeroForegroundMuted
import com.smaznet.teleplay.domain.Video
import com.smaznet.teleplay.playback.PlayerController
import com.smaznet.teleplay.playback.PlayerError

private val EaseOutExpo = androidx.compose.animation.core.CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

const val TAG_PLAYER_ROOT = "player_root"
const val TAG_PLAYER_OVERLAY = "player_overlay"
const val TAG_PLAYER_POSITION = "player_position"
const val TAG_PLAYER_BUFFERING = "player_buffering"
const val TAG_PLAYER_ASPECT = "player_aspect"
const val TAG_PLAYER_RESTART = "player_restart"
const val TAG_PLAYER_BACK = "player_back"
const val TAG_PLAYER_REWIND = "player_rewind"
const val TAG_PLAYER_FORWARD = "player_forward"
const val TAG_PLAYER_PLAY_PAUSE = "player_play_pause"

/** The skip buttons jump this far. */
private const val SKIP_MS = 10_000L

/** How the video fills the screen: [Original] keeps its aspect ratio (letterboxed), [Fill] zooms to cover, [Stretch] distorts. */
enum class AspectMode {
    Original, Fill, Stretch;

    fun next(): AspectMode = entries[(ordinal + 1) % entries.size]
}

@Composable
fun PlayerScreen(videoId: Long, startFromBeginning: Boolean, deps: PlayerDeps, onBack: () -> Unit) {
    var controller by remember { mutableStateOf<PlayerController?>(null) }
    var missing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }

    LaunchedEffect(videoId, startFromBeginning) {
        val video: Video? = deps.video(videoId)
        if (video == null) { missing = true; return@LaunchedEffect }
        title = video.title
        val start = if (startFromBeginning) {
            deps.startOver(videoId); 0L
        } else deps.resumePositionMs(videoId) ?: 0L
        controller = deps.createController(video, start)
    }
    DisposableEffect(Unit) {
        onDispose { controller?.release(); controller = null }
    }
    BackHandler(onBack = onBack)

    val c = controller
    if (c != null) {
        PlayerContent(c, title, onBack)
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(
                stringResource(if (missing) R.string.player_not_found else R.string.player_loading),
                color = Color.White, style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun PlayerContent(controller: PlayerController, title: String, onBack: () -> Unit) {
    val position by controller.positionMs.collectAsState()
    val duration by controller.durationMs.collectAsState()
    val buffering by controller.isBuffering.collectAsState()
    val buffered by controller.bufferedMs.collectAsState()
    val playing by controller.isPlaying.collectAsState()
    val error by controller.error.collectAsState()
    val aspect by controller.videoAspect.collectAsState()
    var aspectMode by rememberSaveable { mutableStateOf(AspectMode.Original) }

    // Keep the TV awake while video is playing or loading; a paused video lets the screensaver start as usual.
    val view = androidx.compose.ui.platform.LocalView.current
    val awake = playing || buffering
    DisposableEffect(awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        // A bare TextureView stretches to its bounds, so the video surface is sized here from the real aspect ratio.
        val videoModifier = when {
            aspect <= 0f || aspectMode == AspectMode.Stretch -> Modifier.fillMaxSize()
            else -> {
                val containerAspect = maxWidth / maxHeight
                val wide = aspect > containerAspect
                val fit = aspectMode == AspectMode.Original
                // Original: the whole picture fits inside. Fill: the picture covers the screen and the overflow is clipped.
                if (wide == fit) Modifier.requiredSize(maxWidth, maxWidth / aspect) else Modifier.requiredSize(maxHeight * aspect, maxHeight)
            }
        }
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { controller.player.setVideoTextureView(it) } },
            onRelease = { runCatching { controller.player.clearVideoTextureView(it) } },
            modifier = videoModifier,
        )
        val err: PlayerError? = error
        if (err != null) {
            PlayerErrorPanel(err, onRetry = controller::retry, onBack = onBack)
        } else {
            PlayerControls(
                positionMs = position, durationMs = duration, bufferedMs = buffered, isBuffering = buffering, isPlaying = playing,
                onTogglePlayPause = controller::togglePlayPause,
                onPlay = controller::play,
                onPause = controller::pause,
                onSeekBy = controller::seekBy,
                title = title,
                aspectMode = aspectMode,
                onCycleAspect = { aspectMode = aspectMode.next() },
                onRestart = { controller.seekTo(0) },
                onBack = onBack,
            )
        }
    }
}

/** Stateless controls overlay + key handling; no media3-ui. */
@Composable
fun PlayerControls(
    positionMs: Long,
    durationMs: Long,
    isBuffering: Boolean,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    modifier: Modifier = Modifier,
    bufferedMs: Long = 0,
    title: String = "",
    aspectMode: AspectMode = AspectMode.Original,
    onCycleAspect: (() -> Unit)? = null,
    onRestart: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    val toolbarFocus = remember { FocusRequester() }
    val actionsFocus = remember { FocusRequester() }
    var topFocused by remember { mutableStateOf(false) }
    var leadFocused by remember { mutableStateOf(false) }
    var trailFocused by remember { mutableStateOf(false) }
    val actionsFocused = leadFocused || trailFocused
    val toolbarFocused = topFocused || actionsFocused
    var interaction by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(true) }
    var aspectToast by remember { mutableStateOf(false) }
    val initialAspect = remember { aspectMode }
    LaunchedEffect(aspectMode) {
        if (aspectMode == initialAspect && !aspectToast) return@LaunchedEffect
        aspectToast = true
        kotlinx.coroutines.delay(1500)
        aspectToast = false
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // The overlay stays up while a top button has focus; when it hides, focus returns to the key handler.
    LaunchedEffect(interaction, isPlaying, isBuffering, toolbarFocused) {
        visible = true
        if (isPlaying && !isBuffering && !toolbarFocused) {
            kotlinx.coroutines.delay(PlayerLogic.OVERLAY_HIDE_MS)
            visible = false
            runCatching { focus.requestFocus() }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .testTag(TAG_PLAYER_ROOT)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                val native = e.nativeKeyEvent
                if (toolbarFocused) {
                    // The top buttons own the keys, except Down which hands control back to playback.
                    if (e.type == KeyEventType.KeyDown) interaction++
                    // Back (top) and the action buttons (bottom) own the keys; Down, or Up from the bottom group, hands control back to playback.
                    return@onPreviewKeyEvent if (native.keyCode == AKeyEvent.KEYCODE_DPAD_DOWN || (actionsFocused && native.keyCode == AKeyEvent.KEYCODE_DPAD_UP)) {
                        if (e.type == KeyEventType.KeyDown) runCatching { focus.requestFocus() }
                        true
                    } else false
                }
                if (e.type != KeyEventType.KeyDown) {
                    // consume matching key-ups for keys we handle
                    return@onPreviewKeyEvent handled(native.keyCode)
                }
                interaction++
                when (native.keyCode) {
                    AKeyEvent.KEYCODE_DPAD_UP -> {
                        // First press reveals the overlay; once it is visible, Up moves to Back.
                        if (onBack != null && visible) runCatching { toolbarFocus.requestFocus() }
                        true
                    }
                    AKeyEvent.KEYCODE_DPAD_DOWN -> {
                        // Likewise Down moves to the transport buttons above the progress bar.
                        if (visible) runCatching { actionsFocus.requestFocus() }
                        true
                    }
                    AKeyEvent.KEYCODE_DPAD_CENTER, AKeyEvent.KEYCODE_ENTER, AKeyEvent.KEYCODE_NUMPAD_ENTER,
                    AKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (native.repeatCount == 0) onTogglePlayPause(); true }
                    AKeyEvent.KEYCODE_MEDIA_PLAY -> { onPlay(); true }
                    AKeyEvent.KEYCODE_MEDIA_PAUSE -> { onPause(); true }
                    AKeyEvent.KEYCODE_DPAD_RIGHT, AKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val s = PlayerLogic.seekStepMs(native.repeatCount); if (s > 0) onSeekBy(s); true
                    }
                    AKeyEvent.KEYCODE_DPAD_LEFT, AKeyEvent.KEYCODE_MEDIA_REWIND -> {
                        val s = PlayerLogic.seekStepMs(native.repeatCount); if (s > 0) onSeekBy(-s); true
                    }
                    AKeyEvent.KEYCODE_BACK -> false
                    else -> true // any other key just reveals the overlay
                }
            }
            .focusable(),
    ) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(120, easing = EaseOutExpo)),
            exit = fadeOut(tween(150, easing = EaseOutExpo)),
        ) {
            val aspectLabel = stringResource(
                when (aspectMode) {
                    AspectMode.Original -> R.string.player_aspect_original
                    AspectMode.Fill -> R.string.player_aspect_fill
                    AspectMode.Stretch -> R.string.player_aspect_stretch
                },
            )
            val stateLabel = stringResource(if (isPlaying) R.string.player_playing else R.string.player_paused)
            Box(Modifier.fillMaxSize().testTag(TAG_PLAYER_OVERLAY)) {
                // Scrims keep white text legible over any picture.
                Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(300.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
                if (onBack != null) {
                    PlayerIconButton(
                        onBack, stringResource(R.string.player_back_to_library), PlayerIcon.Back,
                        Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 28.dp)
                            .focusRequester(toolbarFocus).onFocusChanged { topFocused = it.hasFocus }.testTag(TAG_PLAYER_BACK),
                    )
                }
                // The aspect icon carries no text, so a change is confirmed briefly in words.
                if (aspectToast) {
                    Text(
                        aspectLabel, color = Color.White, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 36.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    )
                }
                Transport(
                    title, positionMs, durationMs, bufferedMs, isBuffering, Modifier.align(Alignment.BottomCenter),
                    onLeadFocus = { leadFocused = it }, onTrailFocus = { trailFocused = it },
                    leading = {
                        PlayerIconButton({ onSeekBy(-SKIP_MS) }, stringResource(R.string.player_rewind_10), PlayerIcon.Rewind10, Modifier.testTag(TAG_PLAYER_REWIND))
                        // Down from playback lands here, on the centre of the cluster.
                        PlayerIconButton(
                            onTogglePlayPause, stateLabel, if (isPlaying) PlayerIcon.Pause else PlayerIcon.Play,
                            Modifier.focusRequester(actionsFocus).testTag(TAG_PLAYER_PLAY_PAUSE),
                        )
                        PlayerIconButton({ onSeekBy(SKIP_MS) }, stringResource(R.string.player_forward_10), PlayerIcon.Forward10, Modifier.testTag(TAG_PLAYER_FORWARD))
                    },
                    trailing = {
                        if (onRestart != null) {
                            PlayerIconButton(onRestart, stringResource(R.string.player_back_to_start), PlayerIcon.Previous, Modifier.testTag(TAG_PLAYER_RESTART))
                        }
                        if (onCycleAspect != null) {
                            PlayerIconButton(onCycleAspect, aspectLabel, PlayerIcon.Aspect, Modifier.testTag(TAG_PLAYER_ASPECT))
                        }
                    },
                )
            }
        }
    }
}

private enum class PlayerIcon { Back, Previous, Aspect, Rewind10, Forward10, Play, Pause }

/** Round glass icon button; the glyph is drawn in the content colour, so it turns black on the white focused fill. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayerIconButton(onClick: () -> Unit, description: String, icon: PlayerIcon, modifier: Modifier = Modifier) {
    androidx.tv.material3.Button(
        onClick = onClick,
        modifier = modifier.size(60.dp).semantics { contentDescription = description },
        scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1.08f),
        shape = androidx.tv.material3.ButtonDefaults.shape(androidx.compose.foundation.shape.CircleShape),
        colors = androidx.tv.material3.ButtonDefaults.colors(
            containerColor = HeroGlass,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        val color = androidx.tv.material3.LocalContentColor.current
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(30.dp)) {
            val w = size.width
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
            )
            when (icon) {
                PlayerIcon.Back -> drawPath(
                    Path().apply { moveTo(w * 0.92f, w * 0.5f); lineTo(w * 0.1f, w * 0.5f); moveTo(w * 0.45f, w * 0.12f); lineTo(w * 0.08f, w * 0.5f); lineTo(w * 0.45f, w * 0.88f) },
                    color, style = stroke,
                )
                PlayerIcon.Previous -> {
                    drawRoundRect(color, Offset(w * 0.1f, w * 0.15f), Size(w * 0.14f, w * 0.7f), CornerRadius(w * 0.04f))
                    drawPath(Path().apply { moveTo(w * 0.92f, w * 0.15f); lineTo(w * 0.92f, w * 0.85f); lineTo(w * 0.34f, w * 0.5f); close() }, color)
                }
                PlayerIcon.Play -> drawPath(
                    Path().apply { moveTo(w * 0.22f, w * 0.1f); lineTo(w * 0.9f, w * 0.5f); lineTo(w * 0.22f, w * 0.9f); close() }, color,
                )
                PlayerIcon.Pause -> {
                    drawRoundRect(color, Offset(w * 0.17f, w * 0.1f), Size(w * 0.24f, w * 0.8f), CornerRadius(w * 0.05f))
                    drawRoundRect(color, Offset(w * 0.59f, w * 0.1f), Size(w * 0.24f, w * 0.8f), CornerRadius(w * 0.05f))
                }
                PlayerIcon.Rewind10, PlayerIcon.Forward10 -> {
                    // Counter-clockwise arrow around the number; the forward icon is the same drawing mirrored.
                    scale(if (icon == PlayerIcon.Forward10) -1f else 1f, 1f, pivot = center) {
                        val c = Offset(w / 2, w / 2 + w * 0.03f)
                        val r = w * 0.4f
                        drawArc(color, -70f, 300f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = stroke)
                        val rad = Math.toRadians(-70.0)
                        val p = Offset(c.x + r * Math.cos(rad).toFloat(), c.y + r * Math.sin(rad).toFloat())
                        val tan = Offset(Math.sin(rad).toFloat(), -Math.cos(rad).toFloat())
                        val n = Offset(-tan.y, tan.x)
                        val tip = p + tan * (w * 0.17f)
                        val base = p - tan * (w * 0.05f)
                        drawPath(Path().apply {
                            moveTo(tip.x, tip.y); lineTo(base.x + n.x * w * 0.14f, base.y + n.y * w * 0.14f)
                            lineTo(base.x - n.x * w * 0.14f, base.y - n.y * w * 0.14f); close()
                        }, color)
                    }
                }
                PlayerIcon.Aspect -> {
                    val l = w * 0.28f
                    val lo = w * 0.08f
                    val hi = w * 0.92f
                    drawPath(
                        Path().apply {
                            moveTo(lo, lo + l); lineTo(lo, lo); lineTo(lo + l, lo)
                            moveTo(hi - l, lo); lineTo(hi, lo); lineTo(hi, lo + l)
                            moveTo(hi, hi - l); lineTo(hi, hi); lineTo(hi - l, hi)
                            moveTo(lo + l, hi); lineTo(lo, hi); lineTo(lo, hi - l)
                        },
                        color, style = stroke,
                    )
                }
            }
        }
        if (icon == PlayerIcon.Rewind10 || icon == PlayerIcon.Forward10) {
            Text("10", color = color, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
        }
        }
    }
}

/** tvOS-style transport: state glyph and title over a thin scrubber with elapsed and remaining time at its ends. */
@Composable
private fun Transport(
    title: String, pos: Long, dur: Long, buffered: Long, loading: Boolean, modifier: Modifier,
    onLeadFocus: (Boolean) -> Unit, onTrailFocus: (Boolean) -> Unit,
    leading: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val remaining = (dur - pos).coerceAtLeast(0)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.onFocusChanged { onLeadFocus(it.hasFocus) }, horizontalArrangement = Arrangement.spacedBy(12.dp), content = leading)
            Text(
                title, color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 28.dp),
            )
            Row(Modifier.onFocusChanged { onTrailFocus(it.hasFocus) }, horizontalArrangement = Arrangement.spacedBy(12.dp), content = trailing)
        }
        ProgressBar(
            PlayerLogic.progressFraction(pos, dur), PlayerLogic.progressFraction(buffered, dur), loading,
            Modifier.fillMaxWidth(), trackDp = 6,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            Text(PlayerLogic.formatTime(pos), color = Color.White, style = TimeStyle.copy(fontSize = 20.sp), modifier = Modifier.testTag(TAG_PLAYER_POSITION))
            Box(Modifier.weight(1f))
            Text("-" + PlayerLogic.formatTime(remaining), color = Color.White.copy(alpha = 0.7f), style = TimeStyle.copy(fontSize = 20.sp))
        }
    }
}

private val TimeStyle
    @Composable get() = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")

/**
 * Scrubber: dim track, brighter loaded range, white played range. While [loading] a bright segment sweeps along the
 * track instead of a separate spinner, so the bar itself says the video is waiting for data.
 */
@Composable
private fun ProgressBar(fraction: Float, buffered: Float, loading: Boolean, modifier: Modifier = Modifier, trackDp: Int = 6) {
    // The infinite transition only exists while loading, and its value is read in the draw phase: an always-running
    // animation read during composition re-composed the whole controls every frame, which stalled video on weak TVs.
    val sweep = if (loading) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "loading").animateFloat(
            0f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
            label = "sweep",
        )
    } else null
    val loadingLabel = stringResource(R.string.player_buffering)
    Canvas(
        modifier.height((trackDp + 10).dp)
            .then(if (loading) Modifier.testTag(TAG_PLAYER_BUFFERING).semantics { contentDescription = loadingLabel } else Modifier),
    ) {
        val track = trackDp.dp.toPx()
        val thumb = (trackDp + 2).dp.toPx()
        val y = (size.height - track) / 2
        val corner = CornerRadius(track / 2)
        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, track), corner)
        val loaded = (size.width * maxOf(buffered, fraction)).coerceIn(0f, size.width)
        drawRoundRect(Color.White.copy(alpha = 0.5f), Offset(0f, y), Size(loaded, track), corner)
        val x = (size.width * fraction).coerceIn(0f, size.width)
        drawRoundRect(Color.White, Offset(0f, y), Size(x, track), corner)
        if (loading) {
            val seg = size.width * 0.25f
            val start = -seg + (size.width + seg) * (sweep?.value ?: 0f)
            clipRect(0f, y, size.width, y + track) {
                drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(start, y), Size(seg, track), corner)
            }
        }
        drawCircle(Color.White, thumb, Offset(x.coerceIn(thumb, size.width - thumb), size.height / 2))
    }
}

private fun handled(keyCode: Int): Boolean = when (keyCode) {
    AKeyEvent.KEYCODE_DPAD_CENTER, AKeyEvent.KEYCODE_ENTER, AKeyEvent.KEYCODE_NUMPAD_ENTER,
    AKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AKeyEvent.KEYCODE_MEDIA_PLAY, AKeyEvent.KEYCODE_MEDIA_PAUSE,
    AKeyEvent.KEYCODE_DPAD_RIGHT, AKeyEvent.KEYCODE_DPAD_LEFT,
    AKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, AKeyEvent.KEYCODE_MEDIA_REWIND -> true
    else -> false
}
