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
import javafx.scene.media.MediaView
import javafx.util.Duration
import javafx.scene.input.KeyCode
import livephoto.core.Time
import livephoto.ui.*
import java.nio.file.Path
import kotlin.math.roundToLong

/** Playback is a view only. Every exported frame or edited video goes through Core/FFmpeg. */
internal class DesktopVideoEditor(private val reference: String, path: Path, private val owner: DesktopPlatform, poster: Path? = null) : VideoEditor {
    private val state = mutableStateOf(VideoPlayback())
    override val playback: State<VideoPlayback> get() = state
    private val panel = JFXPanel()
    private var player: MediaPlayer? = null
    @Volatile private var closed = false
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
                    view.fitWidthProperty().bind(scene.widthProperty())
                    view.fitHeightProperty().bind(scene.heightProperty())
                    if (poster == null) view.setOnMouseClicked { togglePlayback() }
                    else {
                        val cover = ImageView(FxImage(poster.toUri().toString(), true)).apply { isPreserveRatio = true; isSmooth = true }
                        cover.fitWidthProperty().bind(scene.widthProperty()); cover.fitHeightProperty().bind(scene.heightProperty())
                        content.children.add(cover); view.isVisible = false
                        var held = false
                        fun pausePreview() { held = false; active.pause(); view.isVisible = false; cover.isVisible = true }
                        fun playPreview() { if (!held) { held = true; cover.isVisible = false; view.isVisible = true; active.seek(Duration.ZERO); active.play() } }
                        content.isFocusTraversable = true
                        content.setOnMousePressed { content.requestFocus(); playPreview() }
                        content.setOnMouseReleased { pausePreview() }; content.setOnMouseExited { pausePreview() }
                        content.setOnKeyPressed { if (it.code in setOf(KeyCode.SPACE, KeyCode.ENTER)) { playPreview(); it.consume() } }
                        content.setOnKeyReleased { if (it.code in setOf(KeyCode.SPACE, KeyCode.ENTER)) { pausePreview(); it.consume() } }
                        content.focusedProperty().addListener { _, _, focused -> if (!focused) pausePreview() }
                    }
                    panel.scene = scene
                    fun failed() { state.value = state.value.copy(ready = false, seeking = false, error = "本机预览不支持此媒体（${active.error?.type ?: media.error?.type ?: "MEDIA_ERROR"}）") }
                    media.setOnError { failed() }; active.setOnError { failed() }
                    active.setOnReady {
                        val duration = active.totalDuration.toSeconds()
                        state.value = state.value.copy(duration = duration, ready = duration.isFinite() && duration > 0,
                            seeking = false, width = media.width.toDouble(), height = media.height.toDouble())
                    }
                    active.currentTimeProperty().addListener { _, _, value -> state.value = state.value.copy(current = value.toSeconds(), seeking = false) }
                    active.statusProperty().addListener { _, _, status -> state.value = state.value.copy(paused = status != MediaPlayer.Status.PLAYING) }
                    active.setOnEndOfMedia { state.value = state.value.copy(paused = true, current = state.value.duration) }
                } catch (_: Exception) { state.value = state.value.copy(seeking = false, error = "无法启动本机媒体预览") }
            }
        }
    }
    private fun update(action: (MediaPlayer) -> Unit) { Platform.runLater { if (!closed) player?.let(action) } }
    override fun seek(seconds: Double) {
        if (!seconds.isFinite() || !state.value.ready) return
        val position = seconds.coerceIn(0.0, state.value.duration)
        update { it.pause(); state.value = state.value.copy(seeking = position != state.value.current, paused = true, current = position); it.seek(Duration.seconds(position)) }
    }
    override fun togglePlayback() { update { if (state.value.paused) { if (state.value.current >= state.value.duration) it.seek(Duration.ZERO); it.play() } else it.pause() } }
    override fun setVolume(volume: Double) { if (volume.isFinite()) update { it.volume = volume.coerceIn(0.0, 1.0); state.value = state.value.copy(volume = it.volume) } }
    override fun setMuted(muted: Boolean) { update { it.isMute = muted; state.value = state.value.copy(muted = muted) } }
    override fun setRate(rate: Double) { if (rate.isFinite() && rate in .25..4.0) update { it.rate = rate; state.value = state.value.copy(rate = rate) } }
    override suspend fun previewFrame(): String = owner.previewFrame(reference, Time((state.value.current * 1_000_000).roundToLong(), 1_000_000u))
    override suspend fun process(action: CoreMediaAction, start: Time, end: Time): CoreMediaEdit = owner.processMedia(reference, action, start, end)
    override fun cancel() { owner.cancelTask() }
    @Composable override fun Player(modifier: Modifier, onScroll: (Float) -> Unit) {
        SideEffect { scroll = onScroll }
        SwingPanel(factory = { panel }, modifier = modifier, background = Color.Black)
    }
    fun close() {
        closed = true
        Platform.runLater { player?.dispose(); player = null; panel.scene = null }
    }
}
