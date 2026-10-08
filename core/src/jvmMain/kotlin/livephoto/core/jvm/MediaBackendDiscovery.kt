package livephoto.core.jvm

import livephoto.core.*
import java.nio.file.Files
import java.nio.file.Path

public data class BackendDiscovery(
    public val backend: MediaBackend?,
    public val ffmpegPath: Path?,
    public val issues: List<Issue>,
)

/** No downloads, shell aliases, working-directory lookup, or implicit installation. */
public object JvmMediaBackends {
    /**
     * Explicit executable, then absolute PATH entries, then injected OS API adapters.
     * Bundled system adapters report only their finite, runtime-checked implemented capabilities.
     * Unsupported operations may fall through; decode/IO/policy errors never trigger a retry.
     */
    public fun discover(ffmpegPath: Path? = null, systemBackends: List<MediaBackend> = WindowsMediaFoundationBackend.available()): BackendDiscovery =
        discover(ffmpegPath, System.getenv("PATH") ?: "", System.getProperty("os.name").startsWith("Windows"), systemBackends, ::verifyFfmpeg)

    internal fun verifyFfmpeg(path: Path, run: (List<String>, Long) -> ProcessResult = { arguments, timeout -> ExternalProcess.run(arguments, timeout) }): Boolean {
        // Cold-start DLL loading / antivirus on Windows can exceed three seconds. This is a
        // bounded read-only version check, not an operation retry or a media-decode timeout.
        val result = run(listOf(path.toString(), "-version"), 15_000L)
        return result.code == 0 && !result.outputLimited && !result.ioFailed && !result.timedOut && !result.cancelled &&
            result.output.lineSequence().firstOrNull()?.startsWith("ffmpeg version ") == true
    }

    internal fun discover(explicit: Path?, searchPath: String, windows: Boolean, systems: List<MediaBackend>,
        verify: (Path) -> Boolean): BackendDiscovery {
        val issues = mutableListOf<Issue>()
        val candidates = buildList {
            if (explicit != null) add(explicit.toAbsolutePath().normalize())
            for (entry in searchPath.split(if (windows) ';' else ':')) {
                if (entry.isBlank()) continue
                val directory = try { Path.of(entry.removeSurrounding("\"")) } catch (_: IllegalArgumentException) { continue }
                if (directory.isAbsolute) add(directory.resolve(if (windows) "ffmpeg.exe" else "ffmpeg"))
            }
        }.distinct()
        var selected: Path? = null
        for (candidate in candidates) {
            val valid = try {
                candidate.toRealPath().takeIf { Files.isRegularFile(it) && Files.isExecutable(it) && verify(it) }
            } catch (_: Exception) { null }
            if (valid != null) { selected = valid; break }
            if (explicit != null && candidate == explicit.toAbsolutePath().normalize()) issues += Issue(
                IssueCode("FFMPEG_EXPLICIT_PATH_UNAVAILABLE"), Severity.Warning, Layer.Compatibility)
        }
        val backends = listOfNotNull(selected?.let { FfmpegMediaBackend(it) }) + systems
        if (backends.isEmpty()) issues += Issue(IssueCode("MEDIA_BACKEND_UNAVAILABLE"), Severity.Warning, Layer.Compatibility)
        return BackendDiscovery(backends.takeIf { it.isNotEmpty() }?.let(::FallbackMediaBackend), selected, issues.toList())
    }
}

internal class FallbackMediaBackend(private val backends: List<MediaBackend>) : MediaBackend {
    override fun capabilities(): MediaCapabilities = MediaCapabilities(backends.flatMap { it.capabilities().backendIds }.distinct(),
        listOf(Operation.Probe, Operation.Trim, Operation.Remux, Operation.Transcode, Operation.ExtractFrame).map { operation ->
            backends.firstNotNullOfOrNull { backend -> backend.capabilities().operations.firstOrNull {
                it.operation == operation && it.implementation in setOf(Implementation.Supported, Implementation.Experimental)
            } } ?: CapabilityEntry(operation, Implementation.Unsupported, reasons = listOf(IssueCode("CAPABILITY_UNSUPPORTED")))
        })
    private suspend fun <T> dispatch(operation: Operation, context: Context, mayFallback: () -> Boolean = { true }, call: suspend (MediaBackend) -> CoreResult<T>): CoreResult<T> {
        fun cancelled(): CoreResult.Failure? = if (context.cancellation?.isCancelled() == true)
            CoreResult.Failure(CoreError(IssueCode("CANCELLED"), Stage.Plan, "Backend dispatch cancelled")) else null
        cancelled()?.let { return it }
        for (backend in backends) {
            cancelled()?.let { return it }
            if (backend.capabilities().operations.none { it.operation == operation && it.implementation in setOf(Implementation.Supported, Implementation.Experimental) }) continue
            val result = call(backend)
            if (result !is CoreResult.Failure || result.error.code.value != "CAPABILITY_UNSUPPORTED" || !mayFallback()) return result
        }
        return CoreResult.Failure(CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Plan, "No available backend implements this media operation", recoverability = Recoverability.WithBackend))
    }
    override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = dispatch(Operation.Probe, request.context) { it.probe(request) }
    private suspend fun dispatchJob(job: BackendJob, expected: Operation, call: suspend (MediaBackend, BackendJob) -> CoreResult<BackendResult>): CoreResult<BackendResult> {
        if (job.operation != expected) return CoreResult.Failure(CoreError(IssueCode("INVALID_ARGUMENT"), Stage.Plan, "Backend method does not match job operation"))
        return when (val validated = job.validate()) {
            is CoreResult.Failure -> validated
            is CoreResult.Success -> {
                // Unsupported is a preflight signal, not permission to retry after staged IO.
                // Mark attempts too: a backend must not conceal a failed write as Unsupported.
                var stagingTouched = false
                val guarded = job.copy(destination = object : StagingArea {
                    override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> {
                        stagingTouched = true
                        return job.destination.create(spec)
                    }
                    override suspend fun openForRead(id: AssetId): CoreResult<BinarySource> {
                        stagingTouched = true
                        return job.destination.openForRead(id)
                    }
                })
                dispatch(job.operation, job.context, { !stagingTouched }) { backend -> call(backend, guarded) }
            }
        }
    }
    override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = dispatchJob(job, Operation.Trim) { backend, guarded -> backend.trim(guarded) }
    override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = dispatchJob(job, Operation.Remux) { backend, guarded -> backend.remux(guarded) }
    override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = dispatchJob(job, Operation.Transcode) { backend, guarded -> backend.transcode(guarded) }
    override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = dispatchJob(job, Operation.ExtractFrame) { backend, guarded -> backend.extractFrame(guarded) }
}
