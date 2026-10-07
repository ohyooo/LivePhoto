package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

internal object TranscodeOperations {
    private suspend fun prepare(request: TranscodeRequest, backend: MediaBackend?): VideoResource {
        RequestValidation.validate(request).orThrow()
        if (request.context.limits.maxOutputBytes == 0uL) fail("RESOURCE_LIMIT_EXCEEDED", "No transcode output budget", Stage.Plan)
        if (request.policy.preservation == PreservationPolicy.Strict || request.policy.requiredGuarantees.isNotEmpty())
            fail("PRESERVATION_REQUIREMENT_FAILED", "Encoding cannot promise strict/exact/bitstream/full metadata preservation", Stage.Plan)
        if (backend?.capabilities()?.operations?.none { it.operation == Operation.Transcode && it.implementation in setOf(Implementation.Experimental, Implementation.Supported) } != false)
            fail("CAPABILITY_UNSUPPORTED", "No configured transcode backend", Stage.Plan)
        val caps = request.output.capabilities()
        if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic || request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
            fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Transcode requires independent readable atomic output", Stage.Plan)
        val resource = VideoResource.open(request.video, request.context)
        transcodeProfile(resource.video, request.encoding)
        RemuxVerification.metadata(resource.reader, resource.video, transcodeAvcConfiguration = true)
        resource.session.recheck()
        return resource
    }
    fun capability(): CapabilityEntry = CapabilityEntry(Operation.Transcode, Implementation.Experimental,
        conditions = listOf(Condition(ConditionOperator.Equals, "profile", Value.Text("explicit-bounded-single-avc1-mp4-no-audio-edit-reorder-identity-transform-preserve-every-vfr-sample")),
            Condition(ConditionOperator.Equals, "pixelProfile", Value.Text("backend-proved-progressive-square-pixel-eight-bit-bt709-limited-sdr-before-and-after")),
            Condition(ConditionOperator.Equals, "guarantees", Value.Text("encoded-bitstream-changed-classified-ordinary-metadata-compared-no-full-fidelity-promise"))), verification = listOf(Verification.SourceReviewed))
    suspend fun plan(request: TranscodeRequest, backend: MediaBackend?): CoreResult<ExecutionPlan> = attempt {
        val resource = prepare(request, backend)
        ExecutionPlan(resource.session.snapshot, steps = listOf(PlanStep(Stage.Transcode, listOf(Operation.Transcode), listOfNotNull(request.video.resourceId), "Explicit authorized encoding; prove source SDR before encoding, retain all source frames"),
            PlanStep(Stage.Verify, emptyList(), emptyList(), "Compare tracks, every timestamp/duration/dimension and classified ordinary metadata before publication")),
            predictedPreservation = PreservationReport(changes = changes()), capabilities = CapabilitySet(Availability.Conditional, listOf(capability())))
    }
    suspend fun transcode(request: TranscodeRequest, backend: MediaBackend?): CoreResult<OperationResult> = attempt {
        val resource = prepare(request, backend); val processor = backend ?: fail("CAPABILITY_UNSUPPORTED", "No transcode backend")
        val spec = OutputAssetSpec(AssetRole.MotionVideo, recommendedName = "video.mp4", mime = "video/mp4")
        var declared: BackendResult? = null
        val asset = StagedAsset(spec, container = request.encoding.container, write = { writer ->
            val staging = BackendSingleAssetStaging(spec, writer, request.context, Stage.Transcode)
            val run = processor.transcode(BackendJob(Operation.Transcode, listOf(ResourceRef(SourceSet.Single(resource.reader.source))), videoEncoding = request.encoding,
                policy = request.policy, context = request.context, destination = staging))
            resource.session.recheck(); val result = run.orThrow(); staging.validateResult(result); declared = result
            if (result.execution.isEmpty() || result.execution.any { it.stage != Stage.Transcode || !it.transcoded || it.remuxed || it.hardwareUsed != false || it.backendId !in processor.capabilities().backendIds ||
                    it.inputFacts != videoFacts(resource.video) || it.outputFacts != result.media.singleOrNull() } || result.tracks.isNotEmpty() || result.timelineMap.isNotEmpty() ||
                result.actualFrameTime != null || result.actualFrameIndex != null || result.actualFrameTrack != null || result.issues.any { it.severity == Severity.Error })
                fail("POSTCONDITION_FAILED", "Backend transcode execution declaration is inconsistent", Stage.Verify)
        }, verify = { id, reader ->
            val identity = reader.identity().orThrow(); val actual = BmffVideoProbe(reader).probe(ByteRange(0uL, identity.size)).orThrow()
            verifyTranscode(resource.reader, resource.video, reader, actual, request.encoding)
            val run = declared ?: fail("POSTCONDITION_FAILED", "Missing backend execution", Stage.Verify)
            if (run.assets.single().byteLength != identity.size || run.media != listOf(videoFacts(actual))) fail("POSTCONDITION_FAILED", "Backend facts contradict independently parsed output", Stage.Verify)
            val originalDigest = sha256Range(resource.reader, resource.video.range).orThrow(); val outputDigest = sha256Range(reader, actual.range).orThrow()
            AssetVerification(ValidationReport(Verdict.Warning, Coverage.Partial, listOf(CheckResult("transcode.structure", Layer.Structure, Verdict.Valid, Coverage.Complete),
                CheckResult("transcode.timeline-and-classified-metadata", Layer.Media, Verdict.Valid, Coverage.Partial)), snapshot = Snapshot(listOf(identity), identity.generation)), listOf(
                GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable), GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.NotApplicable),
                GuaranteeRecord(id, Guarantee.BitstreamPreserving, GuaranteeOutcome.Changed, originalDigest, outputDigest, "Explicit encoder execution; no bitstream-preserving claim"),
                GuaranteeRecord(id, Guarantee.MetadataPreserving, GuaranteeOutcome.Unknown, proof = "Classified ordinary container metadata compared excluding requested AVC configuration rewrite; no independent pixel/color/SEI fidelity proof")))
        })
        val result = publish(request.output, request.policy, request.context, resource.session.readers, listOf(asset), changes()).orThrow()
        val execution = declared ?: fail("POSTCONDITION_FAILED", "Missing verified transcode execution", Stage.Verify)
        result.copy(execution = execution.execution + result.execution, issues = execution.issues + result.issues)
    }
    private fun changes(): List<Change> = listOf(Change("videoEncodedData", after = Value.Text("authorized-derived-avc"), reason = "Standalone explicit transcode request; coding configuration and encoded samples may change, no implicit CFR/SDR/resize/audio removal", requested = true))
}
