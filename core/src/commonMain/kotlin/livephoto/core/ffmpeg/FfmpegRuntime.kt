package livephoto.core.ffmpeg

import livephoto.core.*

/** Adapter contracts only. Protocol implementations do not depend on these types.
 * Executables and file locators are opaque to the processing/verification code. */
public interface FfmpegRuntime {
    public suspend fun execute(arguments: List<String>, timeoutMillis: Long, context: Context): FfmpegProcessResult
    public suspend fun openFiles(source: BinarySource, context: Context, outputName: String,
        producesOutput: Boolean): CoreResult<FfmpegFiles>
}

public data class FfmpegProcessResult(
    public val code: Int?, public val output: String,
    public val outputLimited: Boolean = false, public val cancelled: Boolean = false,
    public val timedOut: Boolean = false, public val ioFailed: Boolean = false,
)

/** Only backend-owned output may be patched. Input is borrowed and never closed here.
 * openOutput freezes the current file; closing that reader precedes any patch.
 * close must attempt cleanup even when Context is cancelled, deleting owned paths only. */
public interface FfmpegFiles {
    public val input: String
    public val output: String
    public val outputLimit: ULong
    public suspend fun outputSize(): CoreResult<ULong>
    public suspend fun openOutput(): CoreResult<BinarySource>
    public suspend fun patchOutput(patches: List<FfmpegPatch>): CoreResult<Unit>
    public suspend fun close(): CoreResult<Unit>
}

public data class FfmpegPatch(public val range: ByteRange, public val bytes: Bytes)
