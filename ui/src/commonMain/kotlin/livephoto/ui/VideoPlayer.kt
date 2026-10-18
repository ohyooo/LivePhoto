package livephoto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset

private val PlayerBlue = Color(0xff3b82f6)

/** Shared pointer, touch, keyboard and accessibility control. Vertical grows downwards. */
@Composable
public fun MediaSeekBar(value: Double, duration: Double, enabled: Boolean, label: String,
    modifier: Modifier = Modifier, vertical: Boolean = false, onSeek: (Double) -> Unit) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val thumbColor = MaterialTheme.colorScheme.surface
    val current by rememberUpdatedState(value)
    val seek by rememberUpdatedState(onSeek)
    val focus = remember { FocusRequester() }
    val available = enabled && duration.isFinite() && duration > 0
    val fraction = if (duration > 0) (value / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    fun position(offset: Offset, width: Int, height: Int): Double {
        val length = (if (vertical) height else width).toFloat()
        val coordinate = if (vertical) offset.y else offset.x
        return (coordinate / length.coerceAtLeast(1f)).coerceIn(0f, 1f) * duration
    }
    Canvas(modifier.focusRequester(focus).semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0.0, duration.coerceAtLeast(0.0)).toFloat(), 0f..duration.coerceAtLeast(0.001).toFloat())
        if (!available) disabled()
        setProgress { next -> if (available) seek(next.toDouble().coerceIn(0.0, duration)); available }
    }.onKeyEvent { event ->
        if (!available || event.type != KeyEventType.KeyDown) false else {
            val next = when (event.key) {
                Key.DirectionRight, Key.DirectionDown -> current + 1
                Key.DirectionLeft, Key.DirectionUp -> current - 1
                Key.MoveHome -> 0.0
                Key.MoveEnd -> duration
                else -> return@onKeyEvent false
            }
            seek(next.coerceIn(0.0, duration)); true
        }
    }.focusable(available).pointerInput(available, duration, vertical) {
        if (available) detectTapGestures { focus.requestFocus(); seek(position(it, size.width, size.height)) }
    }.pointerInput(available, duration, vertical) {
        if (available) detectDragGestures(onDragStart = { focus.requestFocus(); seek(position(it, size.width, size.height)) },
            onDrag = { change, _ -> change.consume(); seek(position(change.position, size.width, size.height)) })
    }) {
        val a = if (vertical) Offset(size.width / 2, 7.dp.toPx()) else Offset(7.dp.toPx(), size.height / 2)
        val b = if (vertical) Offset(a.x, size.height - 7.dp.toPx()) else Offset(size.width - 7.dp.toPx(), a.y)
        val thumb = a + (b - a) * fraction
        drawLine(track, a, b, 4.dp.toPx(), StrokeCap.Round)
        drawLine(if (available) PlayerBlue else track, a, thumb, 4.dp.toPx(), StrokeCap.Round)
        drawCircle(thumbColor, 7.dp.toPx(), thumb)
        drawCircle(track, 7.dp.toPx(), thumb, style = Stroke(1.dp.toPx()))
    }
}

private fun playerClock(time: Double): String {
    val seconds = time.coerceAtLeast(0.0).toLong()
    return (if (seconds >= 3600) "${seconds / 3600}:" else "") +
        "${seconds / 60 % 60}".padStart(2, '0') + ":" + "${seconds % 60}".padStart(2, '0')
}

@Composable
private fun PlayerButton(label: String, symbol: String, enabled: Boolean = true, onClick: () -> Unit) {
    val PlayerInk = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp).semantics { contentDescription = label }) {
        Canvas(Modifier.size(20.dp)) {
            val k = size.width / 24f
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(PlayerInk, Offset(x*k,y*k), Offset(x2*k,y2*k), 1.8f*k, StrokeCap.Round)
            when (symbol) {
                "play" -> drawPath(Path().apply { moveTo(7*k,4*k); lineTo(20*k,12*k); lineTo(7*k,20*k); close() }, PlayerInk, style = Stroke(1.8f*k))
                "pause" -> { line(8f,5f,8f,19f); line(16f,5f,16f,19f) }
                "volume", "muted" -> {
                    drawPath(Path().apply { moveTo(11*k,4*k); lineTo(5*k,9*k); lineTo(2*k,9*k); lineTo(2*k,15*k); lineTo(5*k,15*k); lineTo(11*k,20*k); close() }, PlayerInk, style = Stroke(1.8f*k))
                    if (symbol == "muted") { line(16f,9f,22f,15f); line(22f,9f,16f,15f) }
                    else { line(16f,8f,19f,12f); line(19f,12f,16f,16f) }
                }
                else -> { line(3f,8f,3f,3f); line(3f,3f,8f,3f); line(16f,3f,21f,3f); line(21f,3f,21f,8f); line(21f,16f,21f,21f); line(21f,21f,16f,21f); line(8f,21f,3f,21f); line(3f,21f,3f,16f) }
            }
        }
    }
}

/** Keep the media node in one composition/owner when changing presentation. */
@Composable
public fun VideoPlayerHost(editor: VideoEditor?, enabled: Boolean, onScroll: (Float) -> Unit,
    content: @Composable (@Composable () -> Unit) -> Unit) {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentScroll by rememberUpdatedState(onScroll)
    val fullscreen = editor?.playback?.value?.fullscreen == true
    val window = LocalWindowInfo.current.containerSize
    val player = remember(editor) { movableContentOf {
        if (editor != null) PlayerCard(editor, currentEnabled, currentScroll, editor.playback.value.fullscreen)
    } }
    Box(Modifier.fillMaxSize()) {
        // Retain the editor/tool state while hiding the ordinary page, including
        // native media views which cannot be hidden by painting a Canvas over them.
        Box(Modifier.fillMaxSize().offset { if (fullscreen) IntOffset(window.width, 0) else IntOffset.Zero }) {
            content { if (!fullscreen) player() }
        }
        if (fullscreen) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.background).padding(12.dp), contentAlignment = Alignment.Center) {
            player()
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PlayerCard(editor: VideoEditor, enabled: Boolean, onScroll: (Float) -> Unit, fullscreen: Boolean) {
    val PlayerInk = MaterialTheme.colorScheme.onSurface
    val palette = MaterialTheme.colorScheme
    val p by editor.playback
    var speedMenu by remember { mutableStateOf(false) }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val aspect = if (p.width > 0 && p.height > 0) (p.width / p.height).toFloat() else 16f / 9f
        val portrait = aspect < 1f
        val room = (maxWidth - 24.dp - if (portrait) 40.dp else 0.dp).coerceAtLeast(1.dp)
        val heightLimit = if (fullscreen) (maxHeight - 92.dp - if (speedMenu) 96.dp else 0.dp).coerceAtLeast(1.dp)
            else minOf(if (portrait) 1200.dp else 1080.dp, (windowHeight - 180.dp).coerceAtLeast(1.dp))
        val pictureWidth = minOf(room, if (portrait) 720.dp else 1920.dp, heightLimit * aspect)
        val pictureHeight = pictureWidth / aspect
        val cardWidth = pictureWidth + 24.dp + if (portrait) 40.dp else 0.dp
        val compact = cardWidth < 480.dp
        // Keep the platform video outside offscreen/clipping layers. Draw the
        // lightweight card directly so scrolling cannot leave a second opaque
        // rectangle where the interop surface used to be.
        Box(Modifier.width(cardWidth).testTag("video-player").drawBehind {
            for (spread in 1..3) {
                val pad = spread.dp.toPx()
                drawRoundRect(Color(0x030f172a), Offset(-pad, pad), Size(size.width + pad * 2, size.height + pad), CornerRadius(14.dp.toPx() + pad))
            }
        }.background(palette.surface, RoundedCornerShape(14.dp)).border(1.dp, palette.outlineVariant, RoundedCornerShape(14.dp))) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    editor.Player(Modifier.size(pictureWidth, pictureHeight).testTag("video-picture"), onScroll)
                    if (portrait) MediaSeekBar(p.current, p.duration, enabled && p.ready, "播放进度", Modifier.width(32.dp).height(pictureHeight).testTag("playback-progress"), vertical = true, onSeek = editor::seek)
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("player-controls"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (portrait) Arrangement.SpaceBetween else Arrangement.spacedBy(2.dp)) {
                    PlayerButton(if (p.paused) "播放" else "暂停", if (p.paused) "play" else "pause", enabled && p.ready, editor::togglePlayback)
                    if (!portrait) {
                        Text("${playerClock(p.current)} / ${playerClock(p.duration)}", fontSize = if (compact) 10.sp else 12.sp, fontFamily = FontFamily.Monospace)
                        MediaSeekBar(p.current, p.duration, enabled && p.ready, "播放进度", Modifier.weight(1f).height(32.dp).testTag("playback-progress"), onSeek = editor::seek)
                    }
                    PlayerButton(if (p.muted || p.volume == 0.0) "取消静音" else "静音", if (p.muted || p.volume == 0.0) "muted" else "volume", enabled) { editor.setMuted(!p.muted && p.volume > 0.0) }
                    if (!compact) MediaSeekBar(if (p.muted) 0.0 else p.volume, 1.0, enabled, "音量", Modifier.width(52.dp).height(32.dp), onSeek = editor::setVolume)
                    TextButton(onClick = { speedMenu = !speedMenu }, enabled = enabled,
                        modifier = Modifier.width(48.dp).semantics { contentDescription = "播放倍速" }, contentPadding = PaddingValues(0.dp)) { Text("${p.rate}x", fontSize = 12.sp, color = PlayerInk) }
                    PlayerButton(if (p.fullscreen) "退出全屏" else "全屏", "fullscreen", enabled && editor.canFullscreen) { editor.setFullscreen(!p.fullscreen) }
                }
                }
                // Expanded beneath the controls, never over the picture.
                if (speedMenu) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { rate ->
                        TextButton(onClick = { editor.setRate(rate); speedMenu = false }, contentPadding = PaddingValues(6.dp)) {
                            Text("${rate}x", color = if (p.rate == rate) PlayerBlue else PlayerInk)
                        }
                    }
                }
            }
        }
    }
}
