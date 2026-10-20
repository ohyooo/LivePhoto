package livephoto.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import livephoto.core.Time
import kotlin.math.roundToLong

/** UI adapter contract: no paths, browser objects or media processing in the shared screen. */
public data class VideoPlayback(
    val duration: Double = 0.0, val current: Double = 0.0, val seeking: Boolean = true,
    val ready: Boolean = false, val error: String? = null,
    val width: Double = 0.0, val height: Double = 0.0,
    val paused: Boolean = true, val volume: Double = 1.0, val muted: Boolean = false,
    val rate: Double = 1.0, val fullscreen: Boolean = false,
)
public data class EditedVideo(val reference: String, val summary: String)
public interface VideoEditor {
    public val playback: State<VideoPlayback>
    public val coverTime: Double? get() = null
    public fun seek(seconds: Double)
    public val canFullscreen: Boolean get() = false
    public fun togglePlayback() {}
    public fun setVolume(volume: Double) {}
    public fun setMuted(muted: Boolean) {}
    public fun setRate(rate: Double) {}
    public fun setFullscreen(fullscreen: Boolean) {}
    public suspend fun capture(): String
    public suspend fun process(start: Double, end: Double, transcodeVideo: Boolean): EditedVideo
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
    player: @Composable () -> Unit,
    preview: @Composable (String, Modifier) -> Unit) {
    val playback by editor.playback
    var start by remember(editor) { mutableDoubleStateOf(0.0) }
    var end by remember(editor) { mutableDoubleStateOf(0.0) }
    val cover = editor.coverTime
    var working by remember(editor) { mutableStateOf(false) }
    var status by remember(editor) { mutableStateOf("") }
    var trimOpen by remember(editor) { mutableStateOf(false) }
    var coverOpen by remember(editor) { mutableStateOf(false) }
    var coverPosition by remember(editor) { mutableDoubleStateOf(0.0) }
    var coverPreview by remember(editor) { mutableStateOf<String?>(null) }
    var capturing by remember(editor) { mutableStateOf(false) }
    var coreWorking by remember(editor) { mutableStateOf(false) }
    var compatibilityTrim by remember(editor) { mutableStateOf(false) }
    var compatibilityTranscode by remember(editor) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    LaunchedEffect(editor, playback.duration) { if (end == 0.0 && playback.duration > 0) end = playback.duration }
    val enabled = !parentBusy && !working && !coreWorking && playback.ready
    val canCapture by rememberUpdatedState(enabled)
    fun process(transcode: Boolean) {
        if (!enabled || capturing) return
        working = true; onBusy(true)
        scope.launch {
            status = "正在本地加载和执行 FFmpeg；不会上传视频…"
            try {
                val from = if (trimOpen) start else 0.0
                val until = if (trimOpen) end else playback.duration
                require(from >= 0 && from < until && until <= playback.duration + 0.001) { "请选择视频时长内的有效片段。" }
                val strictCore = editor is CoreVideoEditor && (if (transcode) !compatibilityTranscode else !compatibilityTrim)
                // Core checks the validated actual retained window, not merely
                // the requested bounds. Legacy semantics remain explicit.
                if (!strictCore) require(cover == null || cover >= from && cover < until) { "封面位置不在保留片段内，请调整封面或裁剪范围。" }
                val result = if (strictCore) {
                    require(!transcode || !trimOpen) { "请先应用裁剪，再执行 Core 转码；不会忽略当前选段。" }
                    require(from.isFinite() && until.isFinite() && from >= 0 && until <= 60) { "Core 媒体处理仅限 60 秒内视频。" }
                    val action = if (transcode) CoreMediaAction.Transcode else CoreMediaAction.Trim
                    val prepared = (editor as CoreVideoEditor).processCore(action,
                        if (transcode) Time.Zero else Time((from * 1_000_000).roundToLong(), 1_000_000u),
                        if (transcode) Time.Zero else Time((until * 1_000_000).roundToLong(), 1_000_000u))
                    checkNotNull(prepared.video) { "Core 未返回视频；输入未替换。" }
                } else editor.process(from, until, transcode)
                onVideo(result)
                status = result.summary
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status = "${if (transcode) "转码" else "裁剪"}失败：${error.message?.take(2000) ?: "未知错误"}。没有替换输入视频，不自动切换处理路径。" }
            finally { working = false; onBusy(false) }
        }
    }
    LaunchedEffect(editor, coverOpen) {
        if (coverOpen) {
            try {
                // Conflate, do not debounce: long drags produce live previews
                // periodically instead of waiting until the user lets go.
                snapshotFlow { coverPosition }.conflate().collect { position ->
                    if (canCapture) {
                        onBusy(true); capturing = true
                        try {
                            val frame = withTimeout(5000) {
                                editor.seek(position)
                                snapshotFlow { editor.playback.value }.first { it.ready && !it.seeking }
                                editor.capture()
                            }
                            coverPreview = frame; onImage(frame)
                            status = "封面已更新。画面时间按协议精度自动换算，不要求逐帧精确。"
                        } catch (timeout: TimeoutCancellationException) { status = "画面加载超时，请重试或重新选择视频。" }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { status = "截取失败：${error.message ?: error.toString()}" }
                        if (position == coverPosition) { capturing = false; onBusy(false) }
                        delay(80)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { capturing = false; onBusy(false) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        player()
        playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            // Do not keep the cover timeline as the scroll/focus target when
            // opening another editor; it can move the page during a range drag.
            Checkbox(checked = trimOpen, enabled = enabled, onCheckedChange = { focus.clearFocus(); trimOpen = it }, modifier = Modifier.semantics { contentDescription = "裁剪视频" })
            Text("裁剪视频")
            Spacer(Modifier.width(16.dp))
            Checkbox(checked = coverOpen, enabled = enabled, onCheckedChange = { focus.clearFocus(); coverOpen = it; if (it) coverPosition = playback.current }, modifier = Modifier.semantics { contentDescription = "截取封面" })
            Text("截取封面")
        }
        if (coverOpen) {
            Text("封面位置：${mediaSeconds(coverPosition)}")
            MediaSeekBar(coverPosition, playback.duration, enabled, "封面位置", Modifier.fillMaxWidth().height(40.dp).testTag("cover-progress"), onSeek = { coverPosition = it })
            coverPreview?.let { preview(it, Modifier.fillMaxWidth().height(160.dp)) }
        }
        if (trimOpen) {
            Text("保留片段：${mediaSeconds(start)} ～ ${mediaSeconds(end)}")
            RangeSlider(value = start.toFloat()..end.toFloat(), enabled = enabled && playback.duration > 0,
                valueRange = 0f..playback.duration.coerceAtLeast(0.001).toFloat(),
                onValueChange = { range ->
                    val moved = if (range.start.toDouble() != start) range.start.toDouble() else range.endInclusive.toDouble()
                    start = range.start.toDouble(); end = range.endInclusive.toDouble(); editor.seek(moved)
                }, modifier = Modifier.fillMaxWidth().testTag("trim-progress").semantics { contentDescription = "裁剪范围" })
            Text("拖动两端手柄，保留中间高亮片段。")
            if (editor is CoreVideoEditor) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(compatibilityTrim, { compatibilityTrim = it }, enabled = enabled && !capturing,
                        modifier = Modifier.semantics { contentDescription = "旧版兼容裁剪" })
                    Text("旧版兼容裁剪")
                }
                Text(if (compatibilityTrim) "当前使用旧预处理，不具备 Core 的完整保真检查；音视频仍为 copy。"
                    else "默认使用 Core 验证裁剪；仅限已支持的媒体配置，失败不自动回退。旧版兼容裁剪需手动勾选。")
            }
            Button(enabled = enabled && !capturing && start < end, modifier = Modifier.fillMaxWidth(), onClick = { process(false) }) {
                Text("应用裁剪（音视频 copy）")
            }
        }
        if (editor is CoreVideoEditor) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(compatibilityTranscode, { compatibilityTranscode = it }, enabled = enabled && !capturing,
                    modifier = Modifier.semantics { contentDescription = "旧版兼容转码" })
                Text("旧版兼容转码")
            }
            Text(if (compatibilityTranscode) "当前使用旧版转码预处理；可同时应用选段，不具备 Core 的完整保真检查，音频仍 copy。"
                else if (trimOpen) "Core 转码处理当前整段视频。请先应用裁剪，再转码；不会忽略选段。"
                else "默认使用 Core 显式转码当前整段视频；仅限已支持的媒体配置，音频 copy，失败不自动回退，处理后需重新选封面。")
        }
        OutlinedButton(enabled = enabled && !capturing && (editor !is CoreVideoEditor || compatibilityTranscode || !trimOpen),
            modifier = Modifier.fillMaxWidth().testTag("player-transcode"), onClick = { process(true) }) {
            Text("显式转为 H.264 并使用（音频 copy）")
        }
        if (working) OutlinedButton(onClick = { editor.cancel() }) { Text("取消媒体处理") }
        if (status.isNotEmpty()) Text(status, modifier = Modifier.testTag("player-edit-status"))
        if (editor is CoreVideoEditor) CoreMediaControls(editor, !parentBusy && !working && !capturing && playback.ready,
            start, end, if (coverOpen) coverPosition else playback.current, trimOpen,
            onBusy = { coreWorking = it; onBusy(it) }, onImage = onImage, onVideo = onVideo)
        Text("编辑仅限 32 MiB / 60 秒内视频。copy 裁剪可能提前至关键帧，以实际保留范围为准；转码仅支持 8-bit SDR，需显式点击，音频不编码。截图不保证 HDR/色彩信息，普通 metadata 不作完整保留保证。")
    }
}
