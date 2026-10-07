package livephoto.core.jvm

import livephoto.core.Context
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class ProcessResult(val code: Int?, val output: String, val outputLimited: Boolean = false,
    val cancelled: Boolean = false, val timedOut: Boolean = false, val ioFailed: Boolean = false)

/** Argument vector only. Drain bounded output concurrently so a noisy process cannot deadlock. */
internal object ExternalProcess {
    fun run(arguments: List<String>, timeoutMillis: Long, context: Context? = null): ProcessResult {
        require(arguments.isNotEmpty() && timeoutMillis > 0) { "An executable and positive runtime limit are required" }
        if (context?.cancellation?.isCancelled() == true) return ProcessResult(null, "", cancelled = true)
        val process = try { ProcessBuilder(arguments).redirectErrorStream(true).start() }
        catch (_: Exception) { return ProcessResult(null, "") }
        val output = ByteArrayOutputStream()
        val limited = AtomicBoolean(false)
        val readFailed = AtomicBoolean(false)
        val reader = Thread {
            try {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val retained = minOf(count, 65_536 - output.size())
                            output.write(buffer, 0, retained)
                            if (retained < count) limited.set(true)
                        }
                    }
                }
            } catch (_: Exception) { readFailed.set(true) }
        }.apply { isDaemon = true; name = "livephoto-process-output"; start() }
        var cancelled = false
        var timedOut = false
        var ioFailed = false
        val start = System.nanoTime()
        try {
            process.outputStream.close()
            while (process.isAlive) {
                cancelled = context?.cancellation?.isCancelled() == true
                timedOut = (System.nanoTime() - start) / 1_000_000 >= timeoutMillis
                if (cancelled || timedOut || limited.get()) break
                process.waitFor(50, TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) { cancelled = true; Thread.currentThread().interrupt() }
        catch (_: Exception) { ioFailed = true }
        finally {
            if (process.isAlive) {
                try { process.descendants().use { children -> children.forEach { it.destroyForcibly() } } }
                catch (_: Exception) { ioFailed = true }
                try { process.destroyForcibly() } catch (_: Exception) { ioFailed = true }
                try { process.waitFor(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            try { reader.join(2_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            if (reader.isAlive) {
                ioFailed = true
                try { process.inputStream.close() } catch (_: Exception) { /* Retain failed-drain result. */ }
            }
        }
        ioFailed = ioFailed || process.isAlive || readFailed.get()
        return ProcessResult(if (process.isAlive) null else process.exitValue(), synchronized(output) { output.toString(Charsets.UTF_8) },
            limited.get(), cancelled || context?.cancellation?.isCancelled() == true, timedOut, ioFailed)
    }
}
