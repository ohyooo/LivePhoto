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
internal fun RepairControls(platform: LivePhotoUiPlatform, reference: String?, enabled: Boolean, onBusy: (Boolean) -> Unit) {
    var result by remember(reference, platform) { mutableStateOf<UiTaskResult?>(null) }
    var report by remember(reference, platform) { mutableStateOf<String?>(null) }
    var failed by remember(reference, platform) { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun runRepair(apply: (suspend () -> UiTaskResult)?) {
        if (working) return
        val selected = reference ?: return
        scope.launch {
            working = true; onBusy(true); result = null; report = null; failed = false
            try {
                val response = apply?.invoke() ?: platform.runTask(UiTask(UiTaskKind.Repair, selected))
                result = response; report = response.report; failed = response.failed
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failed = true
                report = "修复或保存未完成：${error.message?.take(2000) ?: "未知异常"}\n原片未修改；请先检查下载记录或相册，避免重复保存。"
            } finally { working = false; onBusy(false) }
        }
    }
    Text("有限安全修复：先只读预览，再显式应用并另存 JPEG。仅修复 Core 可证明的 metadata 问题；OPPO 等不明确布局会拒绝，不猜测或删除检查。")
    OutlinedButton(shape = ControlShape, onClick = { runRepair(null) }, enabled = enabled && !working && reference != null,
        modifier = Modifier.fillMaxWidth().testTag("preview-repair")) { Text("预览安全修复（只读）") }
    Button(shape = ControlShape, onClick = { result?.applyRepair?.let(::runRepair) }, enabled = enabled && !working && result?.applyRepair != null,
        modifier = Modifier.fillMaxWidth().testTag("apply-repair")) { Text(if (working) "正在处理修复…" else "应用修复并另存") }
    report?.let { text ->
        ReportPanel(text, failed, "repair-report", "修复", platform)
    }
}
