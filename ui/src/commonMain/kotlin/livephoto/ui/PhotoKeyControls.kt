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
internal fun PhotoKeyControls(platform: LivePhotoUiPlatform, reference: String?, enabled: Boolean, onBusy: (Boolean) -> Unit) {
    var seconds by remember(reference) { mutableStateOf("0") }
    var report by remember(reference, seconds) { mutableStateOf<String?>(null) }
    var failed by remember(reference, seconds) { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text("修改已有动态照片的关键照片时间（秒）。选择最近画面，容差 100 毫秒；只改 metadata，不抽帧、不替换静态封面。")
    OutlinedTextField(value = seconds, onValueChange = { seconds = it }, label = { Text("关键照片时间（秒）") },
        enabled = enabled && !working, singleLine = true, isError = parseKeySeconds(seconds) == null,
        modifier = Modifier.fillMaxWidth().testTag("key-seconds"))
    Button(onClick = {
        if (working) return@Button
        val selected = reference ?: return@Button
        val requested = parseKeySeconds(seconds) ?: return@Button
        scope.launch {
            working = true; onBusy(true); report = null; failed = false
            try {
                when (val result = platform.setPhotoKey(selected, requested)) {
                    is CoreResult.Success -> report = formatPhotoKey(result.value, platform.keySuccessMessage)
                    is CoreResult.Failure -> { failed = true; report = formatPhotoKeyFailure(result.error) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed = true; report = "关键照片修改或保存未完成：${error.message?.take(2000) ?: "未知异常"}\n原片未修改；请先检查下载记录或相册，避免重复保存。" }
            finally { working = false; onBusy(false) }
        }
    }, enabled = enabled && !working && reference != null && parseKeySeconds(seconds) != null,
        modifier = Modifier.fillMaxWidth().testTag("set-photo-key")) { Text(if (working) "正在修改关键照片…" else "修改关键照片时间并另存") }
    report?.let { text -> ReportPanel(text, failed, "key-report", "关键照片", platform) }
}
