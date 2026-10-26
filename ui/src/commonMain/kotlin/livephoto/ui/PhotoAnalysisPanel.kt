package livephoto.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
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
    val active by rememberUpdatedState(enabled)
    var tool by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
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
    val names = listOf("检查照片", "提取原始视频", "清理拆分", "协议转换", "安全修复", "修改关键照片时间")
    val supported = listOf(platform.supportsAnalysis, platform.supportsVideoExtraction, platform.supportsCleanSplit,
        platform.supportsConversion, platform.supportsRepair, platform.supportsPhotoKey)
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 800.dp
        val input = retainedContent {
            SectionCard("选择已有动态照片") {
            Text("已有照片单文件上限 32 MiB，不修改原片。")
            Text("选择已有动态照片或普通照片。独立于新建素材；选择文件后不会自动执行处理。")
            OutlinedButton(onClick = { picker() }, enabled = active && !working, shape = ControlShape,
                modifier = Modifier.fillMaxWidth().testTag("select-analysis").semantics {
                    stateDescription = if (active && !working) "可以选择文件" else "处理中，暂不可选择文件"
                }) {
                Text(if (reference == null) "选择待检查照片" else "重新选择待检查照片")
            }
            Text("待检查文件：${info?.displayName ?: if (reference == null) "未选择" else "未知"}",
                modifier = Modifier.testTag("analysis-name"))
            Text("大小：${if (reference == null) "—" else formatMediaSize(info?.sizeBytes)}")
                Box {
                    OutlinedButton(onClick = { menu = true }, enabled = active && !working,
                        shape = ControlShape, modifier = Modifier.fillMaxWidth().testTag("tool-selector")
                            .semantics { contentDescription = "选择照片工具" }) { Text(names[tool]) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        names.forEachIndexed { index, name ->
                            DropdownMenuItem(text = { Text(name) }, enabled = supported[index], onClick = { tool = index; menu = false },
                                modifier = Modifier.testTag("tool-$index").semantics { contentDescription = "工具：$name"; role = Role.Button })
                        }
                    }
                }
            }
        }
        val operation = retainedContent {
            SectionCard(names[tool], Modifier.testTag("tool-content")) {
                if (tool == 0) {
                Button(onClick = {
                    val selected = reference ?: return@Button
                    scope.launch {
                        working = true; onBusy(true); report = null; failed = false
                        try {
                            val result = platform.runTask(UiTask(UiTaskKind.Analyze, selected))
                            report = result.report; failed = result.failed
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            failed = true
                            report = "检查未完成：${error.message?.take(2000) ?: "未知异常"}\n未修改输入、未生成文件。请重新选择文件。"
                        } finally { working = false; onBusy(false) }
                    }
                }, enabled = active && !working && reference != null, shape = ControlShape,
                    modifier = Modifier.fillMaxWidth().testTag("analyze")) {
                    Text(if (working && !extracting && !splitting && !converting && !repairing && !settingKey) "正在检查…" else "开始检查（只读）")
                }
                }
                if (tool == 1 && platform.supportsVideoExtraction) {
                    Text("原始视频提取是单独操作：另存 MP4/MOV，不修改照片、不转码，不等于 Clean Split。")
                    OutlinedButton(onClick = {
                        val selected = reference ?: return@OutlinedButton
                        scope.launch {
                            working = true; extracting = true; onBusy(true); exportReport = null; exportFailed = false
                            try {
                                val result = platform.runTask(UiTask(UiTaskKind.Extract, selected))
                                exportReport = result.report; exportFailed = result.failed
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                exportFailed = true
                                exportReport = "提取或保存未完成：${error.message?.take(2000) ?: "未知异常"}\n原片未修改；请先检查保存位置和记录，避免重复保存。"
                            } finally { working = false; extracting = false; onBusy(false) }
                        }
                    }, enabled = active && !working && reference != null, shape = ControlShape,
                        modifier = Modifier.fillMaxWidth().testTag("extract-video")) {
                        Text(if (working && extracting) "正在提取…" else "提取原始视频")
                    }
                    exportReport?.let { text ->
                        ReportPanel(text, exportFailed, "extraction-report", "提取", platform)
                    }
                }
                if (tool == 2 && platform.supportsCleanSplit) {
                    key(reference, selectionRevision) {
                        CleanSplitControls(platform, reference, active && !working) { busy ->
                            splitting = busy; working = busy; onBusy(busy)
                        }
                    }
                }
                if (tool == 3 && platform.supportsConversion) {
                    key(reference, selectionRevision) {
                        ConversionControls(platform, reference, active && !working) { busy ->
                            converting = busy; working = busy; onBusy(busy)
                        }
                    }
                }
                if (tool == 4 && platform.supportsRepair) {
                    key(reference, selectionRevision) {
                        RepairControls(platform, reference, active && !working) { busy ->
                            repairing = busy; working = busy; onBusy(busy)
                        }
                    }
                }
                if (tool == 5 && platform.supportsPhotoKey) {
                    key(reference, selectionRevision) {
                        PhotoKeyControls(platform, reference, active && !working) { busy ->
                            settingKey = busy; working = busy; onBusy(busy)
                        }
                    }
                }
                report?.let { text ->
                    ReportPanel(text, failed, "analysis-report", "检查", platform)
                }
                if (working && platform.supportsTaskCancellation)
                    OutlinedButton(onClick = platform::cancelTask, modifier = Modifier.testTag("cancel-task")) { Text("取消处理") }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(if (wide) 24.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("照片工具", style = MaterialTheme.typography.headlineSmall)
            Box(Modifier.widthIn(max = 1800.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.width(300.dp)) { input() }
                    Box(Modifier.weight(1f)) { operation() }
                } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { input(); operation() }
            }
        }
    }
}
