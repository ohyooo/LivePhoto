package livephoto.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.layout.StackPane
import javafx.scene.Scene
import javafx.scene.media.Media
import javafx.scene.image.Image as FxImage
import javafx.scene.image.ImageView
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaException
import javafx.scene.media.MediaView
import javafx.util.Duration
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import livephoto.core.Time
import livephoto.ui.*
import java.nio.file.Path
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.roundToLong

/** Playback is a view only. Every exported frame or edited video goes through Core/FFmpeg. */
internal class DesktopVideoEditor(private val reference: String, path: Path, private val owner: DesktopPlatform,
    poster: Path? = null, private val onRetry: (() -> Unit)? = null) : VideoEditor {
    private val state = mutableStateOf(VideoPlayback())
    override val playback: State<VideoPlayback> get() = state
    override val canProcess: Boolean get() = owner.supportsVideoEditing
    override val canRetryPlayback: Boolean get() = !closed && onRetry != null && state.value.error != null
    override fun retryPlayback() {
        if (canRetryPlayback) {
            close()
            onRetry?.invoke()
        }
    }
    private val panel = JFXPanel()
    private val resultPreview = poster != null
    private var player: MediaPlayer? = null
    @Volatile private var closed = false
    private val operationLock = Any()
    private val operationJobs = mutableSetOf<Job>()
    private val operationMutex = Mutex()
    private var previewReference: String? = null
    private var thumbnailReferences = emptyList<String>()
    override var thumbnail: String? by mutableStateOf(null)
        private set
    private val fullscreen = DesktopFullscreen({ owner.owner as? JFrame }, { setFullscreen(false) }) { active ->
        changePlayback { it.copy(fullscreen = active) }
        owner.playerFullscreen = active
    }
    override val canFullscreen: Boolean get() = !resultPreview && owner.owner is JFrame
    private var scroll: (Float) -> Unit = {}
    init {
        panel.addMouseWheelListener { scroll((it.preciseWheelRotation * 40).toFloat()) }
        Platform.runLater {
            Platform.setImplicitExit(false)
            if (!closed) {
                try {
                    val media = Media(path.toUri().toString())
                    val active = MediaPlayer(media)
                    player = active
                    val view = MediaView(active).apply { isPreserveRatio = true; isSmooth = true }
                    val content = StackPane(view)
                    val scene = Scene(content, javafx.scene.paint.Color.BLACK)
                    scene.addEventFilter(KeyEvent.KEY_PRESSED) {
                        if (it.code == KeyCode.ESCAPE && state.value.fullscreen) { setFullscreen(false); it.consume() }
                    }
                    view.fitWidthProperty().bind(scene.widthProperty())
                    view.fitHeightProperty().bind(scene.heightProperty())
                    if (poster == null) view.setOnMouseClicked { togglePlayback() }
                    else {
                        val cover = ImageView(FxImage(poster.toUri().toString(), true)).apply { isPreserveRatio = true; isSmooth = true }
                        cover.fitWidthProperty().bind(scene.widthProperty()); cover.fitHeightProperty().bind(scene.heightProperty())
                        content.children.add(cover); view.isVisible = false
                        var held = false
                        fun pausePreview() { held = false; active.pause(); view.isVisible = false; cover.isVisible = true }
                        fun playPreview() { if (!held && !closed && state.value.ready) { held = true; cover.isVisible = false; view.isVisible = true; active.seek(Duration.ZERO); active.play() } }
                        content.isFocusTraversable = true
                        content.setOnMousePressed { content.requestFocus(); playPreview() }
                        content.setOnMouseReleased { pausePreview() }; content.setOnMouseExited { pausePreview() }
                        content.setOnKeyPressed { if (it.code in setOf(KeyCode.SPACE, KeyCode.ENTER)) { playPreview(); it.consume() } }
                        content.setOnKeyReleased { if (it.code in setOf(KeyCode.SPACE, KeyCode.ENTER)) { pausePreview(); it.consume() } }
                        content.focusedProperty().addListener { _, _, focused -> if (!focused) pausePreview() }
                    }
                    panel.scene = scene
                    fun failed() {
                        val failure = active.error ?: media.error
                        changePlayback { it.copy(ready = false, seeking = false, paused = true,
                            error = "视频预览失败（${playbackFailureDetails(failure)}）") }
                    }
                    media.setOnError { failed() }; active.setOnError { failed() }
                    fun ready() {
                        if (active.error != null || media.error != null) { failed(); return }
                        val duration = active.totalDuration.toSeconds()
                        val valid = duration.isFinite() && duration > 0
                        changePlayback { it.copy(duration = if (valid) duration else 0.0, ready = valid,
                            error = if (valid) null else "本机预览未能读取有效视频时长",
                            seeking = false, width = media.width.toDouble(), height = media.height.toDouble()) }
                    }
                    active.setOnReady { ready() }
                    active.currentTimeProperty().addListener { _, _, value -> changePlayback { it.copy(current = value.toSeconds(), seeking = false) } }
                    active.statusProperty().addListener { _, _, status -> changePlayback { it.copy(paused = it.error != null || status != MediaPlayer.Status.PLAYING) } }
                    active.setOnEndOfMedia { changePlayback { it.copy(paused = true, current = it.duration) } }
                    // Errors may precede installation of the asynchronous callbacks.
                    if (active.error != null || media.error != null) failed()
                    else if (active.status == MediaPlayer.Status.READY) ready()
                } catch (failure: Exception) {
                    player?.dispose(); player = null; panel.scene = null
                    changePlayback { it.copy(ready = false, seeking = false,
                        error = "无法启动本机媒体预览（${playbackFailureDetails(failure)}）") }
                }
            }
        }
    }
    // FX playback callbacks and Swing fullscreen callbacks must not overwrite each other's fields.
    private fun changePlayback(change: (VideoPlayback) -> VideoPlayback) {
        synchronized(state) { if (!closed) state.value = change(state.value) }
    }
    private fun update(action: (MediaPlayer) -> Unit) { Platform.runLater { if (!closed) player?.let(action) } }
    override fun seek(seconds: Double) {
        if (!seconds.isFinite() || !state.value.ready) return
        val position = seconds.coerceIn(0.0, state.value.duration)
        update { it.pause(); changePlayback { value -> value.copy(seeking = position != value.current, paused = true, current = position) }; it.seek(Duration.seconds(position)) }
    }
    override fun togglePlayback() { update { if (state.value.paused) { if (state.value.current >= state.value.duration) it.seek(Duration.ZERO); it.play() } else it.pause() } }
    override fun setVolume(volume: Double) { if (volume.isFinite()) update { player -> player.volume = volume.coerceIn(0.0, 1.0); changePlayback { it.copy(volume = player.volume) } } }
    override fun setMuted(muted: Boolean) { update { player -> player.isMute = muted; changePlayback { it.copy(muted = muted) } } }
    override fun setRate(rate: Double) { if (rate.isFinite() && rate in .25..4.0) update { player -> player.rate = rate; changePlayback { it.copy(rate = rate) } } }
    override fun setFullscreen(fullscreen: Boolean) {
        SwingUtilities.invokeLater {
            if (!closed && (!fullscreen || canFullscreen)) applyFullscreen(fullscreen)
        }
    }
    private fun applyFullscreen(enabled: Boolean) {
        try {
            fullscreen.set(enabled)
            changePlayback { it.copy(presentationWarning = null) }
        } catch (_: Exception) {
            changePlayback { it.copy(presentationWarning = "无法切换全屏，请使用普通窗口预览") }
        }
    }
    private suspend fun <T> operation(action: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        synchronized(operationLock) {
            if (closed) throw CancellationException("输入已改变")
            operationJobs.add(job)
        }
        try { operationMutex.withLock { currentCoroutineContext().ensureActive(); action() } }
        finally { synchronized(operationLock) { operationJobs.remove(job) } }
    }
    override suspend fun previewFrame(): String = previewFrameAt(state.value.current)
    override suspend fun previewFrameAt(seconds: Double): String = operation {
        require(seconds.isFinite() && seconds >= 0 && seconds <= state.value.duration)
        seek(seconds)
        val next = owner.previewFrame(reference, Time((seconds * 1_000_000).roundToLong(), 1_000_000u))
        synchronized(operationLock) {
            if (closed) { owner.release(next); throw CancellationException("输入已改变") }
            previewReference?.let(owner::release)
            previewReference = next
        }
        next
    }
    override suspend fun previewTimeline(): List<String> = operation {
        synchronized(operationLock) { thumbnailReferences.takeIf { it.isNotEmpty() } }?.let { return@operation it }
        val duration = state.value.duration
        require(duration.isFinite() && duration > 0 && duration <= 60) { "缩略图仅限 60 秒内视频" }
        val positions = List(5) { index -> Time((duration * index / 5 * 1_000_000).roundToLong(), 1_000_000u) }
        val next = owner.previewFrames(reference, positions)
        synchronized(operationLock) {
            if (closed) { next.forEach(owner::release); throw CancellationException("输入已改变") }
            thumbnailReferences = next
            thumbnail = next.firstOrNull()
        }
        next
    }
    override suspend fun process(action: CoreMediaAction, start: Time, end: Time): CoreMediaEdit = operation {
        check(canProcess) { "媒体处理后端不可用" }
        owner.processMedia(reference, action, start, end)
    }
    override fun cancel() {
        synchronized(operationLock) { operationJobs.toList() }.forEach { it.cancel() }
    }
    @Composable override fun Player(modifier: Modifier, onScroll: (Float) -> Unit) {
        SideEffect { scroll = onScroll }
        SwingPanel(factory = { panel }, modifier = modifier, background = Color.Black)
    }
    fun close() {
        synchronized(operationLock) {
            if (closed) return
            closed = true
            operationJobs.toList().forEach { it.cancel() }
            thumbnailReferences.forEach(owner::release); thumbnailReferences = emptyList(); thumbnail = null
            previewReference?.let(owner::release); previewReference = null
        }
        SwingUtilities.invokeLater { applyFullscreen(false) }
        Platform.runLater { player?.dispose(); player = null; panel.scene = null }
    }
}

/** Types and media codes only; exception messages may contain the user's media path. */
private fun playbackFailureDetails(error: Throwable?): String =
    generateSequence(error) { it.cause }.take(4).joinToString(" → ") {
        if (it is MediaException) "${it.javaClass.simpleName}/${it.type}" else it.javaClass.simpleName
    }.ifEmpty { "MEDIA_ERROR" }
