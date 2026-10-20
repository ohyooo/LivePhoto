package livephoto.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import livephoto.core.Time
import kotlin.math.roundToLong

@Composable
internal fun CoreMediaControls(editor: CoreVideoEditor, enabled: Boolean, start: Double, end: Double,
    frame: Double, trimOpen: Boolean, onBusy: (Boolean) -> Unit, onImage: (String) -> Unit, onVideo: (EditedVideo) -> Unit) {
    var open by remember(editor) { mutableStateOf(false) }
    var busy by remember(editor) { mutableStateOf(false) }
    var status by remember(editor) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun run(action: CoreMediaAction) {
        if (busy || !enabled) return
        busy = true; onBusy(true); status = "正在执行 Core $action；视频不会上传…"
        scope.launch {
            try {
                fun time(value: Double): Time {
                    require(value.isFinite() && value >= 0.0 && value <= 60.0) { "Core 工具仅限 60 秒内视频。" }
                    return Time((value * 1_000_000).roundToLong(), 1_000_000u)
                }
                val from = if (action == CoreMediaAction.ExtractFrame) time(frame) else if (action == CoreMediaAction.Trim) time(start) else Time.Zero
                val until = if (action == CoreMediaAction.Trim) time(end) else Time.Zero
                val result = editor.processCore(action, from, until)
                result.imageReference?.let(onImage)
                result.video?.let(onVideo)
                status = result.summary
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status = "Core 媒体处理失败：${error.message?.take(2000) ?: "未知错误"}。输入未替换，不自动回退旧实现。" }
            finally { busy = false; onBusy(false) }
        }
    }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(open, { open = it }, enabled = enabled && !busy,
            modifier = Modifier.semantics { contentDescription = "Core 媒体工具（实验）" })
        Text("Core 媒体工具（实验）")
    }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("严格 Core profile，非通用转码器。仅接受可验证的格式、色彩、时间线和 metadata；当前写视频仅无音频或立体声 AAC。不满足时拒绝，不回退。普通裁剪按钮也默认使用 Core；旧版兼容裁剪需手动选择。截图和显式转码入口暂保留原实现。")
        for ((action, label) in listOf(CoreMediaAction.Probe to "Core 检查整段（含解码）",
            CoreMediaAction.Remux to "Core 封装整段并使用（不编码）", CoreMediaAction.Trim to "Core 裁剪并使用（音视频 copy）",
            CoreMediaAction.ExtractFrame to "Core 抽取当前画面为封面", CoreMediaAction.Transcode to "Core 转码整段并使用（音频 copy）")) {
            OutlinedButton(onClick = { run(action) }, enabled = enabled && !busy && (action != CoreMediaAction.Trim || trimOpen && start < end),
                modifier = Modifier.fillMaxWidth().testTag("core-media-${action.name}")) { Text(label) }
        }
        if (busy) OutlinedButton(onClick = { editor.cancel() }) { Text("取消 Core 媒体处理") }
        if (status.isNotEmpty()) Text(status, modifier = Modifier.testTag("core-media-status"))
    }
}
