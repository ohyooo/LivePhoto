package livephoto.core.ffmpeg

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.memory.MemoryBinarySource

/** Worker adapter only. Commands and all media verification remain in the existing backend. */
public fun interface MemoryFfmpegExecutor {
    public suspend fun execute(arguments: List<String>, input: Bytes, previousOutput: Bytes?, outputName: String,
        timeoutMillis: Long, context: Context): MemoryFfmpegResult
}
public data class MemoryFfmpegResult(public val process: FfmpegProcessResult, public val output: Bytes? = null)

/** One private bounded workspace per backend instance; never a host filesystem or publication API. */
public class MemoryFfmpegRuntime(private val executor: MemoryFfmpegExecutor) : FfmpegRuntime {
    private var active: Workspace? = null
    private var opening: Boolean = false
    override suspend fun execute(arguments: List<String>, timeoutMillis: Long, context: Context): FfmpegProcessResult {
        val workspace = active ?: return FfmpegProcessResult(null, "", ioFailed = true)
        if (context.cancellation?.isCancelled() == true) return FfmpegProcessResult(null, "", cancelled = true)
        if (arguments.firstOrNull() != "ffmpeg" || arguments.filterIndexed { i, _ -> i > 0 && arguments[i - 1] == "-i" }
                .any { it !in setOf(workspace.input, workspace.output) }) return FfmpegProcessResult(null, "", ioFailed = true)
        val result = executor.execute(arguments.drop(1), workspace.inputBytes, workspace.outputBytes, workspace.output,
            timeoutMillis.coerceIn(1, 120_000), context)
        if (result.output != null && result.output.size.toULong() > workspace.outputLimit)
            return FfmpegProcessResult(null, "", outputLimited = true)
        if (result.process.code == 0 && !result.process.cancelled && !result.process.timedOut && !result.process.ioFailed && !result.process.outputLimited)
            result.output?.let { workspace.outputBytes = it }
        return result.process
    }

    override suspend fun openFiles(source: BinarySource, context: Context, outputName: String, producesOutput: Boolean): CoreResult<FfmpegFiles> = attempt {
        if (active != null || opening) fail("CAPABILITY_UNSUPPORTED", "Use a separate backend instance for concurrent jobs", Stage.Read)
        if (outputName !in setOf("output.bin", "frame.jpg")) fail("INVALID_ARGUMENT", "Unowned memory output name", Stage.Read)
        opening = true
        try {
            val reader = BinaryReader(source, context)
            val identity = reader.identity().orThrow()
            if (identity.size > minOf(context.limits.maxSpoolBytes, 32uL * 1024u * 1024u)) fail("RESOURCE_LIMIT_EXCEEDED", "Browser media input exceeds memory budget", Stage.Read)
            val data = reader.readExactly(0uL, identity.size.toUInt()).orThrow()
            reader.validateIdentity().orThrow()
            Workspace(data, outputName, minOf(context.limits.maxOutputBytes, 48uL * 1024u * 1024u)).also { active = it }
        } finally { opening = false }
    }

    private inner class Workspace(val inputBytes: Bytes, override val output: String, override val outputLimit: ULong) : FfmpegFiles {
        override val input: String = "input.bin"
        var outputBytes: Bytes? = null
        override suspend fun outputSize(): CoreResult<ULong> = outputBytes?.let { CoreResult.Success(it.size.toULong()) }
            ?: CoreResult.Failure(CoreError(IssueCode("FFMPEG_OUTPUT_MISSING"), Stage.Read, "No private output"))
        override suspend fun openOutput(): CoreResult<BinarySource> = outputBytes?.let { CoreResult.Success(MemoryBinarySource(it, SourceId("memory-ffmpeg-output"))) }
            ?: CoreResult.Failure(CoreError(IssueCode("FFMPEG_OUTPUT_MISSING"), Stage.Read, "No private output"))
        override suspend fun patchOutput(patches: List<FfmpegPatch>): CoreResult<Unit> = attempt {
            val original = outputBytes ?: fail("FFMPEG_OUTPUT_MISSING", "No private output", Stage.Read)
            if (patches.any { !it.range.fitsWithin(original.size.toULong()) || it.range.length != it.bytes.size.toULong() })
                fail("INVALID_ARGUMENT", "Private output patch is out of bounds", Stage.Remux)
            val patched = original.toByteArray()
            patches.forEach { it.bytes.toByteArray().copyInto(patched, it.range.offset.toInt()) }
            outputBytes = Bytes(patched)
        }
        override suspend fun close(): CoreResult<Unit> {
            outputBytes = null
            if (active === this) active = null
            return CoreResult.Success(Unit)
        }
    }
}

/** Reuses finite Core/FFmpeg profiles, but identifies actual Worker/Wasm execution honestly. */
public class BrowserFfmpegBackend(executor: MemoryFfmpegExecutor) : MediaBackend {
    private val delegate = FfmpegMediaBackend("ffmpeg", MemoryFfmpegRuntime(executor))
    override fun capabilities(): MediaCapabilities = delegate.capabilities().copy(backendIds = listOf("ffmpeg-wasm"))
    override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = delegate.probe(request)
    override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = identify(delegate.trim(job))
    override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = identify(delegate.remux(job))
    override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = identify(delegate.transcode(job))
    override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = identify(delegate.extractFrame(job))
    private fun identify(result: CoreResult<BackendResult>): CoreResult<BackendResult> = when (result) {
        is CoreResult.Failure -> result
        is CoreResult.Success -> CoreResult.Success(result.value.copy(execution = result.value.execution.map { it.copy(backendId = "ffmpeg-wasm") }))
    }
}
