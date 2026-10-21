package livephoto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import livephoto.core.*

private data class InputSnapshot(val image: String?, val video: String?, val profile: Int)
private data class CreationReport(val input: InputSnapshot, val text: String, val failed: Boolean)
private val ProfileNames = listOf("OPPO / OnePlus", "Google Motion Photo V2", "Google MicroVideo V1")

@Composable
public fun LivePhotoScreen(platform: LivePhotoUiPlatform, darkTheme: Boolean = false, onToggleTheme: (() -> Unit)? = null,
    themeMode: ThemeMode = ThemeMode.System, onThemeSelected: ((ThemeMode) -> Unit)? = null) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var image by rememberSaveable { mutableStateOf<String?>(null) }
    var video by rememberSaveable { mutableStateOf<String?>(null) }
    var profile by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var analysisBusy by remember { mutableStateOf(false) }
    var mediaBusy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(VideoEditingState()) }
    var result by remember { mutableStateOf<CreationReport?>(null) }
    var resetDialog by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var fileDetails by remember { mutableStateOf<SelectedMediaInfo?>(null) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val resultFocus = remember { BringIntoViewRequester() }
    val scrolling: (Float) -> Unit = { delta -> scope.launch { scroll.scrollBy(delta) } }
    val locked = busy || analysisBusy || mediaBusy
    val imageInfo by produceState<SelectedMediaInfo?>(null, image, platform) {
        value = null; value = image?.let { platform.describe(it) }
    }
    val videoInfo by produceState<SelectedMediaInfo?>(null, video, platform) {
        value = null; value = video?.let { platform.describe(it) }
    }
    val imagePicker = platform.rememberPicker(MediaKind.Image) { selected -> if (selected != null) image = selected }
    val videoPicker = platform.rememberPicker(MediaKind.Video) { selected ->
        if (selected != null) { video = selected; editing = VideoEditingState() }
    }
    val editor = platform.rememberVideoEditor(video)
    val playback = editor?.playback?.value
    val coverWarning = platform.coverWarning(image, video)
    val confirmedTime = platform.coverPosition(image, video)
    val totalSize = if ((image == null || imageInfo?.sizeBytes != null) && (video == null || videoInfo?.sizeBytes != null))
        (imageInfo?.sizeBytes ?: 0) + (videoInfo?.sizeBytes ?: 0) else null
    val blocker = when {
        image == null && video == null -> "还需选择图片和视频"
        video == null -> "已选择图片，还需选择视频"
        image == null -> "已选择视频，还需选择图片或确认封面"
        coverWarning != null -> coverWarning
        editing.pendingTrim -> "请先应用裁剪或重置选段"
        totalSize != null && totalSize!! > 32L * 1024 * 1024 -> "输入合计超过 32 MiB，请更换素材"
        else -> null
    }
    fun reset() {
        image?.let(platform::release); video?.let(platform::release)
        image = null; video = null; editing = VideoEditingState(); resetDialog = false
    }
    fun generate() {
        if (locked || blocker != null) return
        val snapshot = InputSnapshot(image, video, profile)
        val imageName = imageInfo?.displayName; val videoName = videoInfo?.displayName
        val target = when (profile) {
            0 -> ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail"))
            1 -> ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg"))
            else -> ProtocolSelector(ProtocolIds.GoogleV1, ProfileId("jpeg"))
        }
        scope.launch {
            busy = true
            result = try {
                when (val response = platform.create(snapshot.image!!, snapshot.video!!, target)) {
                    is CoreResult.Success -> {
                        val operation = response.value.operation
                        val issues = (operation.issues + operation.validation.issues).distinct()
                        CreationReport(snapshot, buildString {
                            append("${platform.successMessage}\n")
                            append("Core 验证：${operation.validation.verdict}；覆盖：${operation.validation.coverage}；问题报告：${issues.size} 项。\n")
                            operation.output.assets.forEach { append("${it.recommendedName ?: it.mime}；${it.byteLength} bytes；SHA-256：${it.digest.value}\n") }
                            if (issues.isNotEmpty()) append("报告代码：${issues.take(16).joinToString { it.code.value }}\n")
                            operation.keyPhoto?.let { append("关键照片：${it.position}；来源：${it.source}\n") }
                            operation.preservation.records.forEach { append("${it.guarantee}: ${it.outcome}\n") }
                            operation.preservation.changes.forEach { append("变化：${it.selector}\n") }
                            append("默认 BestEffortWithReport，不保证全部 metadata 无损。生成成功不等于手机相册兼容。")
                        }, false)
                    }
                    is CoreResult.Failure -> CreationReport(snapshot, formatCoreFailure(response.error, imageName, videoName), true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { CreationReport(snapshot, platform.formatFailure(error, imageName, videoName), true) }
            finally { busy = false }
            withFrameNanos { }
            resultFocus.bringIntoView()
        }
    }
    VideoPlayerHost(editor, !locked && page == 0, scrolling) { player ->
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 800.dp
        val threeColumns = maxWidth >= 1280.dp
        val fixedAction = maxHeight >= 500.dp
        val windowWidth = LocalWindowInfo.current.containerSize.width
        val navigation: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("新建", "照片工具").forEachIndexed { index, name ->
                    TextButton(onClick = { if (index == 1 && playback?.paused == false) editor?.togglePlayback(); page = index }, shape = ControlShape,
                        modifier = Modifier.testTag("page-$index").semantics { contentDescription = name },
                        colors = ButtonDefaults.textButtonColors(contentColor = if (page == index)
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Text((if (page == index) "▏" else "") + name)
                    }
                }
            }
        }
        val action: @Composable () -> Unit = {
            Surface(Modifier.fillMaxWidth().testTag("main-action"), shadowElevation = 2.dp) {
                Column(Modifier.padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(blocker ?: if (busy) "正在生成与验证…" else "素材已准备好", Modifier.weight(1f).testTag("input-readiness"))
                        TextButton(onClick = { if (image != null || video != null) resetDialog = true }, enabled = !locked) { Text("重置输入") }
                        Button(onClick = ::generate, enabled = !locked && blocker == null, shape = ControlShape,
                            modifier = Modifier.height(48.dp).testTag("create")) { Text(if (busy) "正在生成…" else "生成动态照片") }
                    } else {
                        Text(blocker ?: if (busy) "正在生成与验证…" else "素材已准备好", Modifier.testTag("input-readiness"), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = ::generate, enabled = !locked && blocker == null, shape = ControlShape,
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("create")) { Text(if (busy) "正在生成…" else "生成动态照片") }
                        TextButton(onClick = { if (image != null || video != null) resetDialog = true }, enabled = !locked) { Text("重置输入") }
                    }
                    Text("本地处理 · 不上传文件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Scaffold(topBar = {
            Surface(Modifier.fillMaxWidth().testTag("app-bar"), shadowElevation = 1.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().height(if (wide) 56.dp else 48.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            Text("L", color = MaterialTheme.colorScheme.onPrimary)
                        }
                        Text("LivePhoto", style = MaterialTheme.typography.titleLarge)
                        if (wide) navigation()
                        Spacer(Modifier.weight(1f))
                        if (onThemeSelected != null || onToggleTheme != null)
                            ThemeToggle(darkTheme, { onToggleTheme?.invoke() }, themeMode, onThemeSelected)
                        TextButton(onClick = { about = true }, modifier = Modifier.size(44.dp).semantics { contentDescription = "更多菜单" },
                            contentPadding = PaddingValues(0.dp)) { Text("⋯") }
                    }
                    if (!wide) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { navigation() }
                }
            }
        }, bottomBar = { if (page == 0 && fixedAction) action() }) { padding ->
            val inputContent = retainedContent {
                SectionCard("选择输入", Modifier.testTag("input-card")) {
                    for (kind in listOf(MediaKind.Video, MediaKind.Image)) {
                        val isVideo = kind == MediaKind.Video
                        val reference = if (isVideo) video else image
                        val info = if (isVideo) videoInfo else imageInfo
                        val label = if (isVideo) "视频" else "图片"
                        if (reference == null) OutlinedButton(onClick = { if (isVideo) videoPicker() else imagePicker() }, enabled = !locked,
                            modifier = Modifier.fillMaxWidth().testTag(if (isVideo) "select-video" else "select-image"), shape = ControlShape) { Text("选择$label") }
                        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isVideo && editor?.thumbnail != null) platform.ImagePreview(editor.thumbnail!!, Modifier.size(40.dp), scrolling)
                            else if (isVideo) Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, ControlShape), contentAlignment = Alignment.Center) { Text("▶") }
                            else platform.ImagePreview(reference, Modifier.size(40.dp), scrolling)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("名称：${info?.displayName ?: "正在读取…"}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag(if (isVideo) "video-name" else "image-name").clickable { fileDetails = info })
                                Text("大小：${if (info == null) "正在读取…" else formatMediaSize(info.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(if (isVideo) "video-size" else "image-size"))
                            }
                            TextButton(onClick = { if (isVideo) videoPicker() else imagePicker() }, enabled = !locked,
                                modifier = Modifier.semantics { contentDescription = "重新选择$label" }, contentPadding = PaddingValues(4.dp)) { Text("更换") }
                            TextButton(onClick = {
                                platform.release(reference)
                                if (isVideo) { video = null; editing = VideoEditingState() } else image = null
                            }, enabled = !locked, modifier = Modifier.semantics { contentDescription = "移除$label" }, contentPadding = PaddingValues(4.dp)) { Text("移除") }
                        }
                        Text("$label：${if (reference == null) "未选择" else "已选择"}", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag(if (isVideo) "video-selection" else "image-selection"))
                        if (isVideo && playback?.ready == true) Text("${mediaClock(playback.duration)} · ${playback.width.toInt()} × ${playback.height.toInt()}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (editor != null) TextButton(onClick = { editing.coverOpen = true; editing.coverPosition = editor.playback.value.current }, enabled = !locked) { Text("从视频选封面") }
                    Text("参与生成：${formatMediaSize(totalSize)} / 32 MiB", Modifier.testTag("input-total"), style = MaterialTheme.typography.bodySmall)
                    Text("不修改原片。JPEG＋已支持配置的 MP4；可播放不等于可生成。", style = MaterialTheme.typography.bodySmall)
                }
            }
            val editingContent = retainedContent {
                editor?.let { current -> VideoEditingPanel(current, busy || analysisBusy, { mediaBusy = it },
                    onImage = { image = it }, onVideo = { video = it.reference }, onScroll = scrolling, player = player,
                    preview = { reference, modifier -> platform.ImagePreview(reference, modifier, scrolling) }, state = editing,
                    confirmedTime = confirmedTime, coverWarning = coverWarning, platform = platform) }
            }
            val outputContent = retainedContent {
                OutputSettings(profile, { profile = it }, !locked)
                result?.let { report ->
                    val stale = report.input != InputSnapshot(image, video, profile)
                    SectionCard(if (stale) "上次结果 · 输入已变化" else "本次结果", Modifier.bringIntoViewRequester(resultFocus).testTag("result-card")) {
                        Text(if (report.failed) "生成失败" else "已生成", color = if (report.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text("图片：${report.input.image?.let { if (it == image) imageInfo?.displayName else null } ?: "本次操作所选图片"}；目标：${ProfileNames[report.input.profile]}", style = MaterialTheme.typography.bodySmall)
                        if (!report.failed) Text("已发起下载，实际保存状态请在浏览器中确认。")
                        if (!report.failed && platform.hasResultPreview) {
                            platform.ResultPreview(Modifier.fillMaxWidth().height(180.dp), scrolling)
                            Text("按住预览，松开回封面；空格或回车同样可用。仅应用内模拟，不代表手机相册兼容。", style = MaterialTheme.typography.bodySmall)
                        }
                        ReportPanel(report.text, report.failed, "status", "生成", platform)
                    }
                }
            }
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).clipToBounds()) {
                Column(Modifier.fillMaxSize().offset { if (page == 0) IntOffset.Zero else IntOffset(windowWidth, 0) }
                    .then(if (page != 0) Modifier.clearAndSetSemantics { } else Modifier)
                    .verticalScroll(scroll).padding(if (wide) 24.dp else 16.dp).testTag("new-page"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.widthIn(max = 1800.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
                        if (!wide) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { inputContent(); editingContent(); outputContent() }
                        else Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Box(Modifier.width(if (threeColumns) 300.dp else 280.dp)) { inputContent() }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                editingContent()
                                if (!threeColumns) outputContent()
                            }
                            if (threeColumns) Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { outputContent() }
                        }
                    }
                    if (!fixedAction) action()
                }
                Box(Modifier.fillMaxSize().offset { if (page == 1) IntOffset.Zero else IntOffset(windowWidth, 0) }
                    .then(if (page != 1) Modifier.clearAndSetSemantics { } else Modifier)) {
                    PhotoAnalysisPanel(platform, !locked && page == 1) { analysisBusy = it }
                }
            }
        }
    }
    }
    if (resetDialog) AlertDialog(onDismissRequest = { resetDialog = false }, title = { Text("重置输入？") },
        text = { Text("清除当前素材和未应用编辑。原文件及已生成结果不会删除。") },
        confirmButton = { TextButton(onClick = ::reset) { Text("确认重置") } }, dismissButton = { TextButton(onClick = { resetDialog = false }) { Text("取消") } })
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("LivePhoto") },
        text = { Text("本地处理，不上传媒体。当前仅公开三个 JPEG 目标；媒体编辑为有限实验性能力，音频不编码。生成成功不等于手机相册兼容。") },
        confirmButton = { TextButton(onClick = { about = false }) { Text("知道了") } })
    fileDetails?.let { info -> AlertDialog(onDismissRequest = { fileDetails = null }, title = { Text("文件信息") },
        text = { Text("${info.displayName ?: "未知名称"}\n${formatMediaSize(info.sizeBytes)}") },
        confirmButton = { TextButton(onClick = { fileDetails = null }) { Text("关闭") } }) }
}

@Composable
private fun OutputSettings(profile: Int, onSelect: (Int) -> Unit, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    var formats by remember { mutableStateOf(false) }
    var planned by remember { mutableStateOf(false) }
    SectionCard("输出设置", Modifier.testTag("output-card")) {
        Text("目标协议")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 500.dp) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileNames.forEachIndexed { index, label -> OutlinedButton(onClick = { onSelect(index) }, enabled = enabled,
                    shape = ControlShape, modifier = Modifier.weight(1f).testTag("profile-$index")) { Text((if (profile == index) "✓ " else "") + label) } }
            } else Box {
                OutlinedButton(onClick = { open = true }, enabled = enabled, shape = ControlShape,
                    modifier = Modifier.fillMaxWidth().testTag("protocol-selector").semantics { contentDescription = "选择目标协议" }) { Text(ProfileNames[profile]) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    ProfileNames.forEachIndexed { index, label -> DropdownMenuItem(text = { Text((if (profile == index) "✓ " else "") + label) },
                        onClick = { onSelect(index); open = false }, modifier = Modifier.testTag("profile-$index")
                            .semantics { contentDescription = "协议：$label"; role = Role.Button }) }
                }
            }
        }
        Disclosure("格式说明", "JPEG 输出 · 不自动转码", formats, { formats = !formats }) {
            Text("OPPO / OnePlus、Google V2、Google V1。不是所有容器或 metadata 组合都支持；不会忽略安全错误。")
            Text("媒体编辑：输入 32 MiB / 60 秒，输出 48 MiB。当前写视频仅无音频或符合条件的立体声 AAC；音频始终 copy。HDR、ICC 和 metadata 的保留以报告为准。")
        }
        Disclosure("更多能力", "规划占位，不会启动任务", planned, { planned = !planned }) {
            Text("Apple 写入：暂停\n批量处理：未实现\n更多画面调整：规划占位", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
