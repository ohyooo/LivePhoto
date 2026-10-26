package livephoto.adapters.files

import livephoto.adapters.process.NativeProcess
import livephoto.adapters.process.ProcessStatus
import livephoto.core.Context
import livephoto.core.ffmpeg.FfmpegProcessResult

internal actual suspend fun executeFileFfmpeg(arguments: List<String>, timeoutMillis: Long, context: Context): FfmpegProcessResult {
    val result = NativeProcess.run(arguments, timeoutMillis, cancelled = { context.cancellation?.isCancelled() == true })
    return FfmpegProcessResult(result.exitCode, result.diagnostics,
        outputLimited = result.status == ProcessStatus.OutputLimit, cancelled = result.status == ProcessStatus.Cancelled,
        timedOut = result.status == ProcessStatus.TimedOut, ioFailed = result.status == ProcessStatus.IoFailed)
}
