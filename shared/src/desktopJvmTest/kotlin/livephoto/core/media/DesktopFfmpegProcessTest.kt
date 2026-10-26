package livephoto.core.media

import kotlinx.coroutines.*
import livephoto.adapters.files.NativeFfmpeg
import livephoto.core.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class DesktopFfmpegProcessTest {
    @Test fun timedOutAndCancelledFfmpegJobsAreReapedWithoutLosingDiscoveryFallback() = runBlocking {
        val root = Files.createTempDirectory("livephoto-jvm-process-")
        try {
            val executable = assertNotNull(System.getenv("LIVEPHOTO_FFMPEG"))
            val runtime = NativeFfmpeg({ root.toString() })
            val fallback = runtime.discover(root.resolve("missing-ffmpeg").toString(), java.nio.file.Path.of(executable).parent.toString())
            assertNotNull(fallback.backend)
            assertTrue(fallback.issues.any { it.code.value == "FFMPEG_EXPLICIT_PATH_UNAVAILABLE" })
            val args = listOf(executable, "-nostdin", "-hide_banner", "-loglevel", "error", "-re", "-f", "lavfi", "-i", "color=size=64x64:rate=10", "-t", "10", "-f", "null", "-")
            val timedOut = runtime.execute(args, 1000, Context(Limits(0uL, 0uL)))
            assertTrue(timedOut.timedOut)
            assertFalse(timedOut.ioFailed)
            val stop = AtomicBoolean(false)
            val job = async { runtime.execute(args, 10000, Context(Limits(0uL, 0uL), cancellation = Cancellation { stop.get() })) }
            delay(300); stop.set(true)
            val cancelled = withTimeout(5000) { job.await() }
            assertTrue(cancelled.cancelled)
            assertFalse(cancelled.ioFailed)
        } finally { Files.delete(root) }
    }
}
