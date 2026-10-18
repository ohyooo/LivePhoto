@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package livephoto.ui.browser

import androidx.compose.material3.MaterialTheme
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
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLElement
import org.w3c.files.File
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import kotlin.js.Promise
import kotlin.math.roundToLong
import livephoto.core.*
import livephoto.core.memory.*
import livephoto.ui.*

/** Normalize native JS Promise errors before they cross shared Kotlin UI handlers. */
private suspend fun <T> browserAwait(promise: Promise<T>): T = promise.catch<T> { error ->
    throw IllegalStateException(js("String(error && error.message || error)").unsafeCast<String>().take(2000))
}.await()

public fun main() {
    ComposeViewport(document.getElementById("app")!!, configure = { isA11YEnabled = true }) {
        val platform = remember { BrowserPlatform() }
        val resolver = LocalFontFamilyResolver.current
        var fontsReady by remember { mutableStateOf(false) }
        var fontError by remember { mutableStateOf(false) }
        LaunchedEffect(resolver) {
            try {
                val response = window.fetch("fonts/NotoSansSC-Regular.otf", js("({})")).await()
                check(response.ok) { "Bundled font unavailable" }
                val values = Int8Array(response.arrayBuffer().await())
                val bytes = ByteArray(values.length) { values.asDynamic()[it].unsafeCast<Byte>() }
                resolver.preload(FontFamily(Font("NotoSansSC", bytes)))
                platform.media = window.asDynamic().livePhotoMediaReady.unsafeCast<Promise<dynamic>>().await()
                fontsReady = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { fontError = true }
        }
        MaterialTheme {
            if (fontsReady) LivePhotoScreen(platform)
            else Text(if (fontError) "Unable to load bundled font. Reload the page or check the static server." else "Loading fonts...")
        }
    }
}

private class BrowserPlatform : LivePhotoUiPlatform {
    private val files = mutableMapOf<String, File>()
    var media: dynamic = null
    private data class Timeline(val root: String, val origin: Double)
    private data class Frame(val image: String, val root: String, val sourceTime: Double)
    private val timelines = mutableMapOf<String, Timeline>()
    private var selectedFrame by mutableStateOf<Frame?>(null)
    private var lastResult by mutableStateOf<Pair<Blob, Blob>?>(null)
    private var next = 0
    override val outputDescription = "浏览器下载"
    override val supportsVideoEditing = true
    override val hasResultPreview: Boolean get() = lastResult != null
    override val successMessage = "已生成并发起浏览器下载，请检查下载记录。"

    private fun register(kind: MediaKind, file: File, timeline: Timeline? = null): String {
        val prefix = "${kind.name}:"
        files.keys.filter { it.startsWith(prefix) }.forEach { files.remove(it) }
        val reference = "$prefix${next++}"
        files[reference] = file
        if (kind == MediaKind.Image) selectedFrame = null
        else { timelines.clear(); timelines[reference] = timeline ?: Timeline(reference, 0.0) }
        return reference
    }

    @Composable
    override fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit {
        val currentResult by rememberUpdatedState(onResult)
        val input = remember {
            (document.createElement("input") as HTMLInputElement).apply {
                type = "file"
                accept = if (kind == MediaKind.Image) "image/*" else "video/*"
                id = if (kind == MediaKind.Image) "livephoto-image-input" else "livephoto-video-input"
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
                Unit
            }
            document.body!!.appendChild(input)
            onDispose { input.onchange = null; input.remove() }
        }
        return { input.click() }
    }

    override suspend fun describe(reference: String): SelectedMediaInfo {
        val file = files[reference] ?: error("Selected browser file is no longer available; select it again")
        return SelectedMediaInfo(file.name, file.size.toLong())
    }

    override suspend fun create(image: String, video: String, target: ProtocolSelector): CoreResult<CreatedPhoto> {
        val photo = files[image] ?: error("Select the image again")
        val movie = files[video] ?: error("Select the video again")
        val frame = selectedFrame?.takeIf { it.image == image }
        val timeline = timelines[video]
        if (frame != null && (timeline == null || frame.root != timeline.root || frame.sourceTime < timeline.origin))
            return CoreResult.Failure(CoreError(IssueCode("INVALID_ARGUMENT"), Stage.Plan,
                "视频范围或编码已改变，请在当前视频上重新选择封面；不会猜测旧封面的时间。"))
        val key = frame?.let { CoverPosition.Timestamp(Time(((it.sourceTime - timeline!!.origin) * 1_000_000).roundToLong(), 1_000_000u),
            Selection.Nearest, Time(1, 1u)) }
        if (photo.size.toLong() + movie.size.toLong() > 32L * 1024 * 1024) return CoreResult.Failure(CoreError(
            IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read, "Combined browser inputs exceed 32 MiB"))
        suspend fun bytes(file: File): ByteArray {
            val buffer = file.asDynamic().arrayBuffer().unsafeCast<Promise<ArrayBuffer>>().await()
            val values = Int8Array(buffer)
            return ByteArray(values.length) { values.asDynamic()[it].unsafeCast<Byte>() }
        }
        val ctx = Context(Limits(32uL * 1024u * 1024u, 48uL * 1024u * 1024u))
        val imageSource = MemoryBinarySource(Bytes(bytes(photo)), SourceId("browser-image"))
        val videoSource = MemoryBinarySource(Bytes(bytes(movie)), SourceId("browser-video"))
        val output = MemoryOutputTransaction(ctx, "browser-create")
        var created: OperationResult? = null
        try {
            val policy = if (target.protocol == ProtocolIds.Oplus) MutationPolicy(loss = LossPolicy.AllowListed,
                allowedLosses = listOf(MetadataLoss.OplusCameraUserComment, MetadataLoss.OplusAsciiCameraUserComment)) else MutationPolicy()
            return when (val result = DefaultLivePhotoCore().create(CreateRequest(imageSource, videoSource, target,
                edits = key?.let { EditSpec(keyPosition = it) }, policy = policy, output = output, context = ctx))) {
                is CoreResult.Failure -> result
                is CoreResult.Success -> {
                    created = result.value
                    val asset = result.value.output.assets.single()
                    val blob = Blob(arrayOf(output.committedAssets().getValue(asset.id).toByteArray()), BlobPropertyBag(type = asset.mime))
                    lastResult = blob to movie
                    val url = window.asDynamic().URL.createObjectURL(blob).unsafeCast<String>()
                    try {
                        val link = (document.createElement("a") as HTMLAnchorElement).apply {
                            href = url
                            download = "LivePhoto-${target.protocol.value}.jpg"
                        }
                        document.body!!.appendChild(link)
                        try { link.click() } finally { link.remove() }
                    } finally { window.setTimeout({ window.asDynamic().URL.revokeObjectURL(url) }, 60_000) }
                    val operation = result.value.copy(output = MediaOutput(
                        listOf(asset.copy(readableSource = null, accessReference = "browser-download:${asset.id.value}")), result.value.output.receipt))
                    CoreResult.Success(CreatedPhoto(operation))
                }
            }
        } finally {
            created?.output?.assets?.forEach { it.readableSource?.close() }
            imageSource.close(); videoSource.close()
        }
    }

    @Composable
    override fun ImagePreview(reference: String, modifier: Modifier, onScroll: (Float) -> Unit) {
        val file = files[reference] ?: return
        key(reference) {
            HtmlElementView(factory = {
                val preview = media[0].imagePreview(file, onScroll)
                val element = preview.element.unsafeCast<HTMLElement>()
                element.asDynamic().releasePreview = preview.dispose
                element
            }, modifier = modifier.height(160.dp), onRelease = { it.asDynamic().releasePreview() })
        }
    }

    @Composable
    override fun ResultPreview(modifier: Modifier, onScroll: (Float) -> Unit) {
        val result = lastResult ?: return
        key(result) {
            HtmlElementView(factory = {
                val preview = media[0].resultPreview(result.first, result.second, onScroll)
                val element = preview.element.unsafeCast<HTMLElement>()
                element.asDynamic().releasePreview = preview.dispose
                element
            }, modifier = modifier, onRelease = { it.asDynamic().releasePreview() })
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
        override val coverTime: Double? get() = selectedFrame?.takeIf { it.root == timeline.root }
            ?.let { (it.sourceTime - timeline.origin).takeIf { time -> time >= 0 && time < state.value.duration } }
        private var surface: dynamic = null
        private var job: dynamic = null
        private var disposed = false
        @Composable
        override fun Player(modifier: Modifier, onScroll: (Float) -> Unit) {
            key(this) {
            HtmlElementView(factory = {
                val callback: (dynamic) -> Unit = { value ->
                    if (!disposed) state.value = VideoPlayback(value.duration.unsafeCast<Double>(), value.current.unsafeCast<Double>(),
                        value.seeking.unsafeCast<Boolean>(), value.ready.unsafeCast<Boolean>(), value.error.unsafeCast<String?>(),
                        value.width.unsafeCast<Double>(), value.height.unsafeCast<Double>(),
                        value.paused.unsafeCast<Boolean>(), value.volume.unsafeCast<Double>(), value.muted.unsafeCast<Boolean>(),
                        value.rate.unsafeCast<Double>(), value.fullscreen.unsafeCast<Boolean>())
                }
                if (surface == null) surface = media[0].createPlayer(file, callback, onScroll)
                surface.element.unsafeCast<HTMLElement>()
            }, modifier = modifier)
            }
        }
        override fun seek(seconds: Double) { surface?.seek(seconds) }
        override val canFullscreen: Boolean get() = document.asDynamic().fullscreenEnabled.unsafeCast<Boolean>()
        override fun togglePlayback() { surface?.togglePlayback() }
        override fun setVolume(volume: Double) { surface?.setVolume(volume) }
        override fun setMuted(muted: Boolean) { surface?.setMuted(muted) }
        override fun setRate(rate: Double) { surface?.setRate(rate) }
        override fun setFullscreen(fullscreen: Boolean) { surface?.setFullscreen(fullscreen) }
        override suspend fun capture(): String {
            check(surface != null && !disposed) { "播放器不可用" }
            val result = browserAwait(surface.capture().unsafeCast<Promise<dynamic>>())
            check(!disposed) { "输入已改变" }
            val blob = result.blob.unsafeCast<Blob>()
            val file = js("new File([blob], 'video-cover.jpg', {type:'image/jpeg'})").unsafeCast<File>()
            val reference = register(MediaKind.Image, file)
            selectedFrame = Frame(reference, timeline.root, timeline.origin + result.time.unsafeCast<Double>())
            return reference
        }
        override suspend fun process(start: Double, end: Double, transcodeVideo: Boolean): EditedVideo {
            check(!disposed && job == null) { "已有媒体任务或输入已改变" }
            val options = js("({})")
            options.start = start; options.end = end; options.transcodeVideo = transcodeVideo
            job = media[1].startVideoEdit(file, options)
            try {
                val result = browserAwait(job.promise.unsafeCast<Promise<dynamic>>())
                check(!disposed) { "输入已改变" }
                val blob = result.blob.unsafeCast<Blob>()
                val name = if (transcodeVideo) "transcoded-H264.mp4" else "trimmed-video.mp4"
                val editedFile = js("new File([blob], name, {type:'video/mp4'})").unsafeCast<File>()
                val known = result.timelineKnown.unsafeCast<Boolean>()
                val mapped = if (known) Timeline(timeline.root, timeline.origin + result.origin.unsafeCast<Double>()) else null
                val reference = register(MediaKind.Video, editedFile, mapped)
                val summary = if (known) "copy 裁剪完成；请求 ${mediaSeconds(start)}～${mediaSeconds(end)}，实际保留的视频帧 ${mediaSeconds(result.actualStart.unsafeCast<Double>())}～${mediaSeconds(result.actualEnd.unsafeCast<Double>())}。音视频编码数据包已核对。" else
                    "显式 H.264 转码完成，音频数据包 copy 已核对；未证明原始时间映射，请在新视频上重新选择封面。"
                return EditedVideo(reference, summary + " 普通 metadata 保留不作完整保证。")
            } finally { job = null }
        }
        override fun cancel() { job?.cancel() }
        fun dispose() { disposed = true; cancel(); surface?.dispose(); surface = null }
    }

    override fun formatFailure(error: Exception, imageName: String?, videoName: String?): String =
        "生成失败\n浏览器文件读取或下载失败：${error.message?.take(2000) ?: "未知错误"}\n请重新选择文件；原片不会被覆盖。"
}
