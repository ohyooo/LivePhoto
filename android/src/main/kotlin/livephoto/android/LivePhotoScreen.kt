package livephoto.android

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import livephoto.core.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LivePhotoScreen(
    describe: (suspend (Uri) -> SelectedMediaInfo)? = null,
    create: suspend (Uri, Uri, ProtocolSelector) -> CoreResult<AndroidCreateController.Created>,
) {
    var image by rememberSaveable { mutableStateOf<String?>(null) }
    var video by rememberSaveable { mutableStateOf<String?>(null) }
    var profile by rememberSaveable { mutableIntStateOf(0) }
    var status by rememberSaveable { mutableStateOf("当前不自动从视频抽取封面；需要 JPEG 图片＋MP4 视频。不裁剪或转码。") }
    var busy by remember { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val resolver = LocalContext.current.contentResolver
    val imageInfo by produceState<SelectedMediaInfo?>(null, image, describe) {
        value = null
        value = image?.let { uri -> describe?.invoke(Uri.parse(uri)) ?: readSelectedMediaInfo(resolver, Uri.parse(uri)) }
    }
    val videoInfo by produceState<SelectedMediaInfo?>(null, video, describe) {
        value = null
        value = video?.let { uri -> describe?.invoke(Uri.parse(uri)) ?: readSelectedMediaInfo(resolver, Uri.parse(uri)) }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Cancelling a picker must not erase an earlier valid selection.
        if (uri != null) {
            image = uri.toString()
            status = "图片已更新"
            failed = false
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            video = uri.toString()
            status = "视频已更新"
            failed = false
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("LivePhoto") }, modifier = Modifier.testTag("app-bar"))
    }) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
                .verticalScroll(scroll).padding(16.dp).testTag("content"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("图片＋视频 → Live Photo", style = MaterialTheme.typography.titleLarge)
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
            )) {
                SelectionContainer {
                    Text(status, modifier = Modifier.padding(12.dp).testTag("status"))
                }
            }
            Text("输入合计上限 32 MiB，不修改原片。生成后保存到 Pictures/LivePhoto。")
            OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("select-image")) {
                Text(if (image == null) "选择图片" else "重新选择图片")
            }
            Text(if (image == null) "图片：未选择" else "图片：已选择", modifier = Modifier.testTag("image-selection"))
            if (image != null) {
                Text("名称：${imageInfo?.displayName ?: "未知"}", modifier = Modifier.testTag("image-name"))
                Text("大小：${if (imageInfo == null) "正在读取…" else formatMediaSize(imageInfo?.sizeBytes)}", modifier = Modifier.testTag("image-size"))
            }
            OutlinedButton(onClick = { videoPicker.launch(arrayOf("video/*")) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("select-video")) {
                Text(if (video == null) "选择视频" else "重新选择视频")
            }
            Text(if (video == null) "视频：未选择" else "视频：已选择", modifier = Modifier.testTag("video-selection"))
            if (video != null) {
                Text("名称：${videoInfo?.displayName ?: "未知"}", modifier = Modifier.testTag("video-name"))
                Text("大小：${if (videoInfo == null) "正在读取…" else formatMediaSize(videoInfo?.sizeBytes)}", modifier = Modifier.testTag("video-size"))
            }
            Text("目标协议", style = MaterialTheme.typography.titleMedium)
            listOf("OPPO / OnePlus", "Google Motion Photo V2", "Google MicroVideo V1").forEachIndexed { index, label ->
                OutlinedButton(onClick = { profile = index }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("profile-$index")) {
                    Text(if (profile == index) "✓ $label" else label)
                }
            }
            Button(enabled = !busy && image != null && video != null,
                modifier = Modifier.fillMaxWidth().testTag("create"), onClick = {
                    val chosenImage = image?.let(Uri::parse) ?: return@Button
                    val chosenVideo = video?.let(Uri::parse) ?: return@Button
                    val target = when (profile) {
                        0 -> ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail"))
                        1 -> ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg"))
                        else -> ProtocolSelector(ProtocolIds.GoogleV1, ProfileId("jpeg"))
                    }
                    scope.launch {
                        busy = true
                        failed = false
                        status = "正在生成与验证…"
                        try {
                            status = when (val result = create(chosenImage, chosenVideo, target)) {
                                is CoreResult.Success -> "已保存到 Pictures/LivePhoto。\nCore 验证：${result.value.operation.validation.verdict}；问题报告：${result.value.operation.issues.size} 项。\n默认 BestEffortWithReport，不保证全部 metadata 无损。请在相册测试动态播放与声音；生成成功不等于相册兼容。"
                                is CoreResult.Failure -> { failed = true; formatCoreFailure(result.error) }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { failed = true; status = formatAndroidFailure(error) }
                        finally { busy = false }
                        // The user is normally at the bottom near Create; reveal the actual result.
                        scroll.animateScrollTo(0)
                    }
                }) { Text(if (busy) "正在生成…" else "生成并保存 Live Photo") }
            Text(when {
                image == null && video == null -> "还需选择图片和视频"
                image == null -> "已选择视频，还需选择图片"
                video == null -> "已选择图片，还需选择视频"
                else -> "图片和视频已准备好"
            }, modifier = Modifier.testTag("input-readiness"))
        }
    }
}
