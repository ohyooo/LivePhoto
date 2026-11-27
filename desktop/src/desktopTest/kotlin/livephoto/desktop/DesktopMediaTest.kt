package livephoto.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import javax.swing.JFrame
import kotlinx.coroutines.runBlocking
import livephoto.core.*
import livephoto.ui.CoreMediaAction
import java.awt.EventQueue
import java.awt.Robot
import java.awt.Rectangle
import java.nio.file.FileSystemException
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
        fun generateVideo(prefixFilter: String = "") = execute("-f", "lavfi", "-i", "color=c=red:size=320x180:rate=25", "-t", "2", "-vf", "${prefixFilter}setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709", "-c:v", "libx264", "-g", "1", "-bf", "0", "-pix_fmt", "yuv420p", "-color_range", "tv", "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-map_metadata", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", video.toString())
        var platform: DesktopPlatform? = null
        var editor: DesktopVideoEditor? = null
        var window: JFrame? = null
        var testFailure: Throwable? = null
        try {
            generateVideo()
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
            val thumbnails = host.previewFrames(videoRef, List(5) { Time(it.toLong(), 3u) })
            assertEquals(5, thumbnails.size)
            for (thumbnail in thumbnails) {
                assertTrue(host.describe(thumbnail).sizeBytes!! > 0)
                assertNull(host.coverPosition(thumbnail, videoRef), "Timeline previews must not confirm a cover")
                host.release(thumbnail)
            }
            val create = host.create(imageRef, videoRef, ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail")))
            assertIs<CoreResult.Success<*>>(create)
            assertTrue(Files.size(output) > Files.size(video))
            assertIs<CoreResult.Success<*>>(host.analyze(host.register(output)))
            Files.write(video, byteArrayOf(1, 2, 3))
            val changed = assertIs<CoreResult.Failure>(host.analyze(videoRef))
            assertEquals("SOURCE_CHANGED", changed.error.code.value)
            Files.delete(video)
            // The desktop UI must actually display a distinguishable frame after seeking.
            generateVideo("drawbox=color=lime:t=fill:enable='gte(t,1)',")
            configureDesktopRendering()
            EventQueue.invokeAndWait {
                val playback = DesktopVideoEditor(host.register(video), video, host)
                editor = playback
                window = desktopWindow("LivePhoto CI playback", 640, 420) { playback.Player(Modifier.fillMaxSize()) {} }.apply {
                    host.owner = this
                    setLocation(60, 60); isVisible = true
                }
            }
            val playback = editor!!
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!playback.playback.value.ready && playback.playback.value.error == null && System.nanoTime() < deadline) Thread.sleep(50)
            assertNull(playback.playback.value.error)
            assertTrue(playback.playback.value.ready, "JavaFX did not load the synthetic H.264 video")
            playback.setMuted(true); playback.togglePlayback()
            val robot = Robot()
            val bounds = Rectangle(60, 60, 640, 420)
            val report = Path.of("build/reports/desktop-smoke")
            Files.createDirectories(report)
            fun awaitPicture(name: String, deadline: Long, matches: (Int) -> Boolean, settled: () -> Boolean = { true }) {
                var matching = 0
                do {
                    Thread.sleep(50)
                    val picture = robot.createScreenCapture(bounds)
                    matching = 0
                    for (y in 80 until 340 step 2) for (x in 40 until 600 step 2) {
                        if (matches(picture.getRGB(x, y))) matching++
                    }
                    if (matching > 5000 && settled()) {
                        ImageIO.write(picture, "png", report.resolve(name).toFile())
                        return
                    }
                    if (System.nanoTime() >= deadline || playback.playback.value.error != null) {
                        ImageIO.write(picture, "png", report.resolve(name).toFile())
                        break
                    }
                } while (true)
                fail("Did not render $name with a settled playback clock: $matching matching pixels; ${playback.playback.value}")
            }
            awaitPicture("video-window.png", deadline, { rgb ->
                (rgb shr 16 and 255) > 160 && (rgb shr 8 and 255) < 80 && (rgb and 255) < 80
            })
            assertNull(playback.playback.value.error)
            // Queue two positions. The latest seek should win and pause at the green frame.
            playback.seek(.4)
            playback.seek(1.5)
            awaitPicture("video-seek-window.png", System.nanoTime() + TimeUnit.SECONDS.toNanos(10), { rgb ->
                (rgb shr 16 and 255) < 80 && (rgb shr 8 and 255) > 160 && (rgb and 255) < 80
            }) {
                val state = playback.playback.value
                state.ready && state.paused && !state.seeking && kotlin.math.abs(state.current - 1.5) <= 0.1
            }
            assertTrue(playback.playback.value.duration in 1.9..2.1)
            // Close the app host while the real player is still mounted. The editor's
            // Compose disposal must not be required to release native playback.
            assertTrue(playback.canFullscreen)
            host.close()
            assertFalse(playback.canFullscreen, "Closing the host must close its still-mounted video editor")
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            try {
                platform?.close()
                EventQueue.invokeAndWait { window?.dispose() }
                deleteMediaTestDirectory(directory)
            } catch (cleanupFailure: Exception) {
                val original = testFailure ?: throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
    }
}

private fun deleteMediaTestDirectory(directory: Path) {
    val paths = Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).toList() }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    for (path in paths) {
        while (true) {
            try {
                Files.deleteIfExists(path)
                break
            } catch (failure: FileSystemException) {
                // The FX queue can drain before its native decoder releases a Windows file handle.
                if (System.nanoTime() - deadline >= 0) throw failure
                Thread.sleep(50)
            }
        }
    }
}
