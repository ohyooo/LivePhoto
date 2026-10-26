package livephoto.adapters.files

import kotlinx.coroutines.*
import livephoto.core.Context
import livephoto.core.ffmpeg.FfmpegProcessResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Trusted user-installed FFmpeg, argv only; diagnostic pipes are drained off the UI dispatcher. */
internal actual suspend fun executeFileFfmpeg(arguments: List<String>, timeoutMillis: Long, context: Context): FfmpegProcessResult = withContext(Dispatchers.IO) {
    require(arguments.isNotEmpty() && arguments.size <= 4096 && arguments.none { '\u0000' in it })
    require(java.io.File(arguments.first()).isAbsolute && timeoutMillis in 1..3_600_000)
    if (context.cancellation?.isCancelled() == true) return@withContext FfmpegProcessResult(null, "", cancelled = true)
    val process = try { ProcessBuilder(arguments).redirectErrorStream(true).start() }
    catch (_: Exception) { return@withContext FfmpegProcessResult(null, "", ioFailed = true) }
    try { process.outputStream.close() } catch (_: Exception) {
        process.destroyForcibly(); process.inputStream.close()
        return@withContext FfmpegProcessResult(null, "", ioFailed = true)
    }
    val diagnostics = ByteArrayOutputStream(65536)
    val limited = AtomicBoolean(false)
    val terminated = AtomicBoolean(false)
    val failed = AtomicBoolean(false)
    var cancelled = false
    var timedOut = false
    val reader = async(Dispatchers.IO) {
        try {
            val buffer = ByteArray(4096)
            while (true) {
                val count = process.inputStream.read(buffer)
                if (count < 0) break
                val keep = minOf(count, 65536 - diagnostics.size())
                diagnostics.write(buffer, 0, keep)
                if (keep < count) { limited.set(true); process.destroyForcibly(); break }
            }
        } catch (_: Exception) { if (!terminated.get()) failed.set(true) }
    }
    try {
        withTimeout(timeoutMillis) {
            while (process.isAlive) {
                ensureActive()
                if (context.cancellation?.isCancelled() == true) { cancelled = true; break }
                delay(10)
            }
            if (!cancelled) reader.await()
        }
    } catch (_: TimeoutCancellationException) { timedOut = true }
    finally {
        withContext(NonCancellable) {
            terminated.set(true)
            process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
            if (process.isAlive) process.destroyForcibly()
            if (!process.waitFor(2, TimeUnit.SECONDS)) failed.set(true)
            process.inputStream.close()
            reader.cancelAndJoin()
        }
    }
    FfmpegProcessResult(if (process.isAlive) null else process.exitValue(), diagnostics.toByteArray().decodeToString(),
        outputLimited = limited.get(), cancelled = cancelled, timedOut = timedOut, ioFailed = failed.get())
}
