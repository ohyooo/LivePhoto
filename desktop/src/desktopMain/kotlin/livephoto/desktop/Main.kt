package livephoto.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.AwtWindow
import androidx.compose.ui.window.application
import kotlinx.coroutines.launch
import livephoto.ui.*
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*

internal fun configureDesktopRendering() { System.setProperty("compose.interop.blending", "true") }

/** Swing graphics supports video blending/clipping on Linux as well as Metal/Direct3D hosts. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun desktopWindow(title: String, width: Int = 1280, height: Int = 840, content: @Composable () -> Unit): JFrame {
    val panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
    return JFrame(title).apply {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        contentPane = panel
        val usable = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        setBounds(usable.x, usable.y, minOf(width, usable.width), minOf(height, usable.height))
        panel.setContent(content = content)
    }
}

fun main(args: Array<String>) {
    configureDesktopRendering()
    if (args.contentEquals(arrayOf("--help"))) { println("LivePhoto Desktop\nUsage: LivePhoto [--ffmpeg /absolute/path]\nFFmpeg can also be selected from the File menu."); return }
    if (args.isNotEmpty() && (args.size != 2 || args[0] != "--ffmpeg")) { System.err.println("Unsupported arguments. Use --help."); return }
    val explicit = args.getOrNull(1) ?: System.getenv("LIVEPHOTO_FFMPEG")
    val platform = DesktopPlatform()
    try {
        application(exitProcessOnExit = false) {
            val scope = rememberCoroutineScope()
            AwtWindow(create = {
                desktopWindow("LivePhoto") { DesktopContent(platform, explicit) }.apply {
                    platform.owner = this
                    fun close() { platform.cancelTask(); exitApplication() }
                    addWindowListener(object : WindowAdapter() { override fun windowClosing(event: WindowEvent) { close() } })
                    val frame = this
                    jMenuBar = JMenuBar().apply {
                        add(JMenu("文件").apply {
                            add(JMenuItem("选择 FFmpeg…").apply { addActionListener { scope.launch { chooseFile(frame)?.let { platform.configureFfmpeg(it.toString()) } } } })
                            add(JMenuItem("重新检查 PATH").apply { addActionListener { scope.launch { platform.configureFfmpeg() } } })
                            addSeparator()
                            add(JMenuItem("退出").apply { addActionListener { close() } })
                        })
                    }
                }
            }, dispose = { it.dispose() })
        }
    } finally {
        platform.close()
        try { javafx.application.Platform.exit() } catch (_: IllegalStateException) { }
    }
}

@Composable
private fun DesktopContent(platform: DesktopPlatform, explicit: String?) {
    var theme by remember { mutableStateOf(ThemeMode.System) }
    val systemDark = isSystemInDarkTheme()
    val dark = when (theme) { ThemeMode.System -> systemDark; ThemeMode.Light -> false; ThemeMode.Dark -> true }
    LaunchedEffect(platform) { platform.configureFfmpeg(explicit) }
    MaterialTheme(colorScheme = livePhotoColors(dark), typography = LivePhotoTypography) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(platform.backendName, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.weight(1f)) { LivePhotoScreen(platform, dark, themeMode = theme, onThemeSelected = { theme = it }) }
        }
    }
}
