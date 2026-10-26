package livephoto.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.*
import livephoto.adapters.files.FileInputSession
import livephoto.adapters.files.NativeFfmpeg
import livephoto.core.*
import livephoto.core.ffmpeg.BROWSER_MEDIA_INPUT_LIMIT
import livephoto.core.memory.MemoryOutputTransaction
import livephoto.ui.*
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

internal class DesktopCoreFailure(val error: CoreError) : Exception("${error.code.value} / ${error.stage}：${error.message.take(1600)}")
internal class DesktopPlatform(private val picker: suspend (Frame?, String?) -> Path? = ::chooseFile) : LivePhotoUiPlatform, AutoCloseable {
    internal data class Entry(val path: Path, val name: String, var digest: Digest? = null, val owned: Boolean = false)
    private data class Timeline(val root: String, val origin: Double)
    private data class FramePosition(val root: String, val sourceTime: Double)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val timelines = ConcurrentHashMap<String, Timeline>()
    private val frames = ConcurrentHashMap<String, FramePosition>()
    @Volatile private var selectedFrameImage: String? = null
    private var lastResult by mutableStateOf<Pair<String, String>?>(null)
    private val pendingDeletes = ConcurrentHashMap.newKeySet<Path>()
    private val cancellations = ConcurrentHashMap.newKeySet<AtomicBoolean>()
    private val root = Files.createTempDirectory("LivePhoto-")
    @Volatile private var core: LivePhotoCore = DefaultLivePhotoCore()
    var owner: Frame? = null
    var backendName by mutableStateOf("正在检查本地 FFmpeg…")
        private set
    private var mediaAvailable by mutableStateOf(false)
    override val outputDescription: String = "选择的本地文件"
    override val supportsAnalysis: Boolean = true
    override val supportsVideoExtraction: Boolean = true
    override val supportsCleanSplit: Boolean = true
    override val supportsConversion: Boolean = true
    override val supportsRepair: Boolean = true
    override val supportsPhotoKey: Boolean = true
    override val supportsTaskCancellation: Boolean = true
    override val supportsClipboard: Boolean = true
    override val supportsVideoEditing: Boolean get() = mediaAvailable
    override val hasResultPreview: Boolean get() = lastResult != null
    suspend fun configureFfmpeg(explicit: String? = null) = withContext(Dispatchers.IO) {
        val discovered = NativeFfmpeg({ root.toString() }).discover(explicit, System.getenv("PATH").orEmpty())
        core = DefaultLivePhotoCore(discovered.backend)
        mediaAvailable = discovered.backend != null
        backendName = discovered.executable?.let { "本地 FFmpeg：${Path.of(it).fileName}" } ?: "未找到 FFmpeg；图片与视频直接生成仍可用"
    }
    internal fun register(path: Path, name: String = path.fileName.toString(), digest: Digest? = null, owned: Boolean = false): String {
        val reference = UUID.randomUUID().toString()
        entries[reference] = Entry(path, name, digest, owned)
        timelines[reference] = Timeline(reference, 0.0)
        return reference
    }
    @Composable override fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit {
        val scope = rememberCoroutineScope()
        val selected by rememberUpdatedState(onResult)
        return remember(kind) { { scope.launch { val path = picker(owner, null); if (path != null && kind == MediaKind.Image) selectedFrameImage = null; selected(path?.let { register(it) }) }; Unit } }
    }
    override suspend fun describe(reference: String): SelectedMediaInfo = withContext(Dispatchers.IO) {
        val entry = entries.getValue(reference)
        SelectedMediaInfo(entry.name, Files.size(entry.path))
    }
    override fun release(reference: String) {
        entries.remove(reference)?.takeIf { it.owned }?.let { pendingDeletes.add(it.path) }
        frames.remove(reference); timelines.remove(reference); if (selectedFrameImage == reference) selectedFrameImage = null
    }
    private fun discardReleasedFiles() { pendingDeletes.toList().forEach { try { Files.deleteIfExists(it); pendingDeletes.remove(it) } catch (_: Exception) {} } }
    override fun cancelTask() { cancellations.forEach { it.set(true) } }
    override suspend fun copyText(text: String): Boolean = try { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null); true } catch (_: Exception) { false }
    override fun formatFailure(error: Exception, imageName: String?, videoName: String?): String =
        if (error is DesktopCoreFailure) formatCoreFailure(error.error, imageName, videoName) else "操作失败\n未完成保存；请检查所选文件、输出位置和媒体格式。"

    private suspend fun <T> sources(references: List<String>, limit: Int = ANALYSIS_INPUT_LIMIT,
        action: suspend (LivePhotoCore, List<BinarySource>, Context) -> CoreResult<T>): CoreResult<T> = withContext(Dispatchers.IO) {
        discardReleasedFiles()
        val job = currentCoroutineContext()[Job]
        val stop = AtomicBoolean(false); cancellations.add(stop)
        val context = Context(Limits(limit.toULong(), 48uL * 1024u * 1024u), cancellation = Cancellation { stop.get() || job?.isActive == false })
        val session = FileInputSession(root.resolve("input-${UUID.randomUUID()}").toString(), context)
        val selectedCore = core
        try {
            val captured = mutableListOf<BinarySource>()
            for (reference in references) {
                val entry = entries[reference] ?: return@withContext failed("SOURCE_CHANGED", Stage.Read, "Selected media is no longer available")
                val source = when (val result = session.capture(entry.path.toString())) { is CoreResult.Failure -> return@withContext result; is CoreResult.Success -> result.value }
                val identity = when (val result = source.identity()) { is CoreResult.Failure -> return@withContext result; is CoreResult.Success -> result.value }
                synchronized(entry) {
                    if (entry.digest != null && entry.digest != identity.digest) return@withContext failed("SOURCE_CHANGED", Stage.Read, "Selected media changed; select it again")
                    entry.digest = identity.digest
                }
                captured += source
            }
            action(selectedCore, captured, context)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: DesktopCoreFailure) { CoreResult.Failure(failure.error) }
        catch (_: java.nio.file.FileAlreadyExistsException) { failed("OUTPUT_EXISTS", Stage.Publish, "The output exists; choose a new name") }
        catch (_: UnsupportedOperationException) { failed("ATOMIC_PUBLICATION_UNAVAILABLE", Stage.Publish, "This filesystem cannot atomically create the output") }
        catch (_: Exception) { failed("IO_WRITE_FAILED", Stage.Publish, "Unable to read or save the selected media") }
        finally { withContext(NonCancellable) { session.close(); cancellations.remove(stop) } }
    }
    private suspend fun save(bytes: Bytes, digest: Digest, name: String, context: Context): Boolean {
        if (context.cancellation?.isCancelled() == true) throw DesktopCoreFailure(CoreError(IssueCode("CANCELLED"), Stage.Publish, "Save cancelled"))
        val target = picker(owner, name) ?: return false
        publishFile(target, bytes, digest) { context.cancellation?.isCancelled() == true }
        return true
    }
    private fun <T> saveCancelled(): CoreResult<T> = failed("CANCELLED", Stage.Publish, "Save cancelled; no output file was created")
    override suspend fun create(image: String, video: String, target: ProtocolSelector): CoreResult<CreatedPhoto> = sources(listOf(image, video)) { core, input, context ->
        coverWarning(image, video)?.let { return@sources failed("SOURCE_CHANGED", Stage.Plan, it) }
        val output = MemoryOutputTransaction(context, "desktop-create")
        var operation: OperationResult? = null
        try {
            val position = coverPosition(image, video)
            val edits = position?.let { EditSpec(keyPosition = CoverPosition.Timestamp(it, Selection.Nearest, Time(1, 1u))) }
            when (val result = core.create(CreateRequest(input[0], input[1], target, edits = edits, policy = uiCreationPolicy(target), output = output, context = context))) {
                is CoreResult.Failure -> result
                is CoreResult.Success -> {
                    operation = result.value
                    val asset = result.value.output.assets.single()
                    if (!save(output.committedAssets().getValue(asset.id), asset.digest, "LivePhoto-${target.protocol.value}.jpg", context)) saveCancelled()
                    else {
                        val created = output.committedAssets().getValue(asset.id)
                        val carrier = livephoto.core.memory.MemoryBinarySource(created, SourceId("desktop-result"))
                        try {
                            when (val extracted = prepareExtractedVideo(core, carrier)) {
                                is CoreResult.Success -> {
                                    val previous = lastResult
                                    val poster = cache(created, "LivePhoto-result.jpg")
                                    try { lastResult = poster to cache(extracted.value.bytes, "LivePhoto-result.${extracted.value.report.extension}") }
                                    catch (failure: Exception) { release(poster); throw failure }
                                    previous?.let { release(it.first); release(it.second) }
                                }
                                is CoreResult.Failure -> { lastResult?.let { release(it.first); release(it.second) }; lastResult = null }
                            }
                        } catch (_: Exception) {
                            // Saving succeeded; an optional preview must never turn that into a failed save.
                            lastResult?.let { release(it.first); release(it.second) }; lastResult = null
                        } finally { carrier.close() }
                        CoreResult.Success(CreatedPhoto(result.value))
                    }
                }
            }
        } finally { operation?.output?.assets?.forEach { it.readableSource?.close() }; if (operation == null) output.abort() }
    }
    override suspend fun analyze(reference: String): CoreResult<AnalysisResult> = sources(listOf(reference)) { core, input, context -> analyzeSelectedPhoto(core, input.single(), context.cancellation) }
    override suspend fun extractVideo(reference: String): CoreResult<ExtractedVideo> = sources(listOf(reference)) { core, input, context ->
        when (val result = prepareExtractedVideo(core, input.single(), context.cancellation)) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> if (save(result.value.bytes, result.value.report.digest, "LivePhoto-video.${result.value.report.extension}", context)) CoreResult.Success(result.value.report) else saveCancelled()
        }
    }
    override suspend fun cleanSplit(reference: String): CoreResult<PreparedCleanSplit<Bytes>> = sources(listOf(reference)) { core, input, context -> prepareCleanSplit(core, input.single(), context.cancellation) }
    override suspend fun saveSplitFile(file: SplitFile<Bytes>): String = withContext(Dispatchers.IO) {
        val target = picker(owner, "LivePhoto-clean.${file.extension}") ?: return@withContext "保存已取消；未写入文件。"
        val job = currentCoroutineContext()[Job]
        publishFile(target, file.bytes, file.digest) { job?.isActive == false }
        "已保存${if (file.mime == "image/jpeg") "图片" else "视频"}；另一文件需单独保存。"
    }
    override suspend fun convertPhoto(reference: String, target: ProtocolSelector): CoreResult<ConvertedPhoto> = sources(listOf(reference)) { core, input, context ->
        when (val result = prepareConvertedPhoto(core, input.single(), target, context.cancellation)) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> if (save(result.value.bytes, result.value.report.digest, "LivePhoto-converted.jpg", context)) CoreResult.Success(result.value.report) else saveCancelled()
        }
    }
    override suspend fun setPhotoKey(reference: String, requested: Time): CoreResult<PhotoKeyReport> = sources(listOf(reference)) { core, input, context ->
        when (val result = preparePhotoKey(core, input.single(), requested, context.cancellation)) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> if (save(result.value.bytes, result.value.report.digest, "LivePhoto-key.jpg", context)) CoreResult.Success(result.value.report) else saveCancelled()
        }
    }
    override suspend fun repairPhoto(reference: String, review: RepairReview?): CoreResult<PhotoRepairReport> = sources(listOf(reference)) { core, input, context ->
        when (val result = preparePhotoRepair(core, input.single(), review, context.cancellation)) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> if (result.value.bytes == null || save(result.value.bytes!!, result.value.report.outputDigest!!, "LivePhoto-repaired.jpg", context)) CoreResult.Success(result.value.report) else saveCancelled()
        }
    }
    private fun cache(bytes: Bytes, name: String): String {
        val path = root.resolve("${UUID.randomUUID()}-${name}")
        val digest = bytesDigest(bytes)
        publishFile(path, bytes, digest)
        return register(path, name, digest, owned = true)
    }
    internal suspend fun previewFrame(reference: String, requested: Time): String = media(reference, CoreMediaAction.ExtractFrame, requested, Time.Zero, preview = true).imageReference!!
    internal suspend fun processMedia(reference: String, action: CoreMediaAction, start: Time, end: Time): CoreMediaEdit = media(reference, action, start, end, preview = false)
    private suspend fun media(reference: String, action: CoreMediaAction, start: Time, end: Time, preview: Boolean): CoreMediaEdit {
        val result = sources(listOf(reference), BROWSER_MEDIA_INPUT_LIMIT) { core, input, context ->
            when (val result = prepareCoreMedia(core, input.single(), action, start, end, context.cancellation, BROWSER_MEDIA_INPUT_LIMIT)) {
                is CoreResult.Failure -> result
                is CoreResult.Success -> {
                    val prepared = result.value
                    val bytes = prepared.bytes
                    if (bytes == null) CoreResult.Success(CoreMediaEdit(prepared.summary))
                    else {
                        val previous = timelines.getValue(reference)
                        val selected = selectedFrameImage?.let(frames::get)
                        val window = prepared.retainedVideoWindow
                        if (action == CoreMediaAction.Trim && window != null && selected?.root == previous.root) {
                            val point = selected.sourceTime - previous.origin
                            if (point < window.start.value.toDouble() / window.start.timescale.toDouble() || point >= window.end.value.toDouble() / window.end.timescale.toDouble())
                                return@sources failed("COVER_OUTSIDE_TRIM", Stage.Plan, "裁剪范围未包含已确认封面；输入未替换")
                        }
                        val next = cache(bytes, if (prepared.mime == "image/jpeg") "LivePhoto-cover.jpg" else "LivePhoto-${action.name.lowercase()}.mp4")
                        if (action == CoreMediaAction.ExtractFrame) {
                            if (!preview) { val actual = prepared.actualTime!!; frames[next] = FramePosition(previous.root, previous.origin + actual.value.toDouble() / actual.timescale.toDouble()); selectedFrameImage = next }
                            CoreResult.Success(CoreMediaEdit(prepared.summary, imageReference = next))
                        } else {
                            prepared.retainedVideoWindow?.let { window -> timelines[next] = Timeline(previous.root, previous.origin + window.start.value.toDouble() / window.start.timescale.toDouble()) }
                            CoreResult.Success(CoreMediaEdit(prepared.summary, video = EditedVideo(next, prepared.summary)))
                        }
                    }
                }
            }
        }
        return when (result) { is CoreResult.Failure -> throw DesktopCoreFailure(result.error); is CoreResult.Success -> result.value }
    }
    override fun coverWarning(image: String?, video: String?): String? {
        val frame = image?.let(frames::get) ?: return null
        val timeline = video?.let(timelines::get)
        return if (timeline == null || frame.root != timeline.root || frame.sourceTime < timeline.origin) "视频已变化，请重新选择封面" else null
    }
    override fun coverPosition(image: String?, video: String?): Time? {
        val frame = image?.let(frames::get) ?: return null
        val timeline = video?.let(timelines::get) ?: return null
        if (coverWarning(image, video) != null) return null
        return Time(((frame.sourceTime - timeline.origin) * 1_000_000).roundToLong(), 1_000_000u)
    }
    @Composable override fun rememberVideoEditor(reference: String?): VideoEditor? {
        val entry = reference?.let(entries::get)
        val editor = remember(reference, entry) { entry?.let { DesktopVideoEditor(reference!!, it.path, this) } }
        DisposableEffect(editor) { onDispose { editor?.close() } }
        return editor
    }
    @Composable override fun ImagePreview(reference: String, modifier: Modifier, onScroll: (Float) -> Unit) {
        val path = entries[reference]?.path
        val image by produceState<ImageBitmap?>(null, path) {
            value = withContext(Dispatchers.IO) {
                try {
                    if (path == null || Files.size(path) > ANALYSIS_INPUT_LIMIT) null else {
                        val decoded = org.jetbrains.skia.Image.makeFromEncoded(Files.readAllBytes(path))
                        if (decoded.width.toLong() * decoded.height > 64_000_000) { decoded.close(); null } else decoded.toComposeImageBitmap()
                    }
                } catch (_: Exception) { null }
            }
        }
        Box(modifier) { image?.let { Image(it, "图片预览", Modifier.matchParentSize(), contentScale = ContentScale.Fit) } ?: Text("无法预览此图片") }
    }
    @Composable override fun ResultPreview(modifier: Modifier, onScroll: (Float) -> Unit) {
        val result = lastResult
        val poster = result?.first?.let(entries::get)
        val movie = result?.second?.let(entries::get)
        val preview = remember(result) { if (poster == null || movie == null) null else DesktopVideoEditor(result!!.second, movie.path, this, poster.path) }
        DisposableEffect(preview) { onDispose { preview?.close() } }
        preview?.Player(modifier, onScroll)
    }
    override fun close() {
        cancelTask()
        runBlocking { withTimeoutOrNull(5000) { while (cancellations.isNotEmpty()) delay(10) } }
        try {
            val released = java.util.concurrent.CountDownLatch(1)
            javafx.application.Platform.runLater { released.countDown() }
            released.await(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: IllegalStateException) { }
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { try { Files.deleteIfExists(it) } catch (_: Exception) {} } }
    }
}
private fun <T> failed(code: String, stage: Stage, message: String): CoreResult<T> = CoreResult.Failure(CoreError(IssueCode(code), stage, message))
