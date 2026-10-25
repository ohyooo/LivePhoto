package livephoto.core.ffmpeg

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.BmffVideoProbe
import livephoto.core.memory.MemoryBinarySource
import livephoto.core.implementation.RangeSource

// Preprocessing remains bounded independently of the final photo budget.
public const val BROWSER_MEDIA_INPUT_LIMIT: Int = 512 * 1024 * 1024

/** Worker adapter only. Commands and all media verification remain in the existing backend. */
public fun interface MemoryFfmpegExecutor {
    public suspend fun execute(arguments: List<String>, input: BinarySource, previousOutput: Bytes?, outputName: String,
        timeoutMillis: Long, context: Context): MemoryFfmpegResult
}
public data class MemoryFfmpegResult(public val process: FfmpegProcessResult, public val output: Bytes? = null)

/** Resolve only Core-owned borrowed views onto the captured immutable browser input. */
public suspend fun browserMediaInputRange(input: BinarySource, original: BinarySource): CoreResult<ByteRange> = attempt {
    var source = input
    var range = ByteRange(0uL, input.size().orThrow())
    var depth = 0
    while (source !== original) {
        val view = source as? RangeSource
            ?: fail("CAPABILITY_UNSUPPORTED", "Media input is not bound to the selected browser file", Stage.Read)
        if (++depth > 64) fail("RESOURCE_LIMIT_EXCEEDED", "Too many borrowed media views", Stage.Read)
        checkedRange(range.offset, range.length, view.range.length)
        range = ByteRange(checkedAdd(view.range.offset, range.offset), range.length)
        source = view.reader.source
    }
    checkedRange(range.offset, range.length, original.size().orThrow())
    range
}

/** One private bounded workspace per backend instance; never a host filesystem or publication API. */
public class MemoryFfmpegRuntime(private val executor: MemoryFfmpegExecutor) : FfmpegRuntime {
    private var active: Workspace? = null
    private var opening: Boolean = false
    override suspend fun execute(arguments: List<String>, timeoutMillis: Long, context: Context): FfmpegProcessResult {
        val workspace = active ?: return FfmpegProcessResult(null, "", ioFailed = true)
        if (context.cancellation?.isCancelled() == true) return FfmpegProcessResult(null, "", cancelled = true)
        if (arguments.firstOrNull() != "ffmpeg" || arguments.filterIndexed { i, _ -> i > 0 && arguments[i - 1] == "-i" }
                .any { it !in setOf(workspace.input, workspace.output) }) return FfmpegProcessResult(null, "", ioFailed = true)
        val result = executor.execute(arguments.drop(1), workspace.source, workspace.outputBytes, workspace.output,
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
            if (identity.size > minOf(context.limits.maxSpoolBytes, BROWSER_MEDIA_INPUT_LIMIT.toULong())) fail("RESOURCE_LIMIT_EXCEEDED", "Browser media input exceeds memory budget", Stage.Read)
            reader.validateIdentity().orThrow()
            Workspace(source, outputName, minOf(context.limits.maxOutputBytes, 48uL * 1024u * 1024u)).also { active = it }
        } finally { opening = false }
    }

    private inner class Workspace(val source: BinarySource, override val output: String, override val outputLimit: ULong) : FfmpegFiles {
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
    override fun capabilities(): MediaCapabilities = delegate.capabilities().let { it.copy(backendIds = listOf("ffmpeg-wasm"),
        operations = it.operations.map { entry -> entry.copy(conditions = entry.conditions + listOf(Condition(ConditionOperator.Equals,
            "runtimeProfile", Value.Text("private-worker-input512MiB-output48MiB-timeout120s-per-invocation-no-host-path-no-audio-encoder"))) +
            if (entry.operation in setOf(Operation.Trim, Operation.Remux, Operation.Transcode)) listOf(Condition(ConditionOperator.Equals,
                "wasmAudioProfile", Value.Text("absent-or-AAC-LC-original-channelcount-matches-ASC-no-audio-encoder"))) else emptyList()) }) }.let { caps ->
        caps.copy(operations = caps.operations.map { entry -> if (entry.operation == Operation.Transcode) entry.copy(conditions = entry.conditions +
            Condition(ConditionOperator.Equals, "encoderAnnotation", Value.Text("original-AVC-sample-entry-encoder-annotation-must-match-pinned-muxer-output-otherwise-no-publication"))) else entry })
    }
    override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = delegate.probe(request)
    override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = copyAudio(Operation.Trim, job) { delegate.trim(job) }
    override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = copyAudio(Operation.Remux, job) { delegate.remux(job) }
    override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = copyAudio(Operation.Transcode, job) { delegate.transcode(job) }
    override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = identify(delegate.extractFrame(job))
    private suspend fun copyAudio(operation: Operation, job: BackendJob, run: suspend () -> CoreResult<BackendResult>): CoreResult<BackendResult> {
        val gate = attempt {
            if (job.operation != operation) fail("INVALID_ARGUMENT", "Backend method and operation differ", Stage.Plan)
            job.validate().orThrow()
            val ref = job.inputs.single()
            val source = (ref.input as? SourceSet.Single)?.source
                ?: fail("INVALID_ARGUMENT", "Browser media requires one resolved resource", Stage.Plan)
            if (ref.resourceId != null || ref.snapshot != null) fail("INVALID_ARGUMENT", "Core must resolve browser media references", Stage.Plan)
            val reader = BinaryReader(source, job.context)
            val video = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
            // The rebuilt muxer emits actual channels. Legacy reserved headers
            // that disagree with ASC still cannot be silently normalized.
            if (video.tracks.any { it.handler == "soun" && it.audioChannels != it.audioActualChannelCount })
                fail("CAPABILITY_UNSUPPORTED", "AAC sample-entry channelcount must match AudioSpecificConfig to preserve both without normalization", Stage.Plan)
            reader.validateIdentity().orThrow()
        }
        return when (gate) { is CoreResult.Failure -> gate; is CoreResult.Success -> identify(run()) }
    }
    private fun identify(result: CoreResult<BackendResult>): CoreResult<BackendResult> = when (result) {
        is CoreResult.Failure -> result
        is CoreResult.Success -> CoreResult.Success(result.value.copy(execution = result.value.execution.map { it.copy(backendId = "ffmpeg-wasm") }))
    }
}
