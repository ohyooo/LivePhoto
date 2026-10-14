package livephoto.core.implementation

import livephoto.core.*

/** Explicitly injected backends only; this component never discovers or enables system APIs. */
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
