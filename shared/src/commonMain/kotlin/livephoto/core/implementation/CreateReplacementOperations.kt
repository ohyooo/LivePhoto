package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.memory.MemoryOutputTransaction

/** Resolve the replacement against original presentation order, before any private trim. */
internal object CreateReplacementOperations {
    private data class Prepared(val image: SourceSession, val video: VideoResource, val selected: SelectedFrame, val coding: Digest,
        val ordinary: Digest, val base: ExecutionPlan, val frameLimit: ULong)
    private suspend fun prepare(request: CreateRequest, backend: MediaBackend?, inheritedKey: KeyPhotoResult?): Prepared {
        RequestValidation.validate(request).orThrow()
        val replacement = request.edits?.replacementFrame ?: fail("INVALID_ARGUMENT", "Replacement edits required", Stage.Plan)
        if (request.target.protocol !in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2, ProtocolIds.Oplus, ProtocolIds.Samsung, ProtocolIds.VivoModern))
            fail("CAPABILITY_UNSUPPORTED", "Derived-image edits require an implemented JPEG target with classified key semantics", Stage.Plan)
        if (request.policy.preservation == PreservationPolicy.Strict || request.policy.requiredGuarantees.any { it in setOf(Guarantee.ImageDataPreserving, Guarantee.ExactExtraction) })
            fail("PRESERVATION_REQUIREMENT_FAILED", "Explicit replacement conflicts with strict/exact/image-data preservation", Stage.Plan)
        if (backend?.capabilities()?.operations?.none { it.operation == Operation.ExtractFrame && it.implementation in setOf(Implementation.Experimental, Implementation.Supported) } != false)
            fail("CAPABILITY_UNSUPPORTED", "Replacement edits require a verified JPEG frame backend", Stage.Plan)
        val plain = request.copy(edits = request.edits.copy(replacementFrame = null))
        val base = if (plain.edits?.trim != null) CreateTrimOperations.plan(plain, backend, inheritedKey).orThrow() else GoogleOperations.plan(plain).orThrow()
        val image = SourceSession.open(SourceSet.Single(request.image), request.context, ParseBudget(request.context)).orThrow()
        val jpeg = image.jpeg ?: fail("CAPABILITY_UNSUPPORTED", "Replacement requires a baseline source JPEG", Stage.Plan)
        val header = jpeg.segments.singleOrNull { it.marker == 0xc0 }?.payload ?: fail("HDR_PRESERVATION_UNAVAILABLE", "Unproved source image coding cannot be downgraded", Stage.Plan)
        val bytes = image.reader.readBuffer(header.offset, 6u).orThrow()
        if (bytes[0] != 8.toByte() || bytes[5].toInt() !in setOf(1, 3)) fail("HDR_PRESERVATION_UNAVAILABLE", "Only baseline eight-bit source primary images are admitted", Stage.Plan)
        if (image.bindings.isNotEmpty() || image.gainMaps.isNotEmpty() || jpeg.trailing.length != 0uL || image.inspection.issues.any { it.severity == Severity.Error } ||
            ordinaryDigest(image, verifiedJfifHeader = ReplaceOperations.canonicalJfif(image)?.range) != Sha256().finish())
            fail("UNSAFE_METADATA_REWRITE", "Replacement cannot silently discard source bindings, ordinary metadata, or auxiliary image dependencies", Stage.Plan)
        val video = VideoResource.open(ResourceRef(SourceSet.Single(request.video)), request.context)
        val selected = selectFrame(video.video, replacement)
        val limit = minOf(request.context.limits.maxMetadataBytes, request.context.limits.maxOutputBytes, request.context.limits.maxSpoolBytes / 16uL)
        if (limit == 0uL) fail("RESOURCE_LIMIT_EXCEEDED", "No bounded private replacement-image budget", Stage.Plan)
        image.recheck(); video.session.recheck()
        return Prepared(image, video, selected, codingDigest(image), ordinaryDigest(image), base, limit)
    }
    suspend fun plan(request: CreateRequest, backend: MediaBackend?, inheritedKey: KeyPhotoResult? = null): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request, backend, inheritedKey)
        prepared.base.copy(steps = listOf(PlanStep(Stage.DecodeFrame, listOf(Operation.ExtractFrame), emptyList(),
            "Select and encode the original source-domain replacement before trim; replacement never implicitly changes key")) + prepared.base.steps,
            predictedPreservation = prepared.base.predictedPreservation.copy(changes = listOf(imageChange(prepared.selected)) + prepared.base.predictedPreservation.changes),
            capabilities = CapabilitySet(Availability.Conditional, prepared.base.capabilities.operations + FrameOperations.capability(backend)))
    }
    suspend fun create(request: CreateRequest, backend: MediaBackend?, originalInputs: List<BinaryReader> = emptyList(), sourceChanges: List<Change> = emptyList(),
        inheritedKey: KeyPhotoResult? = null, metadataUnproven: Boolean = false): CoreResult<OperationResult> = attempt {
        val prepared = prepare(request, backend, inheritedKey)
        // Account for the live private frame even when the next operation also holds a private trimmed video.
        val retainedContext = request.context.copy(limits = request.context.limits.copy(maxSpoolBytes = request.context.limits.maxSpoolBytes - prepared.frameLimit * 8uL))
        val frameContext = retainedContext.copy(limits = retainedContext.limits.copy(maxOutputBytes = prepared.frameLimit))
        val frame = FrameOperations.extract(ExtractFrameRequest(ResourceRef(SourceSet.Single(prepared.video.reader.source)),
            CoverPosition.FrameIndex(prepared.selected.index, TrackId(prepared.selected.track.trackId.toString())), ImageEncoding(ImageFormat.Jpeg),
            MemoryOutputTransaction(frameContext, "create-frame-private:${prepared.video.session.snapshot.token.value}"), frameContext), backend).orThrow()
        try {
            prepared.image.recheck(); prepared.video.session.recheck()
            val derived = frame.operation.output.assets.single().readableSource ?: fail("POSTCONDITION_FAILED", "Unreadable private replacement frame", Stage.Verify)
            val image = SourceSession.open(SourceSet.Single(derived), retainedContext, ParseBudget(retainedContext)).orThrow()
            val ordinary = ordinaryDigest(image); val coding = codingDigest(image)
            if (ordinaryDigest(image, verifiedJfifHeader = ReplaceOperations.canonicalJfif(image)?.range) != Sha256().finish() ||
                prepared.ordinary != Sha256().finish() && prepared.ordinary != ordinary)
                fail("UNSAFE_METADATA_REWRITE", "Derived image cannot satisfy source metadata semantics", Stage.Verify)
            val added = prepared.ordinary != ordinary
            if (added && Guarantee.MetadataPreserving in request.policy.requiredGuarantees)
                fail("PRESERVATION_REQUIREMENT_FAILED", "Derived JPEG adds metadata; exact original metadata preservation is unproved", Stage.Verify)
            val changes = sourceChanges + imageChange(prepared.selected) + if (added) listOf(Change("primaryImage:JFIF", after = Value.Text("canonical-thumbnail-free-jfif"),
                reason = "Derived encoder added JFIF to a source without ordinary metadata", requested = true)) else emptyList()
            val create = request.copy(image = derived, edits = request.edits!!.copy(replacementFrame = null), context = retainedContext)
            val originals = originalInputs + prepared.image.readers + prepared.video.session.readers
            val result = if (create.edits?.trim != null) CreateTrimOperations.create(create, backend, originals, changes, inheritedKey, metadataUnproven || added).orThrow()
                else GoogleOperations.create(create, originals, changes, inheritedKey, metadataUnproven || added).orThrow()
            result.copy(preservation = result.preservation.copy(records = result.preservation.records.map { record -> when (record.guarantee) {
                Guarantee.ImageDataPreserving -> record.copy(outcome = GuaranteeOutcome.Changed, sourceDigest = prepared.coding, outputDigest = coding,
                    proof = "Explicit source-domain replacement; original image coding is not claimed unchanged")
                Guarantee.MetadataPreserving -> record.copy(sourceDigest = prepared.ordinary, outputDigest = ordinary,
                    proof = if (added) "Derived encoder added classified JFIF; no source metadata dropped, not exact metadata preservation" else "Original, derived, and final ordinary image metadata digests agree; opaque offset safety separately gated")
                else -> record
            } }), execution = frame.operation.execution.filter { it.stage in setOf(Stage.DecodeFrame, Stage.EncodeImage) } + result.execution,
                issues = frame.operation.issues + result.issues)
        } finally { frame.operation.output.assets.forEach { it.readableSource?.close() } }
    }
    private fun imageChange(frame: SelectedFrame): Change = Change("primaryImage", after = Value.ObjectValue(mapOf("kind" to Value.Text("derived-jpeg"),
        "sourceFrameIndex" to Value.Text(frame.index.toString()), "sourceTrackId" to Value.Text(frame.track.trackId.toString()),
        "sourceTime" to Value.ObjectValue(mapOf("value" to Value.Text(frame.time.value.toString()), "timescale" to Value.Number(frame.time.timescale.toString()))))),
        reason = "Explicit source-domain replacement, independent of source-domain key and trim", requested = true)
}
