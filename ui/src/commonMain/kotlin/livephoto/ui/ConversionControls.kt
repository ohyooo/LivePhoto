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
internal fun ConversionControls(platform: LivePhotoUiPlatform, reference: String?, enabled: Boolean, onBusy: (Boolean) -> Unit) {
    var target by remember(reference, platform) { mutableStateOf(ConversionTarget.Oplus) }
    var report by remember(reference, platform) { mutableStateOf<String?>(null) }
    var failed by remember(reference, platform) { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text("协议转换：仅用于已有动态照片。同目标原样保留；跨协议可能因 EXIF 布局或其它安全限制拒绝，不会删除 metadata 绕过限制。")
    for (choice in ConversionTarget.entries) {
        OutlinedButton(onClick = { target = choice; report = null; failed = false }, enabled = enabled && !working,
            modifier = Modifier.fillMaxWidth().testTag("conversion-target-${choice.name}")) {
            Text((if (target == choice) "✓ " else "") + "转换为 ${choice.displayName}")
        }
    }
    Button(onClick = {
        val selected = reference ?: return@Button
        val selectedTarget = target.selector
        scope.launch {
            working = true; onBusy(true); report = null; failed = false
            try {
                report = when (val result = platform.convertPhoto(selected, selectedTarget)) {
                    is CoreResult.Success -> formatConvertedPhoto(result.value, platform.conversionSuccessMessage)
                    is CoreResult.Failure -> { failed = true; formatConversionFailure(result.error) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failed = true
                report = "转换或保存未完成：${error.message?.take(2000) ?: "未知异常"}\n原片未修改；请先检查下载记录或相册，避免重复保存。"
            } finally { working = false; onBusy(false) }
        }
    }, enabled = enabled && !working && reference != null, modifier = Modifier.fillMaxWidth().testTag("convert-photo")) {
        Text(if (working) "正在转换…" else "转换并另存")
    }
    report?.let { text ->
        Surface(color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant) {
            SelectionContainer { Text(text, Modifier.padding(12.dp).testTag("conversion-report")) }
        }
    }
}
