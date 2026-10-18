package livephoto.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** UI adapter contract: no paths, browser objects or media processing in the shared screen. */
public data class VideoPlayback(
    val duration: Double = 0.0, val current: Double = 0.0, val seeking: Boolean = true,
    val ready: Boolean = false, val error: String? = null,
    val width: Double = 0.0, val height: Double = 0.0,
)
public data class EditedVideo(val reference: String, val summary: String)
public interface VideoEditor {
    public val playback: State<VideoPlayback>
    public val coverTime: Double? get() = null
    public fun seek(seconds: Double)
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
    onImage: (String) -> Unit, onVideo: (EditedVideo) -> Unit, onScroll: (Float) -> Unit) {
    val playback by editor.playback
    var start by remember(editor) { mutableDoubleStateOf(0.0) }
    var end by remember(editor) { mutableDoubleStateOf(0.0) }
    val cover = editor.coverTime
    var working by remember(editor) { mutableStateOf(false) }
    var status by remember(editor) { mutableStateOf("暂停并拖动进度条。A/B 选择裁剪范围，C 截取当前画面作为封面。") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(editor, playback.duration) { if (end == 0.0 && playback.duration > 0) end = playback.duration }
    val enabled = !parentBusy && !working && playback.ready
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("视频预览与 A / B / C", style = MaterialTheme.typography.titleMedium)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val aspect = if (playback.width > 0 && playback.height > 0) (playback.width / playback.height).toFloat() else 16f / 9f
            val portrait = aspect < 1f
            val videoWidth = (maxWidth - if (portrait) 56.dp else 24.dp).coerceAtLeast(1.dp)
            val videoHeight = (videoWidth / aspect).coerceAtMost(if (portrait) 1200.dp else 1080.dp)
            editor.Player(Modifier.fillMaxWidth().height(videoHeight + 84.dp), onScroll)
        }
        playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("A：${mediaSeconds(start)}　B：${mediaSeconds(end)}　C：${cover?.let(::mediaSeconds) ?: "未截取"}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled, onClick = { start = playback.current }) { Text("设为 A") }
            OutlinedButton(enabled = enabled, onClick = { end = playback.current }) { Text("设为 B") }
        }
        OutlinedButton(enabled = enabled && !playback.seeking, modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                working = true; onBusy(true)
                try {
                    require(start < end && playback.current >= start && playback.current < end) { "C 必须位于 A（含）与 B（不含）之间。" }
                    val selected = editor.capture()
                    onImage(selected)
                    status = "已将当前画面设为 C 和封面。截图会重新编码，不保证原视频 HDR/色彩信息。"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { status = "截取失败：${error.message ?: error.toString()}" }
                finally { working = false; onBusy(false) }
            }
        }) { Text("截取当前画面为 C／封面") }
        fun process(transcode: Boolean) {
            scope.launch {
                working = true; onBusy(true)
                status = "正在本地加载和执行 FFmpeg；不会上传视频…"
                try {
                    require(start >= 0 && start < end && end <= playback.duration + 0.001) { "需要 0 ≤ A < B ≤ 视频时长。" }
                    require(cover == null || cover >= start && cover < end) { "C 必须位于 A～B 内，请重新选点或截图。" }
                    val result = editor.process(start, end, transcode)
                    onVideo(result)
                    status = result.summary
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { status = "处理失败：${error.message ?: error.toString()}。没有替换输入视频。" }
                finally { working = false; onBusy(false) }
            }
        }
        Button(enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = { process(false) }) {
            Text("裁剪 A～B 并使用（音视频 copy）")
        }
        OutlinedButton(enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = { process(true) }) {
            Text("显式转为 H.264 并使用（音频 copy）")
        }
        if (working) OutlinedButton(onClick = { editor.cancel() }) { Text("取消媒体处理") }
        Text(status)
        Text("编辑仅限 32 MiB / 60 秒内视频。copy 裁剪可能提前至关键帧，以实际保留范围为准；转码仅支持 8-bit SDR，需显式点击，音频不编码。截图不保证 HDR/色彩信息，普通 metadata 不作完整保留保证。")
    }
}
