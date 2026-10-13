package livephoto.adapters.process

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

public enum class ProcessStatus { Exited, StartFailed, TimedOut, Cancelled, OutputLimit, IoFailed }

/** Bounded merged stdout/stderr for adapter verification, never automatically logged. */
public data class ProcessResult(
    public val status: ProcessStatus,
    public val exitCode: Int?,
    public val diagnostics: String,
)

/**
 * Executes an absolute executable with argv, no shell, closed stdin, inherited
 * environment and a private process group/job. OS pipe reads are nonblocking;
 * delay yields while awaiting data/exit. The caller owns diagnostic redaction.
 * Coroutine cancellation propagates only after terminating/reaping the child.
 */
public object NativeProcess {
    public suspend fun run(
        arguments: List<String>,
        timeoutMillis: Long = 600_000,
        maxDiagnosticBytes: Int = 65_536,
        cancelled: () -> Boolean = { false },
    ): ProcessResult {
        require(arguments.isNotEmpty() && arguments.size <= 4096)
        require(arguments.all { '\u0000' !in it } && arguments.sumOf { it.length.toLong() } <= 131_072)
        require(timeoutMillis in 1..3_600_000 && maxDiagnosticBytes in 1..65_536)
        val argv = arguments.toList()
        require(isAbsoluteExecutable(argv.first())) { "An absolute executable path is required" }
        currentCoroutineContext().ensureActive()
        if (cancelled()) return ProcessResult(ProcessStatus.Cancelled, null, "")
        val child = startProcess(argv) ?: return ProcessResult(ProcessStatus.StartFailed, null, "")
        val retained = ByteArray(maxDiagnosticBytes)
        val buffer = ByteArray(4096)
        var length = 0
        var status = ProcessStatus.Exited
        var code: Int? = null
        var eof = false
        var cleaned = true
        val start = TimeSource.Monotonic.markNow()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (cancelled()) { status = ProcessStatus.Cancelled; break }
                if (start.elapsedNow().inWholeMilliseconds >= timeoutMillis) {
                    status = ProcessStatus.TimedOut; break
                }
                // A noisy child cannot monopolize the coroutine or hide timeout.
                repeat(16) {
                    if (!eof && status == ProcessStatus.Exited) {
                        val count = child.read(buffer)
                        when {
                            count == -1 -> eof = true
                            count < -1 -> status = ProcessStatus.IoFailed
                            count > 0 -> {
                                val keep = minOf(count, retained.size - length)
                                buffer.copyInto(retained, length, 0, keep)
                                length += keep
                                if (keep < count) status = ProcessStatus.OutputLimit
                            }
                        }
                    }
                }
                if (status != ProcessStatus.Exited) break
                code = child.exitCode()
                if (child.failed) { status = ProcessStatus.IoFailed; break }
                if (code != null) {
                    // A child that exits must not leave descendants holding our pipe.
                    if (!child.terminateTree()) { status = ProcessStatus.IoFailed; break }
                    if (eof) break
                }
                delay(10)
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    cleaned = child.terminateTree()
                    var attempts = 0
                    while (child.exitCode() == null && !child.failed && attempts++ < 200) delay(10)
                    code = child.exitCode()
                    cleaned = cleaned && code != null && !child.failed
                } finally { cleaned = child.close() && cleaned }
            }
        }
        return ProcessResult(if (cleaned) status else ProcessStatus.IoFailed, code,
            retained.decodeToString(0, length))
    }
}

internal interface ChildProcess {
    val failed: Boolean
    /** 0 = no data yet, -1 = EOF, -2 = IO failure. */
    fun read(buffer: ByteArray): Int
    fun exitCode(): Int?
    fun terminateTree(): Boolean
    fun close(): Boolean
}

internal expect fun startProcess(arguments: List<String>): ChildProcess?
internal expect fun isAbsoluteExecutable(path: String): Boolean

// Microsoft CRT quoting, not shell escaping. argv[0] is a separately validated path.
internal fun windowsCommandLine(arguments: List<String>): String = arguments.joinToString(" ") { argument ->
    buildString {
        append('"')
        var slashes = 0
        for (character in argument) {
            if (character == '\\') { slashes++; continue }
            repeat(if (character == '"') slashes * 2 + 1 else slashes) { append('\\') }
            append(character)
            slashes = 0
        }
        repeat(slashes * 2) { append('\\') }
        append('"')
    }
}
