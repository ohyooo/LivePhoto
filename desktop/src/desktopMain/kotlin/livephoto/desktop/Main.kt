package livephoto.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.launch
import livephoto.ui.*

fun main(args: Array<String>) {
    // Shared popups and scroll clipping must also cover the embedded playback view.
    System.setProperty("compose.interop.blending", "true")
    if (args.contentEquals(arrayOf("--help"))) { println("LivePhoto Desktop\nUsage: LivePhoto [--ffmpeg /absolute/path]\nFFmpeg can also be selected from the File menu."); return }
    if (args.isNotEmpty() && (args.size != 2 || args[0] != "--ffmpeg")) { System.err.println("Unsupported arguments. Use --help."); return }
    val explicit = args.getOrNull(1) ?: System.getenv("LIVEPHOTO_FFMPEG")
    val platform = DesktopPlatform()
    try {
        application(exitProcessOnExit = false) {
            val scope = rememberCoroutineScope()
            var theme by remember { mutableStateOf(ThemeMode.System) }
            val systemDark = isSystemInDarkTheme()
            val dark = when (theme) { ThemeMode.System -> systemDark; ThemeMode.Light -> false; ThemeMode.Dark -> true }
            Window(onCloseRequest = { platform.cancelTask(); exitApplication() }, title = "LivePhoto",
                state = rememberWindowState(width = 1280.dp, height = 840.dp)) {
                SideEffect { platform.owner = window }
                LaunchedEffect(platform) { platform.configureFfmpeg(explicit) }
                MenuBar {
                    Menu("文件") {
                        Item("选择 FFmpeg…", onClick = { scope.launch { chooseFile(window)?.let { platform.configureFfmpeg(it.toString()) } } })
                        Item("重新检查 PATH", onClick = { scope.launch { platform.configureFfmpeg() } })
                        Separator()
                        Item("退出", onClick = { platform.cancelTask(); exitApplication() })
                    }
                }
                MaterialTheme(colorScheme = livePhotoColors(dark), typography = LivePhotoTypography) {
                    Column(Modifier.fillMaxSize()) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(platform.backendName, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        Box(Modifier.weight(1f)) {
                            LivePhotoScreen(platform, dark, themeMode = theme, onThemeSelected = { theme = it })
                        }
                    }
                }
            }
        }
    } finally {
        platform.close()
        try { javafx.application.Platform.exit() } catch (_: IllegalStateException) { }
    }
}
