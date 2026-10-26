package livephoto.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.runBlocking
import livephoto.core.*
import livephoto.ui.CoreMediaAction
import java.awt.EventQueue
import java.awt.Robot
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopMediaTest {
    @Test fun localFfmpegFeedsSharedTasksAndAnActualVideoWindow() = runBlocking {
        val directory = Files.createTempDirectory("LivePhoto-desktop-test-")
        val video = directory.resolve("video.mp4")
        val image = directory.resolve("video.jpg")
        val output = directory.resolve("result.jpg")
        val ffmpeg = assertNotNull(System.getenv("LIVEPHOTO_FFMPEG"))
        fun execute(vararg args: String) {
            val process = ProcessBuilder(listOf(ffmpeg, "-nostdin", "-n", "-hide_banner", "-loglevel", "error") + args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            try { assertTrue(process.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()) }
            finally { if (process.isAlive) process.destroyForcibly() }
        }
        var platform: DesktopPlatform? = null
        var editor: DesktopVideoEditor? = null
        var window: ComposeWindow? = null
        try {
            execute("-f", "lavfi", "-i", "color=c=red:size=320x180:rate=25", "-t", "2", "-c:v", "libx264", "-g", "1", "-bf", "0", "-pix_fmt", "yuv420p", "-color_range", "tv", "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-map_metadata", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", video.toString())
            execute("-i", video.toString(), "-frames:v", "1", image.toString())
            val host = DesktopPlatform { _, name -> if (name == null) null else output }
            platform = host
            host.configureFfmpeg(ffmpeg)
            assertTrue(host.supportsVideoEditing)
            val imageRef = host.register(image)
            val videoRef = host.register(video)
            val probe = host.processMedia(videoRef, CoreMediaAction.Probe, Time.Zero, Time.Zero)
            assertTrue(probe.summary.isNotBlank())
            val frame = host.previewFrame(videoRef, Time(1, 1u))
            assertTrue(host.describe(frame).sizeBytes!! > 0)
            val create = host.create(imageRef, videoRef, ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail")))
            assertIs<CoreResult.Success<*>>(create)
            assertTrue(Files.size(output) > Files.size(video))
            assertIs<CoreResult.Success<*>>(host.analyze(host.register(output)))
            Files.write(video, byteArrayOf(1, 2, 3))
            val changed = assertIs<CoreResult.Failure>(host.analyze(videoRef))
            assertEquals("SOURCE_CHANGED", changed.error.code.value)
            Files.delete(video)
            execute("-f", "lavfi", "-i", "color=c=red:size=320x180:rate=25", "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-color_range", "tv", "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-map_metadata", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", video.toString())
            System.setProperty("compose.interop.blending", "true")
            EventQueue.invokeAndWait {
                val playback = DesktopVideoEditor(host.register(video), video, host)
                editor = playback
                window = ComposeWindow().apply {
                    title = "LivePhoto CI playback"
                    setSize(640, 420)
                    setLocation(60, 60)
                    setContent { playback.Player(Modifier.fillMaxSize()) {} }
                    isVisible = true
                }
            }
            val playback = editor!!
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!playback.playback.value.ready && playback.playback.value.error == null && System.nanoTime() < deadline) Thread.sleep(50)
            assertNull(playback.playback.value.error)
            assertTrue(playback.playback.value.ready, "JavaFX did not load the synthetic H.264 video")
            playback.setMuted(true); playback.togglePlayback()
            val robot = Robot()
            var redPixels = 0
            var screenshot: java.awt.image.BufferedImage? = null
            while (redPixels < 5000 && System.nanoTime() < deadline) {
                Thread.sleep(100)
                val bounds = Rectangle(60, 60, 640, 420)
                screenshot = robot.createScreenCapture(bounds)
                redPixels = 0
                for (y in 80 until 340 step 2) for (x in 40 until 600 step 2) {
                    val rgb = screenshot.getRGB(x, y)
                    if ((rgb shr 16 and 255) > 160 && (rgb shr 8 and 255) < 80 && (rgb and 255) < 80) redPixels++
                }
            }
            val report = Path.of("build/reports/desktop-smoke")
            Files.createDirectories(report)
            screenshot?.let { ImageIO.write(it, "png", report.resolve("video-window.png").toFile()) }
            assertTrue(redPixels > 5000, "Native video is blank/occluded in the real Compose window: $redPixels red pixels")
            playback.seek(.8)
            assertTrue(playback.playback.value.duration in 1.9..2.1)
        } finally {
            EventQueue.invokeAndWait { editor?.close(); window?.dispose() }
            platform?.close()
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
