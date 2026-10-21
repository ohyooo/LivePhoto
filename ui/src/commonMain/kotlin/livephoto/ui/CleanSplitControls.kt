package livephoto.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import livephoto.core.CoreResult

@Composable
internal fun CleanSplitControls(platform: LivePhotoUiPlatform, reference: String?, enabled: Boolean, onBusy: (Boolean) -> Unit) {
    var prepared by remember(reference, platform) { mutableStateOf<PreparedCleanSplit?>(null) }
    var report by remember(reference, platform) { mutableStateOf<String?>(null) }
    var failed by remember(reference, platform) { mutableStateOf(false) }
    var saving by remember(reference, platform) { mutableStateOf<String?>(null) }
    var imageStatus by remember(reference, platform) { mutableStateOf<String?>(null) }
    var videoStatus by remember(reference, platform) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Text("清理拆分：先验证并准备普通图片和视频，再分别保存。不修改原片、不转码；当前仅 JPEG + MP4/MOV。")
    OutlinedButton(shape = ControlShape, onClick = {
        val selected = reference ?: return@OutlinedButton
        scope.launch {
            onBusy(true); saving = "prepare"; prepared = null; report = null; failed = false
            imageStatus = null; videoStatus = null
            try {
                when (val result = platform.cleanSplit(selected)) {
                    is CoreResult.Success -> { prepared = result.value; report = formatCleanSplit(result.value) }
                    is CoreResult.Failure -> { failed = true; report = formatCleanSplitFailure(result.error) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failed = true
                report = "清理拆分未完成：${error.message?.take(2000) ?: "未知异常"}\n未修改原片、未发起文件保存。"
            } finally { saving = null; onBusy(false) }
        }
    }, enabled = enabled && saving == null && reference != null,
        modifier = Modifier.fillMaxWidth().testTag("prepare-clean-split")) {
        Text(if (saving == "prepare") "正在准备拆分…" else "准备清理拆分")
    }
    prepared?.let { split ->
        Text("已准备并验证两个文件，请分别保存；详细保留报告在下方。")
        for ((key, file, status) in listOf(Triple("image", split.image, imageStatus), Triple("video", split.video, videoStatus))) {
            val label = if (key == "image") "保存拆分图片" else "保存拆分视频"
            OutlinedButton(shape = ControlShape, onClick = {
                scope.launch {
                    saving = key; onBusy(true)
                    try {
                        val message = platform.saveSplitFile(file)
                        if (key == "image") imageStatus = message else videoStatus = message
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        val message = "保存未完成或状态不确定：${error.message?.take(2000) ?: "未知异常"}；请先检查下载记录或相册，避免重复保存。另一文件的状态不受影响。"
                        if (key == "image") imageStatus = message else videoStatus = message
                    } finally { saving = null; onBusy(false) }
                }
            }, enabled = enabled && saving == null, modifier = Modifier.fillMaxWidth().testTag("save-split-$key")) {
                Text(if (saving == key) "正在保存…" else label)
            }
            status?.let { Text(it, Modifier.testTag("split-$key-status")) }
        }
    }
    report?.let { text ->
        ReportPanel(text, failed, "clean-split-report", "拆分", platform)
    }
}
