package livephoto.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import livephoto.core.CoreResult

@Composable
internal fun PhotoAnalysisPanel(platform: LivePhotoUiPlatform, enabled: Boolean, onBusy: (Boolean) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var reference by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionRevision by rememberSaveable { mutableStateOf(0) }
    var report by rememberSaveable { mutableStateOf<String?>(null) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var extracting by remember { mutableStateOf(false) }
    var splitting by remember { mutableStateOf(false) }
    var converting by remember { mutableStateOf(false) }
    var repairing by remember { mutableStateOf(false) }
    var settingKey by remember { mutableStateOf(false) }
    var exportReport by rememberSaveable { mutableStateOf<String?>(null) }
    var exportFailed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = platform.rememberPicker(MediaKind.LivePhoto) { selected ->
        if (selected != null) { selectionRevision++; reference = selected; report = null; failed = false; exportReport = null; exportFailed = false }
    }
    val info by produceState<SelectedMediaInfo?>(null, reference, selectionRevision, platform) {
        value = null
        try { value = reference?.let { platform.describe(it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The explicit read operation reports an inaccessible input. */ }
    }
    OutlinedButton(onClick = { expanded = !expanded }, enabled = enabled && !working,
        modifier = Modifier.fillMaxWidth().testTag("toggle-analysis")) {
        Text(if (expanded) "收起照片检查" else "检查已有动态照片")
    }
    if (expanded) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("只读检查", style = MaterialTheme.typography.titleMedium)
                Text("选择一张已有动态照片或普通照片。无需视频，不修改原片、不修复、不转码；单文件上限 32 MiB。")
                OutlinedButton(onClick = picker, enabled = enabled && !working,
                    modifier = Modifier.fillMaxWidth().testTag("select-analysis")) {
                    Text(if (reference == null) "选择待检查照片" else "重新选择待检查照片")
                }
                Text("待检查文件：${info?.displayName ?: if (reference == null) "未选择" else "未知"}",
                    modifier = Modifier.testTag("analysis-name"))
                Text("大小：${if (reference == null) "—" else formatMediaSize(info?.sizeBytes)}")
                Button(onClick = {
                    val selected = reference ?: return@Button
                    scope.launch {
                        working = true; onBusy(true); report = null; failed = false
                        try {
                            report = when (val result = platform.analyze(selected)) {
                                is CoreResult.Success -> formatPhotoAnalysis(result.value)
                                is CoreResult.Failure -> { failed = true; formatPhotoAnalysisFailure(result.error) }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            failed = true
                            report = "检查未完成：${error.message?.take(2000) ?: "未知异常"}\n未修改输入、未生成文件。请重新选择文件。"
                        } finally { working = false; onBusy(false) }
                    }
                }, enabled = enabled && !working && reference != null,
                    modifier = Modifier.fillMaxWidth().testTag("analyze")) {
                    Text(if (working && !extracting && !splitting && !converting && !repairing && !settingKey) "正在检查…" else "开始检查（只读）")
                }
                if (platform.supportsVideoExtraction) {
                    Text("原始视频提取是单独操作：另存 MP4/MOV，不修改照片、不转码，不等于 Clean Split。")
                    OutlinedButton(onClick = {
                        val selected = reference ?: return@OutlinedButton
                        scope.launch {
                            working = true; extracting = true; onBusy(true); exportReport = null; exportFailed = false
                            try {
                                exportReport = when (val result = platform.extractVideo(selected)) {
                                    is CoreResult.Success -> formatExtractedVideo(result.value, platform.videoExportSuccessMessage)
                                    is CoreResult.Failure -> { exportFailed = true; formatVideoExtractionFailure(result.error) }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                exportFailed = true
                                exportReport = "提取或保存未完成：${error.message?.take(2000) ?: "未知异常"}\n原片未修改；请先检查下载记录或相册，避免重复保存。"
                            } finally { working = false; extracting = false; onBusy(false) }
                        }
                    }, enabled = enabled && !working && reference != null,
                        modifier = Modifier.fillMaxWidth().testTag("extract-video")) {
                        Text(if (working && extracting) "正在提取…" else "提取原始视频")
                    }
                    exportReport?.let { text ->
                        Surface(color = if (exportFailed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            SelectionContainer { Text(text, Modifier.padding(12.dp).testTag("extraction-report")) }
                        }
                    }
                }
                if (platform.supportsCleanSplit) {
                    key(reference, selectionRevision) {
                        CleanSplitControls(platform, reference, enabled && !working) { busy ->
                            splitting = busy; working = busy; onBusy(busy)
                        }
                    }
                }
                if (platform.supportsConversion) {
                    key(reference, selectionRevision) {
                        ConversionControls(platform, reference, enabled && !working) { busy ->
                            converting = busy; working = busy; onBusy(busy)
                        }
                    }
                }
                if (platform.supportsRepair) {
                    key(reference, selectionRevision) {
                        RepairControls(platform, reference, enabled && !working) { busy ->
                            repairing = busy; working = busy; onBusy(busy)
                        }
                    }
                }
                if (platform.supportsPhotoKey) {
                    key(reference, selectionRevision) {
                        PhotoKeyControls(platform, reference, enabled && !working) { busy ->
                            settingKey = busy; working = busy; onBusy(busy)
                        }
                    }
                }
                report?.let { text ->
                    Surface(color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        SelectionContainer { Text(text, Modifier.padding(12.dp).testTag("analysis-report")) }
                    }
                }
            }
        }
    }
}
