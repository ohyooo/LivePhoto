package livephoto.adapters.files

import kotlinx.coroutines.CancellationException
import livephoto.adapters.process.NativeProcess
import livephoto.adapters.process.ProcessStatus
import livephoto.core.*
import livephoto.core.ffmpeg.*
import okio.Path.Companion.toPath

public data class NativeFfmpegDiscovery(public val backend: MediaBackend?,
    public val executable: String?, public val issues: List<Issue>)

/** User-installed external FFmpeg only. No shell, installer or system media API. */
public class NativeFfmpeg(private val workspace: () -> String,
    private val retainedSpool: () -> ULong = { 0uL }) : FfmpegRuntime {
    public override suspend fun execute(arguments: List<String>, timeoutMillis: Long, context: Context): FfmpegProcessResult {
        val result = try { NativeProcess.run(arguments, timeoutMillis, cancelled = { context.cancellation?.isCancelled() == true }) }
        catch (_: CancellationException) { return FfmpegProcessResult(null, "", cancelled = true) }
        return FfmpegProcessResult(result.exitCode, result.diagnostics,
            outputLimited = result.status == ProcessStatus.OutputLimit, cancelled = result.status == ProcessStatus.Cancelled,
            timedOut = result.status == ProcessStatus.TimedOut, ioFailed = result.status == ProcessStatus.IoFailed)
    }
    public override suspend fun openFiles(source: BinarySource, context: Context, outputName: String,
        producesOutput: Boolean): CoreResult<FfmpegFiles> =
        FileFfmpegWorkspace.open(workspace(), source, context, outputName, producesOutput, retainedSpool())

    public suspend fun discover(explicit: String?, searchPath: String): NativeFfmpegDiscovery {
        val issues = mutableListOf<Issue>()
        val separator = if (nativeWindows) ';' else ':'
        val name = if (nativeWindows) "ffmpeg.exe" else "ffmpeg"
        val candidates = buildList {
            if (explicit != null) add(explicit)
            for (entry in searchPath.split(separator).take(4096)) {
                val path = entry.removeSurrounding("\"").takeIf { it.isNotBlank() && '\u0000' !in it } ?: continue
                try { if (path.toPath().isAbsolute) add((path.toPath() / name).toString()) } catch (_: Exception) { }
            }
        }.distinct()
        for (candidate in candidates) {
            val executable = try {
                if ('\u0000' in candidate || candidate.isBlank()) null else {
                    val path = localFileSystem.canonicalize(candidate.toPath())
                    regular(localFileSystem, path)
                    path.toString().takeIf { nativeExecutable(it) }
                }
            } catch (_: Exception) { null }
            if (executable != null) {
                val result = execute(listOf(executable, "-version"), 15_000, Context(Limits(0uL, 0uL)))
                if (result.cancelled) return NativeFfmpegDiscovery(null, null, listOf(Issue(IssueCode("CANCELLED"), Severity.Error, Layer.Media)))
                if (result.code == 0 && !result.outputLimited && !result.ioFailed && !result.timedOut &&
                    result.output.lineSequence().firstOrNull()?.startsWith("ffmpeg version ") == true)
                    return NativeFfmpegDiscovery(FfmpegMediaBackend(executable, this), executable, issues.toList())
            }
            if (candidate == explicit) issues += Issue(IssueCode("FFMPEG_EXPLICIT_PATH_UNAVAILABLE"), Severity.Warning, Layer.Compatibility)
        }
        issues += Issue(IssueCode("MEDIA_BACKEND_UNAVAILABLE"), Severity.Warning, Layer.Compatibility)
        return NativeFfmpegDiscovery(null, null, issues.toList())
    }
}

internal expect val nativeWindows: Boolean
internal expect fun nativeExecutable(path: String): Boolean
