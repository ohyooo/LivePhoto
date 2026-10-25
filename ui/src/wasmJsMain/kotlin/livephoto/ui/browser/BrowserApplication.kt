@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package livephoto.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.material3.Text
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.ensureActive
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLElement
import org.w3c.files.File
import org.w3c.files.Blob
import org.khronos.webgl.Int8Array
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.math.roundToLong
import livephoto.core.*
import livephoto.ui.*

/** Normalize native JS Promise errors before they cross shared Kotlin UI handlers. */
private suspend fun <T : JsAny> browserAwait(promise: Promise<T>): T = promise.await()

private fun downloadBlob(blob: Blob, name: String) {
    val url = browserObjectUrl(blob)
    try {
        val link = (document.createElement("a") as HTMLAnchorElement).apply { href = url; download = name }
        document.body!!.appendChild(link)
        try { link.click() } finally { link.remove() }
    } finally { window.setTimeout({ browserRevokeUrl(url); null }, 60_000) }
}

public fun main() {
    browserRuntimeReady()
    ComposeViewport(document.getElementById("app")!!, configure = { isA11YEnabled = true }) {
        val browserDark = isSystemInDarkTheme()
        var themeMode by remember { mutableStateOf(ThemeMode.System) }
        val dark = when (themeMode) { ThemeMode.System -> browserDark; ThemeMode.Light -> false; ThemeMode.Dark -> true }
        SideEffect {
            (document.documentElement as? HTMLElement)?.style?.setProperty("color-scheme", if (dark) "dark" else "light")
            (document.documentElement as? HTMLElement)?.style?.setProperty("--livephoto-media-background", if (dark) "#11151b" else "#f5f7fa")
        }
        val platform = remember { BrowserPlatform() }
        val resolver = LocalFontFamilyResolver.current
        var fontsReady by remember { mutableStateOf(false) }
        var fontError by remember { mutableStateOf(false) }
        LaunchedEffect(resolver) {
            try {
                val values = Int8Array(browserFont().await())
                val bytes = ByteArray(values.length) { browserByte(values, it) }
                resolver.preload(FontFamily(Font("NotoSansSC", bytes)))
                platform.media = browserMediaReady().await()
                fontsReady = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { fontError = true }
        }
        MaterialTheme(colorScheme = livePhotoColors(dark), typography = LivePhotoTypography) {
            Surface(Modifier.fillMaxSize()) {
                if (fontsReady) LivePhotoScreen(platform, dark, themeMode = themeMode, onThemeSelected = { themeMode = it })
                else Text(if (fontError) "Unable to load bundled font. Reload the page or check the static server." else "Loading fonts...")
            }
        }
    }
}

private class BrowserPlatform : LivePhotoUiPlatform {
    private val files = mutableMapOf<String, File>()
    lateinit var media: BrowserMedia
    private data class Timeline(val root: String, val origin: Double)
    private data class Frame(val image: String, val root: String, val sourceTime: Double)
    private val timelines = mutableMapOf<String, Timeline>()
    private var selectedFrame by mutableStateOf<Frame?>(null)
    private var lastResult by mutableStateOf<Pair<Blob, Blob>?>(null)
    private var next = 0
    override val outputDescription = "浏览器下载"
    override val supportsVideoEditing = true
    override val supportsAnalysis = true
    override val supportsVideoExtraction = true
    override val supportsCleanSplit = true
    override val supportsConversion = true
    override val supportsRepair = true
    override val supportsPhotoKey = true
    override val keySuccessMessage = "已修改关键照片时间并发起下载，请检查下载记录。"
    override val repairSuccessMessage = "已应用修复并发起下载，请检查下载记录。"
    override val conversionSuccessMessage = "已转换并发起下载，请检查下载记录。"
    override val videoExportSuccessMessage = "已提取并发起视频下载，请检查下载记录。"
    override val hasResultPreview: Boolean get() = lastResult != null
    override val successMessage = "已生成并发起浏览器下载，请检查下载记录。"
    override val supportsClipboard: Boolean get() = browserClipboardAvailable()
    override suspend fun copyText(text: String): Boolean { browserCopyText(text).await(); return true }
    override fun release(reference: String) {
        files.remove(reference); timelines.remove(reference)
        if (selectedFrame?.image == reference) selectedFrame = null
    }
    override fun coverWarning(image: String?, video: String?): String? {
        val frame = selectedFrame?.takeIf { it.image == image } ?: return null
        val timeline = timelines[video]
        return if (timeline == null || frame.root != timeline.root || frame.sourceTime < timeline.origin)
            "视频已变化，请重新选择封面" else null
    }
    override fun coverPosition(image: String?, video: String?): Time? {
        val frame = selectedFrame?.takeIf { it.image == image } ?: return null
        val timeline = timelines[video] ?: return null
        if (coverWarning(image, video) != null) return null
        return Time(((frame.sourceTime - timeline.origin) * 1_000_000).roundToLong(), 1_000_000u)
    }

    private fun register(kind: MediaKind, file: File, timeline: Timeline? = null): String {
        val prefix = "${kind.name}:"
        files.keys.filter { it.startsWith(prefix) }.forEach { files.remove(it) }
        val reference = "$prefix${next++}"
        files[reference] = file
        when (kind) {
            MediaKind.Image -> selectedFrame = null
            MediaKind.Video -> { timelines.clear(); timelines[reference] = timeline ?: Timeline(reference, 0.0) }
            MediaKind.LivePhoto -> Unit
        }
        return reference
    }

    @Composable
    override fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit {
        val currentResult by rememberUpdatedState(onResult)
        val input = remember {
            (document.createElement("input") as HTMLInputElement).apply {
                type = "file"
                accept = if (kind == MediaKind.Video) "video/*" else "image/*"
                id = when (kind) {
                    MediaKind.Image -> "livephoto-image-input"
                    MediaKind.Video -> "livephoto-video-input"
                    MediaKind.LivePhoto -> "livephoto-analysis-input"
                }
                style.display = "none"
            }
        }
        DisposableEffect(input) {
            input.onchange = {
                val file = input.files?.item(0)
                if (file != null) {
                    val reference = register(kind, file)
                    currentResult(reference)
                } else currentResult(null)
                input.value = ""
            }
            document.body!!.appendChild(input)
            onDispose { input.onchange = null; input.remove() }
        }
        return { browserOpenPicker(input) }
    }

    override suspend fun describe(reference: String): SelectedMediaInfo {
        val file = files[reference] ?: error("Selected browser file is no longer available; select it again")
        return SelectedMediaInfo(file.name, browserFileSize(file).toLong())
    }

    private val tasks = BrowserTasks()
    override val supportsTaskCancellation = true
    override fun cancelTask() { tasks.cancel() }

    override suspend fun runTask(task: UiTask): UiTaskResult = runBrowserTask(task, "")

    private suspend fun runBrowserTask(task: UiTask, review: String): UiTaskResult {
        val file = files[task.input] ?: error("请重新选择文件")
        val movie = task.video?.let { files[it] ?: error("请重新选择视频") }
        if (task.kind == UiTaskKind.Create && coverWarning(task.input, task.video) != null)
            return UiTaskResult("生成失败\n视频已变化，请重新选择封面。", true)
        val key = if (task.kind == UiTaskKind.Create) coverPosition(task.input, task.video) else task.time
        val publication = when (task.kind) {
            UiTaskKind.Create -> successMessage
            UiTaskKind.Extract -> videoExportSuccessMessage
            UiTaskKind.Convert -> conversionSuccessMessage
            UiTaskKind.Repair -> repairSuccessMessage
            UiTaskKind.Key -> keySuccessMessage
            else -> ""
        }
        val response = tasks.run(taskOptions(task.kind.name, task.input,
            task.target?.protocol?.value ?: "", task.target?.profile?.value ?: "",
            key?.value?.toString() ?: "", key?.timescale?.toString() ?: "", review = review,
            imageName = task.imageName ?: "", videoName = task.videoName ?: "", publication = publication), file, movie)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (files[task.input] !== file || task.video != null && files[task.video] !== movie)
            return UiTaskResult("已取消处理：所选输入已改变。未发布旧任务输出。", true)
        if (response.failed) return UiTaskResult(taskFailure(task.kind.name, response), true)
        val blob = response.bytes?.let { taskBlob(it, checkNotNull(response.mime)) }
        if (task.kind == UiTaskKind.Split) {
            val image = checkNotNull(blob)
            val video = taskBlob(checkNotNull(response.second), checkNotNull(response.secondMime))
            return UiTaskResult(response.report, saves = listOf(
                UiSaveAction("image") {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    downloadBlob(image, checkNotNull(response.name))
                    "${response.mime}：已发起下载，请检查下载记录；另一文件需单独保存。"
                },
                UiSaveAction("video") {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    downloadBlob(video, checkNotNull(response.secondName))
                    "${response.secondMime}：已发起下载，请检查下载记录；另一文件需单独保存。"
                }))
        }
        if (blob != null) {
            if (task.kind == UiTaskKind.Create) lastResult = blob to checkNotNull(movie)
            downloadBlob(blob, checkNotNull(response.name))
        }
        return UiTaskResult(response.report, name = response.name,
            applyRepair = response.review?.takeIf { it.isNotEmpty() }?.let { token -> { runBrowserTask(task, token) } })
    }

    @Composable
    override fun ImagePreview(reference: String, modifier: Modifier, onScroll: (Float) -> Unit) {
        val file = files[reference] ?: return
        key(reference) {
            HtmlElementView(factory = {
                browserPreviewElement(media.player.imagePreview(file, onScroll)).also {
                    if (reference.startsWith("thumbnail:")) it.setAttribute("alt", "时间轴缩略图")
                }
            }, modifier = modifier.height(160.dp), onRelease = { browserReleasePreview(it) })
        }
    }

    @Composable
    override fun ResultPreview(modifier: Modifier, onScroll: (Float) -> Unit) {
        val result = lastResult ?: return
        key(result) {
            HtmlElementView(factory = {
                browserPreviewElement(media.player.resultPreview(result.first, result.second, onScroll))
            }, modifier = modifier, onRelease = { browserReleasePreview(it) })
        }
    }

    @Composable
    override fun rememberVideoEditor(reference: String?): VideoEditor? {
        if (reference == null) return null
        val file = files[reference] ?: return null
        val timeline = timelines[reference] ?: return null
        val controller = remember(reference) { BrowserEditor(file, timeline) }
        DisposableEffect(controller) { onDispose { controller.dispose() } }
        return controller
    }

    private inner class BrowserEditor(private val file: File, private val timeline: Timeline) : VideoEditor {
        private val state = mutableStateOf(VideoPlayback())
        override val playback: State<VideoPlayback> = state
        private var surface: BrowserPlayer? = null
        private var previewReference: String? = null
        private val thumbnailReferences = mutableListOf<String>()
        override var thumbnail: String? by mutableStateOf(null)
            private set
        private var disposed = false
        private var coreBusy = false
        private var coreCancelled = false
        @Composable
        override fun Player(modifier: Modifier, onScroll: (Float) -> Unit) {
            key(this) {
            HtmlElementView(factory = {
                val callback: (BrowserPlayback) -> Unit = { value ->
                    if (!disposed) state.value = VideoPlayback(value.duration, value.current,
                        value.seeking, value.ready, value.error, value.width, value.height,
                        value.paused, value.volume, value.muted, value.rate, value.fullscreen)
                }
                if (surface == null) surface = media.player.createPlayer(file, callback, onScroll)
                surface!!.element
            }, modifier = modifier)
            }
        }
        override fun seek(seconds: Double) { surface?.seek(seconds) }
        override val canFullscreen: Boolean get() = browserFullscreenEnabled()
        override fun togglePlayback() { surface?.togglePlayback() }
        override fun setVolume(volume: Double) { surface?.setVolume(volume) }
        override fun setMuted(muted: Boolean) { surface?.setMuted(muted) }
        override fun setRate(rate: Double) { surface?.setRate(rate) }
        override fun setFullscreen(fullscreen: Boolean) { surface?.setFullscreen(fullscreen) }
        override suspend fun previewFrame(): String {
            check(surface != null && !disposed) { "播放器不可用" }
            val result = browserAwait(surface!!.capture())
            check(!disposed) { "输入已改变" }
            val file = browserFile(result.blob, "video-cover.jpg", "image/jpeg")
            previewReference?.let { files.remove(it) }
            val reference = "preview:${next++}"
            files[reference] = file
            previewReference = reference
            return reference
        }
        override suspend fun previewTimeline(): List<String> {
            if (thumbnailReferences.isNotEmpty()) return thumbnailReferences.toList()
            // A separate read-only decoder must not seek or reload the active player.
            var ready: BrowserPlayback? = null
            val decoder = media.player.createPlayer(file, { ready = it }, {})
            decoder.element.setAttribute("preload", "auto")
            try {
                kotlinx.coroutines.withTimeout(15_000) {
                    while (ready?.ready != true || ready?.seeking != false) kotlinx.coroutines.delay(30)
                    val duration = ready!!.duration
                    for (index in 0..4) {
                        check(!disposed) { "输入已改变" }
                        decoder.seek(duration * index / 5)
                        while (ready?.seeking != false) kotlinx.coroutines.delay(30)
                        val captured = browserAwait(decoder.capture())
                        check(!disposed) { "输入已改变" }
                        val reference = "thumbnail:${next++}"
                        files[reference] = browserFile(captured.blob, "timeline.jpg", "image/jpeg")
                        thumbnailReferences += reference
                    }
                }
                thumbnail = thumbnailReferences.firstOrNull()
                return thumbnailReferences.toList()
            } catch (error: Exception) {
                thumbnailReferences.forEach { files.remove(it) }; thumbnailReferences.clear()
                throw error
            } finally { decoder.dispose() }
        }
        override suspend fun process(action: CoreMediaAction, start: Time, end: Time): CoreMediaEdit {
            check(!disposed && !coreBusy) { "已有媒体任务或输入已改变" }
            coreBusy = true; coreCancelled = false
            try {
                check(browserFileSize(file) <= livephoto.core.ffmpeg.BROWSER_MEDIA_INPUT_LIMIT) { "媒体预处理输入超过 512 MiB" }
                check(state.value.duration.isFinite() && state.value.duration > 0 && state.value.duration <= 60) { "Core 媒体工具仅限 60 秒内视频" }
                val prepared = tasks.run(taskOptions(action.name, timeline.root, time = start.value.toString(), scale = start.timescale.toString(),
                    end = end.value.toString(), endScale = end.timescale.toString()), file)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                check(!disposed && !coreCancelled) { "媒体操作已取消；输入未替换" }
                check(!prepared.failed) { taskFailure(action.name, prepared) }
                val bytes = prepared.bytes ?: return CoreMediaEdit(prepared.report)
                val mime = checkNotNull(prepared.mime)
                val name = if (action == CoreMediaAction.ExtractFrame) "core-frame.jpg" else "core-${action.name.lowercase()}.mp4"
                val output = browserFile(taskBlob(bytes, mime), name, mime)
                return if (action == CoreMediaAction.ExtractFrame) {
                    val actual = Time(checkNotNull(prepared.time).toLong(), checkNotNull(prepared.scale).toUInt())
                    val reference = register(MediaKind.Image, output)
                    selectedFrame = Frame(reference, timeline.root, timeline.origin + actual.value.toDouble() / actual.timescale.toDouble())
                    CoreMediaEdit(prepared.report, imageReference = reference)
                } else {
                    val window = prepared.windowScale?.takeIf { it.isNotEmpty() }?.let { scale ->
                        TimeRange(Time(checkNotNull(prepared.start).toLong(), scale.toUInt()), Time(checkNotNull(prepared.end).toLong(), scale.toUInt()))
                    }
                    val mapped = window?.let {
                        val frame = selectedFrame?.takeIf { selected -> selected.root == timeline.root }
                        if (frame != null) {
                            val relative = Time(((frame.sourceTime - timeline.origin) * 1_000_000).roundToLong(), 1_000_000u)
                            check(relative in it) { "KEY_PHOTO_OUTSIDE_TRIM：封面不在实际保留范围内；未替换输入，请重新选择封面。" }
                        }
                        Timeline(timeline.root, timeline.origin + it.start.value.toDouble() / it.start.timescale.toDouble())
                    }
                    // No validated unit-rate video map: keep rejecting stale covers.
                    val reference = register(MediaKind.Video, output, mapped)
                    CoreMediaEdit(prepared.report, video = EditedVideo(reference, prepared.report))
                }
            } finally { coreBusy = false }
        }
        override fun cancel() { coreCancelled = true; if (coreBusy) tasks.cancel() }
        fun dispose() {
            disposed = true; cancel(); previewReference?.let { files.remove(it) }
            thumbnailReferences.forEach { files.remove(it) }; thumbnailReferences.clear()
            surface?.dispose(); surface = null
        }
    }

    override fun formatFailure(error: Exception, imageName: String?, videoName: String?): String =
        "生成失败\n浏览器文件读取或下载失败：${error.message?.take(2000) ?: "未知错误"}\n请重新选择文件；原片不会被覆盖。"
}
