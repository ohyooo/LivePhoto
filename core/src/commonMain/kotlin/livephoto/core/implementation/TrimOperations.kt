package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

internal object TrimOperations {
    private data class Prepared(val resource: VideoResource, val plan: PlannedTrim, val metadata: RemuxVerification.Metadata)
    private suspend fun prepare(request: TrimRequest, backend: MediaBackend?): Prepared {
        RequestValidation.validate(request).orThrow()
        if (request.context.limits.maxOutputBytes == 0uL) fail("RESOURCE_LIMIT_EXCEEDED", "No trim output budget", Stage.Plan)
        if (Guarantee.ExactExtraction in request.policy.requiredGuarantees) fail("PRESERVATION_REQUIREMENT_FAILED", "Trim cannot promise whole-file exact extraction", Stage.Plan)
        if (backend?.capabilities()?.operations?.none { it.operation == Operation.Trim && it.implementation in setOf(Implementation.Experimental, Implementation.Supported) } != false)
            fail("CAPABILITY_UNSUPPORTED", "No configured backend implements trim", Stage.Plan)
        val caps = request.output.capabilities()
        if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic || request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
            fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Trim requires independently readable atomic staging", Stage.Plan)
        val resource = VideoResource.open(request.video, request.context)
        val trim = planTrim(resource.reader, resource.video, request.spec, request.policy)
        val metadata = RemuxVerification.metadata(resource.reader, resource.video, trimDurationsVerifiedSeparately = true, transcodeAvcConfiguration = trim.encoded)
        resource.session.recheck()
        return Prepared(resource, trim, metadata)
    }
    fun capability(backend: MediaBackend?): CapabilityEntry = CapabilityEntry(Operation.Trim, Implementation.Experimental,
        conditions = listOf(Condition(ConditionOperator.Equals, "input", Value.Text("one-avc1-video-no-audio-no-reorder-zero-or-identity-edit-closed-idr")),
            Condition(ConditionOperator.Equals, "precision", Value.Text("lossless-first-preferred-never-encodes-exact-authorized-finite-avc-sdr-encoding-no-hidden-preroll"))) +
            (backend?.capabilities()?.operations?.firstOrNull { it.operation == Operation.Trim }?.conditions ?: emptyList()), verification = listOf(Verification.SourceReviewed))
    suspend fun plan(request: TrimRequest, backend: MediaBackend?): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request, backend)
        ExecutionPlan(prepared.resource.session.snapshot, steps = listOf(
            PlanStep(Stage.Trim, listOf(Operation.Trim), listOfNotNull(request.video.resourceId), if (prepared.plan.encoded) "Explicit authorized exact-frame trim encoding; source SDR proof required before encoder" else "Trim at proved independent source boundaries without encoding"),
            PlanStep(Stage.Verify, emptyList(), emptyList(), "Verify every retained sample/configuration, rebased timeline and ordinary metadata before commit")),
            predictedPreservation = PreservationReport(changes = listOf(change(prepared.plan))), capabilities = CapabilitySet(Availability.Conditional, listOf(capability(backend))))
    }
    suspend fun trim(request: TrimRequest, backend: MediaBackend?): CoreResult<TrimResult> = attempt {
        val prepared = prepare(request, backend); val resource = prepared.resource; val plan = prepared.plan; val trim = plan.boundaries
        val processor = backend ?: fail("CAPABILITY_UNSUPPORTED", "No trim backend", Stage.Plan)
        val mime = videoFacts(resource.video).mime!!
        val spec = OutputAssetSpec(AssetRole.MotionVideo, mime = mime)
        var backendResult: BackendResult? = null
        val asset = StagedAsset(spec, container = resource.video.container, write = { writer ->
            val staging = BackendSingleAssetStaging(spec, writer, request.context, Stage.Trim)
            val result = processor.trim(BackendJob(Operation.Trim, listOf(ResourceRef(SourceSet.Single(resource.reader.source))), trim = request.spec,
                policy = request.policy, context = request.context, destination = staging))
            resource.session.recheck()
            val declared = result.orThrow(); staging.validateResult(declared)
            if (declared.tracks != listOf(plan.trackTrim) || declared.timelineMap != trim.mapping || declared.actualFrameTime != null || declared.actualFrameIndex != null || declared.actualFrameTrack != null ||
                declared.execution.isEmpty() || declared.execution.any { it.stage != Stage.Trim || it.transcoded != plan.encoded || it.remuxed == plan.encoded || plan.encoded && it.hardwareUsed != false || it.backendId !in processor.capabilities().backendIds ||
                    it.inputFacts != videoFacts(resource.video) || it.outputFacts != declared.media.singleOrNull() } || declared.issues.any { it.severity == Severity.Error })
                fail("POSTCONDITION_FAILED", "Backend did not describe the proven trim range/timeline/encoding", Stage.Verify)
            backendResult = declared
        }, verify = { id, reader ->
            val actual = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
            verifyTrimDurationHeaders(reader, actual, trim)
            val digest = if (plan.encoded) { verifyEncodedTrim(resource.reader, trim, reader, actual); null }
                else RemuxVerification.verify(resource.reader, trim.expected(), reader, actual)
            val metadata = RemuxVerification.metadata(reader, actual, trimDurationsVerifiedSeparately = true, transcodeAvcConfiguration = plan.encoded)
            RemuxVerification.verifyMetadata(prepared.metadata, metadata)
            val declared = backendResult ?: fail("POSTCONDITION_FAILED", "Missing trim backend result", Stage.Verify)
            if (declared.media != listOf(videoFacts(actual)) || declared.assets.single().byteLength != reader.identity().orThrow().size)
                fail("POSTCONDITION_FAILED", "Trim bytes contradict backend output facts", Stage.Verify)
            val report = ValidationReport(Verdict.Warning, Coverage.Partial, listOf(CheckResult("trim.structure", Layer.Structure, Verdict.Valid, Coverage.Complete),
                CheckResult("trim.selected-samples-timeline-metadata", Layer.Media, Verdict.Valid, Coverage.Partial)), snapshot = Snapshot(listOf(reader.identity().orThrow()), reader.identity().orThrow().generation))
            AssetVerification(report, listOf(GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable), GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.NotApplicable),
                GuaranteeRecord(id, Guarantee.BitstreamPreserving, if (plan.encoded) GuaranteeOutcome.Changed else GuaranteeOutcome.Verified, digest, digest, if (plan.encoded) "Explicit exact trim encoding; no bitstream preservation claim" else "Retained AVC samples/configuration unchanged; independent IDR start; exact rebased timeline; no hidden content"),
                GuaranteeRecord(id, Guarantee.MetadataPreserving, if (plan.encoded) GuaranteeOutcome.Unknown else GuaranteeOutcome.Verified, prepared.metadata.digest, metadata.digest, "Classified ordinary headers compared separately from durations and explicitly authorized coding rewrite; no full encoded pixel/SEI fidelity claim")))
        })
        val operation = publish(request.output, request.policy, request.context, resource.session.readers, listOf(asset), listOf(change(plan))).orThrow()
        val declared = backendResult ?: fail("POSTCONDITION_FAILED", "Missing trim execution", Stage.Verify)
        TrimResult(operation.copy(execution = declared.execution + operation.execution, issues = operation.issues + declared.issues),
            request.spec.range.start, request.spec.range.end, trim.start, trim.end, listOf(plan.trackTrim), plan.encoded, !plan.encoded, !plan.encoded, false, request.spec.exactTolerance, trim.mapping)
    }
    private fun change(plan: PlannedTrim): Change = Change("presentationRange", after = Value.Text("${plan.boundaries.start.value}/${plan.boundaries.start.timescale}..${plan.boundaries.end.value}/${plan.boundaries.end.timescale}"), reason = if (plan.encoded) "Authorized exact trim with changed encoded data; actual source-domain boundaries disclosed" else "Lossless trim, actual source-domain boundaries disclosed", requested = true)
}
