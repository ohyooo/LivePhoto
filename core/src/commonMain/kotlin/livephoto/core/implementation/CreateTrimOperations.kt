package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.memory.MemoryOutputTransaction

/** Trim is verified privately; only the final composite transaction is public. Positions are source-domain. */
internal object CreateTrimOperations {
    private data class Prepared(val image: BinaryReader, val video: VideoResource, val trim: LosslessTrimPlan, val key: KeyPhotoResult?, val basePlan: ExecutionPlan, val spec: TrimSpec)
    private suspend fun prepare(request: CreateRequest, backend: MediaBackend?, inheritedKey: KeyPhotoResult?): Prepared {
        RequestValidation.validate(request).orThrow()
        val spec = request.edits?.trim ?: fail("INVALID_ARGUMENT", "Trim orchestration requires trim edits", Stage.Plan)
        if (request.target.protocol !in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2) || request.edits.replacementFrame != null)
            fail("CAPABILITY_UNSUPPORTED", "This orchestration profile supports trim/key-only Google JPEG Create", Stage.Plan)
        if (backend?.capabilities()?.operations?.none { it.operation == Operation.Trim && it.implementation in setOf(Implementation.Experimental, Implementation.Supported) } != false)
            fail("CAPABILITY_UNSUPPORTED", "Create/Convert trim requires an available verified trim backend", Stage.Plan)
        val image = BinaryReader(request.image, request.context); image.identity().orThrow()
        val video = VideoResource.open(ResourceRef(SourceSet.Single(request.video)), request.context)
        val trim = planLosslessTrim(video.reader, video.video, spec)
        // Current Google writer and finite backend boundaries must be exactly expressible in microseconds.
        microseconds(trim.start); microseconds(trim.end)
        val key = mapKey(request.edits.keyPosition, inheritedKey, trim, spec.keyOutside)
        val base = GoogleOperations.plan(request.copy(edits = null)).orThrow()
        TrimOperations.plan(TrimRequest(ResourceRef(SourceSet.Single(request.video)), spec, request.policy, request.output, request.context), backend).orThrow()
        image.validateIdentity().orThrow(); video.session.recheck()
        return Prepared(image, video, trim, key, base, spec)
    }
    suspend fun plan(request: CreateRequest, backend: MediaBackend?, inheritedKey: KeyPhotoResult? = null): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request, backend, inheritedKey)
        prepared.basePlan.copy(steps = listOf(
            PlanStep(Stage.Trim, listOf(Operation.Trim), emptyList(), "Source-domain key resolved before private lossless trim; no intermediate public output"),
            PlanStep(Stage.WriteProtocol, listOf(Operation.Create), emptyList(), "Use actual trimmed video length and rebased key in the final target binding")) + prepared.basePlan.steps,
            predictedPreservation = PreservationReport(changes = listOf(trimChange(prepared.spec, prepared.trim.start, prepared.trim.end))),
            capabilities = CapabilitySet(Availability.Conditional, prepared.basePlan.capabilities.operations + listOf(TrimOperations.capability(backend))))
    }
    suspend fun create(request: CreateRequest, backend: MediaBackend?, originalInputs: List<BinaryReader> = emptyList(), sourceChanges: List<Change> = emptyList(),
        inheritedKey: KeyPhotoResult? = null, metadataUnproven: Boolean = false): CoreResult<OperationResult> = attempt {
        val prepared = prepare(request, backend, inheritedKey)
        val limit = minOf(request.context.limits.maxOutputBytes, request.context.limits.maxSpoolBytes / 16uL)
        if (limit == 0uL) fail("RESOURCE_LIMIT_EXCEEDED", "No bounded private trimmed-video storage budget", Stage.Plan)
        val privateContext = request.context.copy(limits = request.context.limits.copy(maxOutputBytes = limit, maxSpoolBytes = request.context.limits.maxSpoolBytes - limit * 8uL))
        val privateOutput = MemoryOutputTransaction(privateContext, "create-trim-private:${prepared.video.session.snapshot.token.value}")
        val trimmed = TrimOperations.trim(TrimRequest(ResourceRef(SourceSet.Single(prepared.video.reader.source)), prepared.spec,
            request.policy, privateOutput, privateContext), backend).orThrow()
        try {
            prepared.image.validateIdentity().orThrow(); prepared.video.session.recheck()
            if (trimmed.actualStart.compareTo(prepared.trim.start) != 0 || trimmed.actualEnd.compareTo(prepared.trim.end) != 0 || trimmed.wasTranscoded || trimmed.retainedHiddenContent || !trimmed.wasBitstreamPreserved)
                fail("POSTCONDITION_FAILED", "Private trim contradicts the source-domain plan", Stage.Verify)
            val video = trimmed.operation.output.assets.single().readableSource ?: fail("POSTCONDITION_FAILED", "Private trimmed video is unreadable", Stage.Verify)
            val proof = trimmed.operation.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }
            if (proof.outcome != GuaranteeOutcome.Verified) fail("POSTCONDITION_FAILED", "Private trim lacks retained-sample proof", Stage.Verify)
            val created = GoogleOperations.create(request.copy(video = video, edits = null), originalInputs + prepared.video.session.readers + prepared.image,
                sourceChanges + listOf(trimChange(prepared.spec, trimmed.actualStart, trimmed.actualEnd)), prepared.key, metadataUnproven).orThrow()
            created.copy(preservation = created.preservation.copy(records = created.preservation.records.map { record ->
                if (record.guarantee == Guarantee.BitstreamPreserving) record.copy(sourceDigest = proof.sourceDigest, outputDigest = proof.outputDigest,
                    proof = "Every retained source sample/configuration verified by private trim; entire resulting video independently embedded byte-identically in final carrier; requested discarded samples excluded") else record
            }), execution = trimmed.operation.execution.filter { it.stage == Stage.Trim } + created.execution, issues = trimmed.operation.issues + created.issues)
        } finally { trimmed.operation.output.assets.forEach { it.readableSource?.close() } }
    }
    private fun mapKey(position: CoverPosition?, inherited: KeyPhotoResult?, trim: LosslessTrimPlan, outside: KeyOutsidePolicy): KeyPhotoResult? {
        var time = position?.let { selectFrame(trim.source, it).time } ?: inherited?.position
        if (time == null) return inherited?.copy(rawFields = emptyList(), frameIndex = null, trackId = null) // unknown stays unknown; ordinary Create derives its default from FINAL video
        if (time < trim.start || time >= trim.end) when (outside) {
            KeyOutsidePolicy.Reject -> fail("KEY_PHOTO_OUTSIDE_TRIM", "Source key lies outside the actual retained source range", Stage.Plan)
            KeyOutsidePolicy.ClearIfSupported -> return KeyPhotoResult(source = KeySource.Unknown) // write unspecified -1; V2 may expose a separately labelled DerivedDefault display position
            KeyOutsidePolicy.ClampExplicitly -> time = if (time < trim.start) trim.start else Time(trim.samples.last().presentationTime, trim.track.timescale)
        }
        return KeyPhotoResult(Time(microseconds(time) - microseconds(trim.start), 1_000_000u), source = KeySource.ProtocolField)
    }
    private fun timeValue(time: Time): Value = Value.ObjectValue(mapOf("value" to Value.Text(time.value.toString()), "timescale" to Value.Number(time.timescale.toString())))
    private fun trimChange(spec: TrimSpec, start: Time, end: Time): Change = Change("videoTrim", after = Value.ObjectValue(mapOf(
        "requestedStart" to timeValue(spec.range.start), "requestedEnd" to timeValue(spec.range.end), "actualStart" to timeValue(start), "actualEnd" to timeValue(end),
        "sourceDomain" to Value.BooleanValue(true), "wasTranscoded" to Value.BooleanValue(false), "retainedHiddenContent" to Value.BooleanValue(false), "keyOutsidePolicy" to Value.Text(spec.keyOutside.name))),
        reason = "Explicit source-domain lossless trim; final key rebased by actualStart, outside key changes only with explicit policy", requested = true)
}
