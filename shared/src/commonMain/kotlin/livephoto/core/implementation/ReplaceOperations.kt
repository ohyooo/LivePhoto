package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.jpeg.*
import livephoto.core.memory.MemoryOutputTransaction
import livephoto.core.xml.*

/** Finite Google JPEG replacement, allowing only an exactly matched thumbnail-free canonical JFIF header. */
internal object ReplaceOperations {
    private data class Prepared(val resource: VideoResource, val target: ProtocolSelector, val selected: SelectedFrame, val originalCoding: Digest, val ordinary: Digest)
    private suspend fun prepare(request: ReplaceRequest, backend: MediaBackend?): Prepared {
        RequestValidation.validate(request).orThrow()
        if (request.policy.preservation == PreservationPolicy.Strict || request.policy.requiredGuarantees.any { it in setOf(Guarantee.ImageDataPreserving, Guarantee.ExactExtraction) })
            fail("PRESERVATION_REQUIREMENT_FAILED", "Replacing image coding conflicts with strict/exact/image-data preservation", Stage.Plan)
        if (request.encoding.format != ImageFormat.Jpeg || backend?.capabilities()?.operations?.none { it.operation == Operation.ExtractFrame && it.implementation in setOf(Implementation.Experimental, Implementation.Supported) } != false)
            fail("CAPABILITY_UNSUPPORTED", "Replace requires a configured JPEG frame backend", Stage.Plan)
        val caps = request.output.capabilities()
        if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic || request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
            fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Replace requires independently readable atomic output", Stage.Plan)
        val session = SourceSession.open(request.input, request.context, ParseBudget(request.context)).orThrow()
        val binding = session.bindings.singleOrNull()?.takeIf { it.protocol in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2) }
            ?: fail("CAPABILITY_UNSUPPORTED", "Replacement currently requires one Google JPEG protocol binding", Stage.Plan)
        val jpeg = session.jpeg ?: fail("CAPABILITY_UNSUPPORTED", "Replacement currently requires JPEG", Stage.Plan)
        val frameHeader = jpeg.segments.singleOrNull { it.marker == 0xc0 }?.payload
            ?: fail("HDR_PRESERVATION_UNAVAILABLE", "Only baseline eight-bit source JPEG has a proved SDR replacement profile", Stage.Plan)
        val frameBytes = session.reader.readBuffer(frameHeader.offset, 6u).orThrow()
        if (frameBytes[0] != 8.toByte() || frameBytes[5].toInt() !in setOf(1, 3))
            fail("HDR_PRESERVATION_UNAVAILABLE", "Source primary-image precision/color interpretation cannot be silently downgraded", Stage.Plan)
        if (session.readers.size != 1 || !binding.structurallyValid || binding.protocol !in session.videos || binding.video != jpeg.trailing || session.gainMaps.isNotEmpty() ||
            session.inspection.issues.any { it.severity == Severity.Error }) fail("UNSAFE_METADATA_REWRITE", "Replacement requires one complete unambiguous video suffix and no auxiliary dependencies", Stage.Plan)
        GoogleJpegWriter.cleanPlan(session, request.context).orThrow()
        val packet = session.xmp?.packets?.singleOrNull() ?: fail("UNSAFE_METADATA_REWRITE", "Replacement requires one standard complete XMP packet", Stage.Plan)
        if (packet.descriptions.any { description -> description.children.filterIsInstance<XmlElement>().any { field ->
                field.name.expanded in binding.ownedProperties && field.name.expanded != ExpandedName(CONTAINER_URI, "Directory") &&
                    (field.attributes.isNotEmpty() || field.children.any { it !is XmlText && it !is XmlCData }) } })
            fail("UNSAFE_METADATA_REWRITE", "Unclassified live-field qualifiers cannot be discarded during replacement", Stage.Plan)
        val jfif = canonicalJfif(session)
        if (ordinaryDigest(session, verifiedJfifHeader = jfif?.range) != Sha256().finish())
            fail("UNSAFE_METADATA_REWRITE", "Source ordinary metadata needs a verified transfer/update plan; it cannot be silently dropped or reused", Stage.Plan)
        val resource = VideoResource.open(ResourceRef(request.input, videoId(binding.protocol), session.snapshot), request.context)
        val selected = selectFrame(resource.video, request.position)
        if (request.updateKeyPosition) microseconds(selected.time)
        resource.session.recheck()
        return Prepared(resource, binding.selector, selected, codingDigest(session), ordinaryDigest(session))
    }
    suspend fun plan(request: ReplaceRequest, backend: MediaBackend?): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request, backend)
        ExecutionPlan(prepared.resource.session.snapshot, prepared.target, steps = listOf(
            PlanStep(Stage.DecodeFrame, listOf(Operation.ExtractFrame), listOf(videoId(prepared.target.protocol)), "Resolve source frame before any mutation and encode an isolated new primary image"),
            PlanStep(Stage.WriteProtocol, listOf(Operation.ReplaceCover), emptyList(), "Rebuild the same Google binding with final image dimensions/video length; update key only if requested"),
            PlanStep(Stage.Verify, emptyList(), emptyList(), "Verify new coding and byte-exact original video before the sole public commit")),
            predictedPreservation = PreservationReport(changes = listOf(imageChange(request))), capabilities = CapabilitySet(Availability.Conditional, listOf(capability())))
    }
    fun capability(): CapabilityEntry = CapabilityEntry(Operation.ReplaceCover, Implementation.Experimental,
        conditions = listOf(Condition(ConditionOperator.Equals, "source", Value.Text("single-google-baseline-eight-bit-jpeg-binding-no-ordinary-metadata-except-byte-matched-canonical-jfif-no-auxiliary-resources")),
            Condition(ConditionOperator.Equals, "backend", Value.Text("configured-verified-jpeg-extract-frame-profile")),
            Condition(ConditionOperator.Equals, "preservation", Value.Text("image-changed-by-request-video-exact-no-old-gainmap-icc-or-thumbnail-reuse"))), verification = listOf(Verification.SourceReviewed))
    suspend fun replace(request: ReplaceRequest, backend: MediaBackend?): CoreResult<OperationResult> = attempt {
        val prepared = prepare(request, backend); val resource = prepared.resource
        // This transaction is private memory only: no image is published to the caller/filesystem.
        // Its handles are released on every exit; the final destination gets one composite commit.
        // Conservative reserve for private memory snapshots in addition to backend temporary media.
        val frameLimit = minOf(request.context.limits.maxMetadataBytes, request.context.limits.maxOutputBytes, request.context.limits.maxSpoolBytes / 16uL)
        if (frameLimit == 0uL) fail("RESOURCE_LIMIT_EXCEEDED", "No bounded private-frame storage budget", Stage.Plan)
        val frameContext = request.context.copy(limits = request.context.limits.copy(maxOutputBytes = frameLimit,
            maxSpoolBytes = request.context.limits.maxSpoolBytes - frameLimit * 8uL))
        val privateOutput = MemoryOutputTransaction(frameContext, "replace-private:${resource.session.snapshot.token.value}")
        val frame = FrameOperations.extract(ExtractFrameRequest(ResourceRef(SourceSet.Single(resource.reader.source)),
            CoverPosition.FrameIndex(prepared.selected.index, TrackId(prepared.selected.track.trackId.toString())), request.encoding, privateOutput, frameContext), backend).orThrow()
        try {
            resource.session.recheck()
            val image = frame.operation.output.assets.single().readableSource ?: fail("POSTCONDITION_FAILED", "Private derived frame is not readable", Stage.Verify)
            val imageSession = SourceSession.open(SourceSet.Single(image), request.context, ParseBudget(request.context)).orThrow()
            val imageCoding = codingDigest(imageSession)
            val newOrdinary = ordinaryDigest(imageSession)
            // JFIF 1.02, no units, square density 1:1, no thumbnail: no old offsets or pixels reused.
            // Compare its complete raw bytes via the ordinary digest before final publication.
            if (ordinaryDigest(imageSession, verifiedJfifHeader = canonicalJfif(imageSession)?.range) != Sha256().finish() ||
                prepared.ordinary != Sha256().finish() && prepared.ordinary != newOrdinary)
                fail("UNSAFE_METADATA_REWRITE", "Derived image metadata cannot satisfy the classified source JFIF semantics", Stage.Verify)
            val metadataAdded = prepared.ordinary != newOrdinary
            if (metadataAdded && Guarantee.MetadataPreserving in request.policy.requiredGuarantees)
                fail("PRESERVATION_REQUIREMENT_FAILED", "Derived image introduces JFIF metadata; exact metadata preservation is not proved", Stage.Verify)
            val oldKey = resource.session.inspection.keyPhoto
            val edits = if (request.updateKeyPosition) EditSpec(keyPosition = CoverPosition.Timestamp(frame.actualTime, Selection.Exact)) else null
            val created = GoogleOperations.create(CreateRequest(image, resource.reader.source, prepared.target, edits = edits, policy = request.policy,
                output = request.output, context = request.context), originalInputs = resource.session.readers,
                sourceChanges = listOf(imageChange(request)) + if (metadataAdded) listOf(Change("primaryImage:JFIF", after = Value.Text("canonical-thumbnail-free-jfif"), reason = "Derived JPEG encoder introduced a new JFIF header; no original ordinary metadata was discarded", requested = true)) else emptyList(),
                sourceKey = if (request.updateKeyPosition) null else oldKey, metadataUnproven = metadataAdded).orThrow()
            val records = created.preservation.records.map { record -> when (record.guarantee) {
                Guarantee.ImageDataPreserving -> record.copy(outcome = GuaranteeOutcome.Changed, sourceDigest = prepared.originalCoding, outputDigest = imageCoding, proof = "Explicit replacement with the verified derived frame; no old auxiliary image reused")
                Guarantee.MetadataPreserving -> record.copy(sourceDigest = prepared.ordinary, outputDigest = newOrdinary,
                    proof = if (metadataAdded) "Source had no ordinary metadata; derived JPEG adds JFIF, not an exact source-metadata guarantee" else "Original ordinary metadata digest equals derived and final image metadata; opaque offset safety remains separately gated")
                else -> record
            } }
            created.copy(preservation = created.preservation.copy(records = records), execution = frame.operation.execution.filter { it.stage in setOf(Stage.DecodeFrame, Stage.EncodeImage) } + created.execution,
                issues = frame.operation.issues + created.issues)
        } finally { frame.operation.output.assets.forEach { it.readableSource?.close() } }
    }
    internal suspend fun canonicalJfif(session: SourceSession): JpegSegment? {
        val segment = session.jpeg!!.segments.singleOrNull { it.marker == 0xe0 } ?: return null
        val payload = segment.payload ?: return null
        val canonical = Bytes(byteArrayOf(0x4a, 0x46, 0x49, 0x46, 0, 1, 2, 0, 0, 1, 0, 1, 0, 0))
        return segment.takeIf { payload.length == 14uL && segment.range.length == 18uL && session.reader.readBuffer(payload.offset, 14u).orThrow() == canonical }
    }
    private fun imageChange(request: ReplaceRequest): Change = Change("primaryImage", after = Value.Text("derived-jpeg"),
        reason = if (request.updateKeyPosition) "Explicit image replacement and key synchronization" else "Explicit image replacement; original protocol key retained", requested = true)
}
