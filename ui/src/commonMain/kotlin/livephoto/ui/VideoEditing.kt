package livephoto.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
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
public data class EditedVideo(val reference: String, val summary: String)

/** One application media entry point. Preview is read-only and never selects an input. */
public interface VideoEditor {
    public val playback: State<VideoPlayback>
    public fun seek(seconds: Double)
    public val canFullscreen: Boolean get() = false
    public fun togglePlayback() {}
    public fun setVolume(volume: Double) {}
    public fun setMuted(muted: Boolean) {}
    public fun setRate(rate: Double) {}
    public fun setFullscreen(fullscreen: Boolean) {}
    public suspend fun previewFrame(): String
    public suspend fun process(action: CoreMediaAction, start: Time, end: Time): CoreMediaEdit
    public fun cancel()
    @Composable public fun Player(modifier: Modifier, onScroll: (Float) -> Unit)
}

public fun mediaSeconds(value: Double): String {
    val millis = (value.coerceAtLeast(0.0) * 1000).toLong()
    return "${millis / 1000}.${(millis % 1000).toString().padStart(3, '0')} 秒"
}

@Composable
public fun VideoEditingPanel(editor: VideoEditor, parentBusy: Boolean, onBusy: (Boolean) -> Unit,
    onImage: (String) -> Unit, onVideo: (EditedVideo) -> Unit, onScroll: (Float) -> Unit,
    player: @Composable () -> Unit, preview: @Composable (String, Modifier) -> Unit) {
    val playback by editor.playback
    var start by remember(editor) { mutableDoubleStateOf(0.0) }
    var end by remember(editor) { mutableDoubleStateOf(0.0) }
    var trimOpen by remember(editor) { mutableStateOf(false) }
    var coverOpen by remember(editor) { mutableStateOf(false) }
    var coverPosition by remember(editor) { mutableDoubleStateOf(0.0) }
    var coverPreview by remember(editor) { mutableStateOf<String?>(null) }
    var working by remember(editor) { mutableStateOf(false) }
    var capturing by remember(editor) { mutableStateOf(false) }
    var status by remember(editor) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    LaunchedEffect(editor, playback.duration) { if (end == 0.0 && playback.duration > 0) end = playback.duration }
    val enabled = !parentBusy && !working && playback.ready
    val canPreview by rememberUpdatedState(enabled)
    fun run(action: CoreMediaAction) {
        if (!enabled || capturing) return
        working = true; onBusy(true)
        val label = when (action) {
            CoreMediaAction.Probe -> "检查"
            CoreMediaAction.Remux -> "封装"
            CoreMediaAction.Trim -> "裁剪"
            CoreMediaAction.ExtractFrame -> "抽帧"
            CoreMediaAction.Transcode -> "转码"
        }
        scope.launch {
            status = "正在处理视频；不会上传文件…"
            try {
                require(action != CoreMediaAction.Transcode || !trimOpen) { "请先应用裁剪，再转码；不会忽略选段。" }
                fun time(value: Double): Time {
                    require(value.isFinite() && value >= 0 && value <= 60) { "媒体处理仅限 60 秒内视频。" }
                    return Time((value * 1_000_000).roundToLong(), 1_000_000u)
                }
                val from = when (action) {
                    CoreMediaAction.Trim -> time(start)
                    CoreMediaAction.ExtractFrame -> time(if (coverOpen) coverPosition else playback.current)
                    else -> Time.Zero
                }
                val until = if (action == CoreMediaAction.Trim) time(end) else Time.Zero
                val result = editor.process(action, from, until)
                result.imageReference?.let(onImage)
                result.video?.let(onVideo)
                status = result.summary
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status = "${label}失败：${error.message?.take(2000) ?: "未知错误"}。输入未替换，不自动转码或切换实现。" }
            finally { working = false; onBusy(false) }
        }
    }
    LaunchedEffect(editor, coverOpen) {
        if (coverOpen) try {
            snapshotFlow { coverPosition }.conflate().collect { position ->
                if (canPreview) {
                    capturing = true; onBusy(true)
                    try {
                        coverPreview = withTimeout(5000) {
                            editor.seek(position)
                            snapshotFlow { editor.playback.value }.first { it.ready && !it.seeking }
                            editor.previewFrame()
                        }
                    } catch (_: TimeoutCancellationException) { status = "预览加载超时，请重试。" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { status = "预览失败：${error.message?.take(2000) ?: "未知错误"}" }
                    finally { capturing = false; onBusy(false) }
                    delay(80)
                }
            }
        } finally { capturing = false; onBusy(false) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        player()
        playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(trimOpen, { focus.clearFocus(); trimOpen = it }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "裁剪视频" })
            Text("裁剪视频")
            Spacer(Modifier.width(16.dp))
            Checkbox(coverOpen, { focus.clearFocus(); coverOpen = it; if (it) coverPosition = playback.current }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "截取封面" })
            Text("截取封面")
        }
        if (coverOpen) {
            Text("封面位置：${mediaSeconds(coverPosition)}")
            MediaSeekBar(coverPosition, playback.duration, enabled, "封面位置", Modifier.fillMaxWidth().height(40.dp).testTag("cover-progress"), onSeek = { coverPosition = it })
            coverPreview?.let { preview(it, Modifier.fillMaxWidth().height(160.dp)) }
            Text("拖动只更新预览，不替换已选图片；点击下方按钮后抽取实际帧作为封面。")
        }
        OutlinedButton(onClick = { run(CoreMediaAction.ExtractFrame) }, enabled = enabled && !capturing, modifier = Modifier.fillMaxWidth()) {
            Text("使用当前画面为封面")
        }
        if (trimOpen) {
            Text("保留片段：${mediaSeconds(start)} ～ ${mediaSeconds(end)}")
            RangeSlider(value = start.toFloat()..end.toFloat(), enabled = enabled && playback.duration > 0,
                valueRange = 0f..playback.duration.coerceAtLeast(0.001).toFloat(),
                onValueChange = { range ->
                    val moved = if (range.start.toDouble() != start) range.start.toDouble() else range.endInclusive.toDouble()
                    start = range.start.toDouble(); end = range.endInclusive.toDouble(); editor.seek(moved)
                }, modifier = Modifier.fillMaxWidth().testTag("trim-progress").semantics { contentDescription = "裁剪范围" })
            Text("拖动两端手柄，保留中间高亮片段。copy 以实际边界为准，音频不编码。")
            Button(onClick = { run(CoreMediaAction.Trim) }, enabled = enabled && !capturing && start < end, modifier = Modifier.fillMaxWidth()) { Text("应用裁剪（音视频 copy）") }
            Text("转码处理当前整段视频；请先应用裁剪，再转码，不会忽略选段。")
        }
        OutlinedButton(onClick = { run(CoreMediaAction.Transcode) }, enabled = enabled && !capturing && !trimOpen,
            modifier = Modifier.fillMaxWidth().testTag("player-transcode")) { Text("显式转为 H.264 并使用（音频 copy）") }
        OutlinedButton(onClick = { run(CoreMediaAction.Remux) }, enabled = enabled && !capturing, modifier = Modifier.fillMaxWidth()) { Text("重新封装（不编码）") }
        OutlinedButton(onClick = { run(CoreMediaAction.Probe) }, enabled = enabled && !capturing, modifier = Modifier.fillMaxWidth()) { Text("检查视频（含解码）") }
        if (working) OutlinedButton(onClick = { editor.cancel() }) { Text("取消媒体处理") }
        if (status.isNotEmpty()) Text(status, modifier = Modifier.testTag("player-edit-status"))
        Text("统一媒体处理仅限已验证的格式、色彩、时间线与 metadata，输入 32 MiB / 60 秒、输出 48 MiB。当前写视频只支持无音频或立体声 AAC；不支持时明确拒绝，不切换旧实现。预览不保证 HDR/色彩保真，保留情况以操作报告为准。")
    }
}
