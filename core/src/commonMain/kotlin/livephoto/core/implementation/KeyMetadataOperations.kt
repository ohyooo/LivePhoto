package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.jpeg.*
import livephoto.core.xml.*
import livephoto.core.xmp.*

/** SetKey edits only protocol timestamps. It never asks a decoder for a new primary image. */
internal object KeyMetadataOperations {
    private data class Prepared(val session: SourceSession, val rewrite: JpegRewritePlan, val key: KeyPhotoResult, val changes: List<Change>, val video: ByteRange)

    suspend fun plan(request: SetKeyRequest): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request)
        val caps = request.output.capabilities()
        if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic || request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
            fail("ATOMIC_PUBLICATION_UNAVAILABLE", "SetKey requires verified atomic publication", Stage.Plan)
        prepared.session.recheck()
        ExecutionPlan(prepared.session.snapshot, prepared.session.inspection.detection.primaryProtocol,
            listOf(PlanStep(Stage.WriteProtocol, listOf(Operation.SetKey), prepared.session.inspection.layout.resources.map { it.id }, "Synchronize only authoritative key timestamps; retain image coding and complete video bytes")),
            PreservationReport(changes = prepared.changes), CapabilitySet(Availability.Conditional, listOf(CapabilityEntry(Operation.SetKey, Implementation.Experimental, verification = listOf(Verification.SourceReviewed)))))
    }

    suspend fun set(request: SetKeyRequest): CoreResult<OperationResult> = attempt {
        val prepared = prepare(request)
        val session = prepared.session
        val coding = codingDigest(session)
        val metadata = ordinaryDigest(session)
        val videoDigest = sha256Range(session.reader, prepared.video).orThrow()
        val timestamp = microseconds(prepared.key.position!!)
        val asset = StagedAsset(OutputAssetSpec(AssetRole.Composite, mime = "image/jpeg"), ImageFormat.Jpeg,
            write = { writer ->
                JpegRewrite.write(session.reader, writer, session.jpeg!!, prepared.rewrite, request.context).orThrow()
                copyRange(session.reader, writer, prepared.video, request.context).orThrow()
            },
            verify = { id, reader ->
                val staged = SourceSession.open(SourceSet.Single(reader.source), request.context, ParseBudget(request.context)).orThrow()
                val validation = validateSession(staged, listOf(Layer.Structure, Layer.Protocol)).orThrow()
                if (validation.verdict == Verdict.Invalid || validation.coverage != Coverage.Complete || staged.bindings.map { it.selector }.toSet() != session.bindings.map { it.selector }.toSet())
                    fail("POSTCONDITION_FAILED", "SetKey changed protocol authority or failed validation", Stage.Verify)
                val ranges = staged.bindings.mapNotNull { it.video }.distinct()
                if (ranges.size != 1 || staged.bindings.any { it.key.position?.compareTo(Time(timestamp, 1_000_000u)) != 0 })
                    fail("POSTCONDITION_FAILED", "SetKey timestamps were not synchronized", Stage.Verify)
                val outputVideo = sha256Range(reader, ranges.single()).orThrow()
                val outputCoding = codingDigest(staged)
                val outputMetadata = ordinaryDigest(staged)
                if (coding != outputCoding || metadata != outputMetadata || videoDigest != outputVideo)
                    fail("POSTCONDITION_FAILED", "SetKey changed image coding, video bytes, or ordinary metadata", Stage.Verify)
                AssetVerification(validation, listOf(
                    GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable),
                    GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.Verified, coding, outputCoding),
                    GuaranteeRecord(id, Guarantee.BitstreamPreserving, GuaranteeOutcome.Verified, videoDigest, outputVideo),
                    GuaranteeRecord(id, Guarantee.MetadataPreserving, if (opaqueOffsetsPreserved(session, staged)) GuaranteeOutcome.Verified else GuaranteeOutcome.Unknown, metadata, outputMetadata)),
                    staged.inspection.keyPhoto)
            })
        publish(request.output, request.policy, request.context, session.readers, listOf(asset), prepared.changes).orThrow()
    }

    private suspend fun prepare(request: SetKeyRequest): Prepared {
        RequestValidation.validate(request).orThrow()
        val session = SourceSession.open(request.input, request.context, ParseBudget(request.context)).orThrow()
        val jpeg = session.jpeg ?: fail("CAPABILITY_UNSUPPORTED", "SetKey currently requires a Google JPEG carrier", Stage.Plan)
        if (session.readers.size != 1 || session.bindings.isEmpty() || session.bindings.any { it.protocol !in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2) })
            fail("CAPABILITY_UNSUPPORTED", "Vendor key synchronization needs its own metadata writer", Stage.Plan)
        if (session.inspection.issues.any { it.severity == Severity.Error && it.code.value != "INVALID_PRESENTATION_TIMESTAMP" })
            fail("UNSAFE_METADATA_REWRITE", "Unrelated source errors cannot be repaired by SetKey", Stage.Plan)
        val ranges = session.bindings.mapNotNull { it.video }.distinct()
        if (ranges.size != 1 || session.bindings.any { it.protocol !in session.videos || it.padding?.length?.let { n -> n != 0uL } == true || it.items.size > 2 } || ranges.single() != jpeg.trailing || session.gainMaps.isNotEmpty())
            fail("UNSAFE_METADATA_REWRITE", "SetKey needs one complete video suffix with no auxiliary relocation", Stage.Plan)
        val xmp = session.xmp!!
        if (!xmp.rewriteAllowed) fail("UNSAFE_METADATA_REWRITE", "SetKey needs one unambiguous standard XMP packet", Stage.Plan)
        val key = selectKey(session.videos.values.first(), request.position)
        val timestamp = microseconds(key.position!!).toString()
        val updates = session.bindings.associate { binding -> ExpandedName(CAMERA_URI, if (binding.protocol == ProtocolIds.GoogleV1) "MicroVideoPresentationTimestampUs" else "MotionPhotoPresentationTimestampUs") to timestamp }
        val xml = XmpWriter.merge(xmp.packets.single(), updates, request.context).orThrow()
        val rewrite = GoogleJpegWriter.patch(jpeg, xml)
        val changes = updates.map { (name, value) -> Change("{${name.uri}}${name.local}", xmp.scalar(name.uri, name.local).orThrow()?.let(Value::Text), Value.Text(value), "Set requested key photo timestamp without replacing the primary image", true) }
        return Prepared(session, rewrite, key, changes, ranges.single())
    }
}
