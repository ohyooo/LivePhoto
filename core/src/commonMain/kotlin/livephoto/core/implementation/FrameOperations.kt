package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.jpeg.*

/** Derives an image, never rewrites the video or treats the key-photo field as a decoded frame. */
internal object FrameOperations {
    private data class Prepared(val resource: VideoResource, val frame: SelectedFrame)
    private suspend fun prepare(request: ExtractFrameRequest, backend: MediaBackend?): Prepared {
        RequestValidation.validate(request).orThrow()
        if (request.encoding.format != ImageFormat.Jpeg) fail("CAPABILITY_UNSUPPORTED", "Independent frame-output verification currently supports JPEG", Stage.Plan)
        if (backend?.capabilities()?.operations?.none { it.operation == Operation.ExtractFrame && it.implementation in setOf(Implementation.Supported, Implementation.Experimental) } != false)
            fail("CAPABILITY_UNSUPPORTED", "No configured backend implements frame extraction", Stage.Plan)
        val caps = request.output.capabilities()
        if (!caps.canReadStaged || !caps.assetSetAtomic) fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Frame extraction requires readable atomic staging", Stage.Plan)
        val resource = VideoResource.open(request.video, request.context)
        return Prepared(resource, selectFrame(resource.video, request.position))
    }

    fun capability(backend: MediaBackend?): CapabilityEntry = CapabilityEntry(Operation.ExtractFrame, Implementation.Experimental,
        conditions = listOf(Condition(ConditionOperator.Equals, "output", Value.Text("independently-verified-jpeg")),
            Condition(ConditionOperator.Equals, "selection", Value.Text("exact-rational-presentation-order-pts-dts-sample-ordinal"))) +
            (backend?.capabilities()?.operations?.firstOrNull { it.operation == Operation.ExtractFrame }?.conditions ?: emptyList()),
        verification = listOf(Verification.SourceReviewed))

    suspend fun plan(request: ExtractFrameRequest, backend: MediaBackend?): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request, backend)
        ExecutionPlan(prepared.resource.session.snapshot, steps = listOf(
            PlanStep(Stage.DecodeFrame, listOf(Operation.ExtractFrame), listOfNotNull(request.video.resourceId), "Decode dependencies to the selected source-domain presentation frame"),
            PlanStep(Stage.EncodeImage, emptyList(), emptyList(), "Encode the explicitly requested derived JPEG; never alter source video"),
            PlanStep(Stage.Verify, emptyList(), emptyList(), "Verify image structure and backend frame/time/track declarations before atomic commit")),
            predictedPreservation = PreservationReport(changes = listOf(derivedChange())), capabilities = CapabilitySet(Availability.Conditional, listOf(capability(backend))))
    }

    suspend fun extract(request: ExtractFrameRequest, backend: MediaBackend?): CoreResult<FrameResult> = attempt {
        val prepared = prepare(request, backend)
        val resource = prepared.resource
        val frame = prepared.frame
        val processor = backend ?: fail("CAPABILITY_UNSUPPORTED", "No frame decoder", Stage.Plan)
        val spec = OutputAssetSpec(AssetRole.PrimaryImage, "frame.jpg", "image/jpeg")
        var backendResult: BackendResult? = null
        val asset = StagedAsset(spec, ImageFormat.Jpeg, write = { writer ->
            val staging = BackendSingleAssetStaging(spec, writer, request.context, Stage.EncodeImage)
            // Resolve once in the source domain; backend receives exact index/track, not a second nearest selection.
            val job = BackendJob(Operation.ExtractFrame, listOf(ResourceRef(SourceSet.Single(resource.reader.source))),
                position = CoverPosition.FrameIndex(frame.index, TrackId(frame.track.trackId.toString())), imageEncoding = request.encoding,
                context = request.context, destination = staging)
            val result = processor.extractFrame(job)
            resource.session.recheck()
            val declared = result.orThrow()
            staging.validateResult(declared)
            if (declared.actualFrameTime?.compareTo(frame.time) != 0 || declared.actualFrameIndex != frame.index || declared.actualFrameTrack != TrackId(frame.track.trackId.toString()) ||
                declared.execution.isEmpty() || declared.execution.none { it.stage == Stage.DecodeFrame } || declared.execution.none { it.stage == Stage.EncodeImage } ||
                declared.execution.any { it.stage !in setOf(Stage.DecodeFrame, Stage.EncodeImage) || it.transcoded || it.remuxed || it.backendId !in processor.capabilities().backendIds || it.inputFacts != videoFacts(resource.video) || it.outputFacts != declared.media.singleOrNull() } ||
                declared.tracks.isNotEmpty() || declared.timelineMap.isNotEmpty() || declared.issues.any { it.severity == Severity.Error })
                fail("POSTCONDITION_FAILED", "Backend declarations do not describe the selected derived frame", Stage.Verify)
            backendResult = declared
        }, verify = { id, reader ->
            val jpeg = JpegParser.parse(reader).orThrow()
            val facts = jpegFacts(reader, jpeg)
            val declared = backendResult ?: fail("POSTCONDITION_FAILED", "Missing frame backend result", Stage.Verify)
            val reported = declared.media.singleOrNull() ?: fail("POSTCONDITION_FAILED", "Frame must have one media-facts record", Stage.Verify)
            if (jpeg.trailing.length != 0uL || facts.width == null || facts.height == null || facts.issues.any { it.severity == Severity.Error } ||
                facts.width != reported.width || facts.height != reported.height || facts.width != frame.track.width || facts.height != frame.track.height ||
                reported.imageFormat != ImageFormat.Jpeg || reported.mime != spec.mime || reported.videoContainer != null || reported.duration != null || reported.tracks.isNotEmpty() ||
                reported.auxiliary.isNotEmpty() || declared.assets.single().byteLength != reader.identity().orThrow().size ||
                jpeg.segments.any { it.payloadKind in setOf(AppPayloadKind.Exif, AppPayloadKind.Xmp, AppPayloadKind.ExtendedXmp, AppPayloadKind.Mpf) })
                fail("POSTCONDITION_FAILED", "Derived frame bytes contradict output facts or carry stale bindings", Stage.Verify)
            val report = ValidationReport(Verdict.Warning, Coverage.Partial, listOf(
                CheckResult("frame.jpeg-structure", Layer.Structure, Verdict.Valid, Coverage.Complete),
                CheckResult("frame.backend-selection", Layer.Media, Verdict.Valid, Coverage.Partial)), snapshot = Snapshot(listOf(reader.identity().orThrow()), reader.identity().orThrow().generation))
            AssetVerification(report, listOf(
                GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable, proof = "A derived frame is explicitly encoded, not extracted encoded image bytes"),
                GuaranteeRecord(id, Guarantee.BitstreamPreserving, GuaranteeOutcome.NotApplicable, proof = "Source video is not an output asset and remains immutable"),
                GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.Changed, proof = "Explicit derived-image encoding"),
                GuaranteeRecord(id, Guarantee.MetadataPreserving, GuaranteeOutcome.Unknown, proof = "Source video metadata is not claimed to be transferred into the derived image")))
        })
        val result = publish(request.output, MutationPolicy(), request.context, resource.session.readers, listOf(asset), listOf(derivedChange())).orThrow()
        val declared = backendResult ?: fail("POSTCONDITION_FAILED", "Missing frame execution", Stage.Verify)
        FrameResult(result.copy(execution = declared.execution + result.execution, issues = result.issues + declared.issues), request.position, frame.time, frame.index, TrackId(frame.track.trackId.toString()))
    }

    private fun derivedChange(): Change = Change("derivedFrame", after = Value.Text("new-jpeg"), reason = "Explicitly requested frame decoding and image encoding; source video unchanged", requested = true)
}
