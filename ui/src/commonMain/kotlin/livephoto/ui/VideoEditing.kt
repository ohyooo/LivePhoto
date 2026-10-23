package livephoto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import livephoto.core.Time
import kotlin.math.roundToLong

public data class VideoPlayback(
    val duration: Double = 0.0, val current: Double = 0.0, val seeking: Boolean = true,
    val ready: Boolean = false, val error: String? = null,
    val width: Double = 0.0, val height: Double = 0.0,
    val paused: Boolean = true, val volume: Double = 1.0, val muted: Boolean = false,
    val rate: Double = 1.0, val fullscreen: Boolean = false,
)

public interface VideoEditor {
    public val playback: State<VideoPlayback>
    public fun seek(seconds: Double)
    public val canFullscreen: Boolean get() = false
    public val thumbnail: String? get() = null
    public fun togglePlayback() {}
    public fun setVolume(volume: Double) {}
    public fun setMuted(muted: Boolean) {}
    public fun setRate(rate: Double) {}
    public fun setFullscreen(fullscreen: Boolean) {}
    public suspend fun previewFrame(): String
    public suspend fun previewTimeline(): List<String> = emptyList()
    public suspend fun process(action: CoreMediaAction, start: Time, end: Time): CoreMediaEdit
    public fun cancel()
    @Composable public fun Player(modifier: Modifier, onScroll: (Float) -> Unit)
}

/** Drafts survive folding, navigation, theme and column changes. */
public class VideoEditingState {
    public var start by mutableDoubleStateOf(0.0)
    public var end by mutableDoubleStateOf(0.0)
    public var startText by mutableStateOf("0")
    public var endText by mutableStateOf("0")
    public var pendingTrim by mutableStateOf(false)
    public var trimOpen by mutableStateOf(false)
    public var toolsOpen by mutableStateOf(false)
    public var coverOpen by mutableStateOf(false)
    internal var coverFocusRequest by mutableIntStateOf(0)
    public var coverPosition by mutableDoubleStateOf(0.0)
    public var coverPreview by mutableStateOf<String?>(null)
    public var status by mutableStateOf("")
    public var lastAction by mutableStateOf<CoreMediaAction?>(null)
    public var working by mutableStateOf(false)
    public var capturing by mutableStateOf(false)
    public var cancelling by mutableStateOf(false)
    public fun resetRange(duration: Double) {
        start = 0.0; end = duration; startText = "0"; endText = decimalSeconds(duration); pendingTrim = false
    }
    public fun processedVideo() { resetRange(0.0); coverPreview = null; coverPosition = 0.0 }
}

private fun decimalSeconds(value: Double): String {
    val millis = (value.coerceAtLeast(0.0) * 1000).roundToLong()
    return "${millis / 1000}.${(millis % 1000).toString().padStart(3, '0')}"
}
public fun mediaSeconds(value: Double): String = decimalSeconds(value) + " 秒"
public fun mediaClock(value: Double): String {
    val millis = (value.coerceAtLeast(0.0) * 1000).roundToLong()
    return "${millis / 60_000}".padStart(2, '0') + ":" + "${millis / 1000 % 60}".padStart(2, '0') +
        "." + "${millis % 1000}".padStart(3, '0')
}

@Composable
public fun VideoEditingPanel(editor: VideoEditor, parentBusy: Boolean, onBusy: (Boolean) -> Unit,
    onImage: (String) -> Unit, onVideo: (EditedVideo) -> Unit, onScroll: (Float) -> Unit,
    player: @Composable () -> Unit, preview: @Composable (String, Modifier) -> Unit,
    state: VideoEditingState, confirmedTime: Time? = null, coverWarning: String? = null, hasImage: Boolean,
    platform: LivePhotoUiPlatform) {
    val playback by editor.playback
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val coverFocus = remember { BringIntoViewRequester() }
    LaunchedEffect(state.coverFocusRequest) {
        if (state.coverFocusRequest > 0) { withFrameNanos { }; coverFocus.bringIntoView() }
    }
    var thumbnails by remember(editor) { mutableStateOf<List<String>>(emptyList()) }
    var thumbnailError by remember(editor) { mutableStateOf<String?>(null) }
    val enabled = !parentBusy && !state.working && playback.ready
    val canPreview by rememberUpdatedState(enabled)
    LaunchedEffect(editor, playback.duration) {
        if (state.end == 0.0 && playback.duration > 0 && !state.pendingTrim) state.resetRange(playback.duration)
    }
    LaunchedEffect(editor, playback.ready) {
        if (playback.ready && thumbnails.isEmpty()) try { thumbnails = editor.previewTimeline() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { thumbnailError = "缩略图暂不可用，仍可使用时间与范围控件。" }
    }
    fun time(value: Double): Time {
        require(value.isFinite() && value >= 0 && value <= 60) { "媒体处理仅限 60 秒内视频。" }
        return Time((value * 1_000_000).roundToLong(), 1_000_000u)
    }
    fun run(action: CoreMediaAction) {
        if (!enabled || state.capturing) return
        focus.clearFocus()
        state.working = true; state.cancelling = false; state.lastAction = action; onBusy(true)
        val label = when (action) {
            CoreMediaAction.Probe -> "检查"; CoreMediaAction.Remux -> "封装"; CoreMediaAction.Trim -> "裁剪"
            CoreMediaAction.ExtractFrame -> "抽帧"; CoreMediaAction.Transcode -> "转码"
        }
        scope.launch {
            state.status = "正在$label；本地处理，不上传文件…"
            try {
                require(action != CoreMediaAction.Transcode || !state.pendingTrim) { "请先应用或放弃裁剪选段，再转码。" }
                val from = when (action) {
                    CoreMediaAction.Trim -> time(state.start)
                    CoreMediaAction.ExtractFrame -> time(if (state.coverOpen) state.coverPosition else playback.current)
                    else -> Time.Zero
                }
                val result = editor.process(action, from, if (action == CoreMediaAction.Trim) time(state.end) else Time.Zero)
                result.imageReference?.let(onImage)
                result.video?.let { state.processedVideo(); onVideo(it) }
                state.status = result.summary
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { state.status = "${label}失败：${error.message?.take(2000) ?: "未知错误"}。输入未替换，不自动转码或切换实现。" }
            finally { state.working = false; state.cancelling = false; onBusy(false) }
        }
    }
    LaunchedEffect(editor, state.coverOpen) {
        if (state.coverOpen) try {
            snapshotFlow { state.coverPosition }.conflate().collect { position ->
                if (canPreview) {
                    state.capturing = true; onBusy(true)
                    try {
                        state.coverPreview = withTimeout(5000) {
                            editor.seek(position)
                            snapshotFlow { editor.playback.value }.first { it.ready && !it.seeking }
                            editor.previewFrame()
                        }
                    } catch (_: TimeoutCancellationException) { state.status = "预览加载超时，请重试。" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { state.status = "预览失败：${error.message?.take(2000) ?: "未知错误"}" }
                    finally { state.capturing = false; onBusy(false) }
                    delay(80)
                }
            }
        } finally { state.capturing = false; onBusy(false) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard("视频预览") {
            player()
            if (!playback.ready && playback.error == null) Text("正在读取视频画面…")
            playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("可播放不代表可以安全生成或编辑。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard("视频编辑") {
            Disclosure("裁剪范围", when {
                state.pendingTrim -> "待应用 · ${mediaSeconds((state.end - state.start).coerceAtLeast(0.0))}"
                state.lastAction == CoreMediaAction.Trim -> "已裁剪 · ${mediaSeconds(playback.duration)}"
                else -> "保留完整视频"
            }, state.trimOpen, { state.trimOpen = !state.trimOpen }) {
                if (thumbnails.isNotEmpty()) Row(Modifier.fillMaxWidth().height(48.dp)) {
                    thumbnails.forEachIndexed { index, reference ->
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            preview(reference, Modifier.fillMaxSize())
                            val position = playback.duration * (index + .5) / thumbnails.size
                            if (position < state.start || position > state.end) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f)))
                        }
                    }
                }
                thumbnailError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                RangeSlider(value = state.start.toFloat()..state.end.toFloat(), enabled = enabled && playback.duration > 0,
                    valueRange = 0f..playback.duration.coerceAtLeast(.001).toFloat(), onValueChange = { range ->
                        val moved = if (range.start.toDouble() != state.start) range.start.toDouble() else range.endInclusive.toDouble()
                        state.start = range.start.toDouble(); state.end = range.endInclusive.toDouble()
                        state.startText = decimalSeconds(state.start); state.endText = decimalSeconds(state.end)
                        state.pendingTrim = state.start > .001 || state.end < playback.duration - .001
                        editor.seek(moved)
                    }, modifier = Modifier.fillMaxWidth().testTag("trim-progress").semantics { contentDescription = "裁剪范围" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (start in listOf(true, false)) OutlinedTextField(
                        value = if (start) state.startText else state.endText,
                        onValueChange = { value ->
                            if (start) state.startText = value else state.endText = value
                            state.pendingTrim = true
                            val parsed = parseKeySeconds(value)?.let { it.value.toDouble() / it.timescale.toDouble() }
                            if (parsed != null && parsed <= playback.duration && (if (start) parsed < state.end else parsed > state.start)) {
                                if (start) state.start = parsed else state.end = parsed
                                editor.seek(parsed)
                            }
                        }, label = { Text(if (start) "开始时间（秒）" else "结束时间（秒）") }, enabled = enabled,
                        singleLine = true, shape = ControlShape, modifier = Modifier.weight(1f).testTag(if (start) "trim-start" else "trim-end"))
                }
                Text("保留片段：${mediaSeconds(state.start)} ～ ${mediaSeconds(state.end)}")
                Text("选中时长：${mediaSeconds((state.end - state.start).coerceAtLeast(0.0))}；音视频 copy，以实际边界为准。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val valid = parseKeySeconds(state.startText) != null && parseKeySeconds(state.endText) != null &&
                        state.start >= 0 && state.end > state.start && state.end <= playback.duration &&
                        kotlin.math.abs(state.start - (state.startText.toDoubleOrNull() ?: -1.0)) < .001 &&
                        kotlin.math.abs(state.end - (state.endText.toDoubleOrNull() ?: -1.0)) < .001
                    Button(onClick = { run(CoreMediaAction.Trim) }, enabled = enabled && !state.capturing && valid && state.pendingTrim,
                        shape = ControlShape) { Text("应用裁剪") }
                    TextButton(onClick = { state.resetRange(playback.duration) }, enabled = enabled) { Text("重置选段") }
                }
                Text("重置只放弃尚未应用的选择，不撤销已处理视频。", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Disclosure("更多视频处理", if (state.working) "正在处理" else "可选 · 音频不编码", state.toolsOpen, { state.toolsOpen = !state.toolsOpen }) {
                OutlinedButton(onClick = { run(CoreMediaAction.Probe) }, enabled = enabled && !state.capturing, shape = ControlShape) { Text("检查视频") }
                OutlinedButton(onClick = { run(CoreMediaAction.Remux) }, enabled = enabled && !state.capturing, shape = ControlShape) { Text("重新封装") }
                OutlinedButton(onClick = { run(CoreMediaAction.Transcode) }, enabled = enabled && !state.capturing && !state.pendingTrim,
                    shape = ControlShape, modifier = Modifier.testTag("player-transcode")) { Text("转为 H.264") }
                Text("实验性：仅已验证的 8-bit SDR 等配置；视频显式编码，音频 copy。先应用或放弃选段，再转码。", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            val confirmed = confirmedTime?.let { mediaClock(it.value.toDouble() / it.timescale.toDouble()) }
            Column(Modifier.bringIntoViewRequester(coverFocus)) {
            Disclosure("封面位置", coverWarning ?: confirmed?.let { "已确认 · $it" } ?: if (hasImage) "使用所选图片" else "尚未确认封面", state.coverOpen,
                { state.coverOpen = !state.coverOpen; if (state.coverOpen) state.coverPosition = playback.current }) {
                MediaSeekBar(state.coverPosition, playback.duration, enabled, "封面位置",
                    Modifier.fillMaxWidth().height(44.dp).testTag("cover-progress"), onSeek = { state.coverPosition = it })
                Text("正在预览：${mediaClock(state.coverPosition)}")
                Text("当前封面：${confirmed ?: if (hasImage) "所选图片" else "未选择"}")
                state.coverPreview?.let { preview(it, Modifier.fillMaxWidth().height(160.dp)) }
                Text("仅预览，尚未替换封面。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { run(CoreMediaAction.ExtractFrame) }, enabled = enabled && !state.capturing, shape = ControlShape) { Text("使用当前画面作为封面") }
            }
            }
            coverWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.pendingTrim) Text("选段尚未应用；请应用裁剪或重置选段后生成。", color = MaterialTheme.colorScheme.error)
            if (state.working) OutlinedButton(onClick = { state.cancelling = true; editor.cancel() }, enabled = !state.cancelling, shape = ControlShape) {
                Text(if (state.cancelling) "正在取消…" else "取消媒体处理")
            }
            if (state.status.isNotEmpty()) ReportPanel(state.status, state.status.contains("失败"), "player-edit-status", "视频处理", platform)
        }
    }
}
