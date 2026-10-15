package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.jpeg.*
import livephoto.core.xml.*
import livephoto.core.xmp.*
import livephoto.core.vivo.*
import livephoto.core.oplus.*

/** SetKey edits only protocol timestamps. It never asks a decoder for a new primary image. */
internal object KeyMetadataOperations {
    private data class Prepared(val session: SourceSession, val rewrite: JpegRewritePlan, val key: KeyPhotoResult, val changes: List<Change>, val video: ByteRange)

    suspend fun plan(request: SetKeyRequest): CoreResult<ExecutionPlan> = attempt {
        RequestValidation.validate(request).orThrow()
        val session = SourceSession.open(request.input, request.context, ParseBudget(request.context)).orThrow()
        if (session.applePair != null) return@attempt AppleKeyOperations.plan(request, session).orThrow()
        if (session.heifItems != null) return@attempt GoogleHeicKeyOperations.plan(request, session).orThrow()
        val prepared = prepare(request, session)
        val caps = request.output.capabilities()
        if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic || request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
            fail("ATOMIC_PUBLICATION_UNAVAILABLE", "SetKey requires verified atomic publication", Stage.Plan)
        prepared.session.recheck()
        ExecutionPlan(prepared.session.snapshot, prepared.session.inspection.detection.primaryProtocol,
            listOf(PlanStep(Stage.WriteProtocol, listOf(Operation.SetKey), prepared.session.inspection.layout.resources.map { it.id }, "Synchronize only authoritative key timestamps; retain image coding and complete video bytes")),
            PreservationReport(changes = prepared.changes), CapabilitySet(Availability.Conditional, listOf(CapabilityEntry(Operation.SetKey, Implementation.Experimental, verification = listOf(Verification.SourceReviewed)))))
    }

    suspend fun set(request: SetKeyRequest): CoreResult<OperationResult> = attempt {
        RequestValidation.validate(request).orThrow()
        val source = SourceSession.open(request.input, request.context, ParseBudget(request.context)).orThrow()
        if (source.applePair != null) return@attempt AppleKeyOperations.set(request, source).orThrow()
        if (source.heifItems != null) return@attempt GoogleHeicKeyOperations.set(request, source).orThrow()
        val prepared = prepare(request, source)
        val session = prepared.session
        val coding = codingDigest(session)
        val metadata = ordinaryDigest(session)
        val videoDigest = sha256Range(session.reader, prepared.video).orThrow()
        val suffixDigest = sha256Range(session.reader, session.jpeg!!.trailing).orThrow()
        val timestamp = microseconds(prepared.key.position!!)
        val asset = StagedAsset(OutputAssetSpec(AssetRole.Composite, mime = "image/jpeg"), ImageFormat.Jpeg,
            write = { writer ->
                JpegRewrite.write(session.reader, writer, session.jpeg, prepared.rewrite, request.context).orThrow()
                copyRange(session.reader, writer, session.jpeg.trailing, request.context).orThrow()
            },
            verify = { id, reader ->
                val staged = SourceSession.open(SourceSet.Single(reader.source), request.context, ParseBudget(request.context)).orThrow()
                val validation = validateSession(staged, listOf(Layer.Structure, Layer.Protocol)).orThrow()
                if (validation.verdict == Verdict.Invalid || validation.coverage != Coverage.Complete || staged.bindings.map { it.selector }.toSet() != session.bindings.map { it.selector }.toSet())
                    fail("POSTCONDITION_FAILED", "SetKey changed protocol authority or failed validation", Stage.Verify)
                val ranges = staged.bindings.mapNotNull { it.video }.distinct()
                if (ranges.size != 1 || staged.bindings.any { it.key.position?.compareTo(Time(timestamp, 1_000_000u)) != 0 })
                    fail("POSTCONDITION_FAILED", "SetKey timestamps were not synchronized", Stage.Verify)
                // Ordinary metadata hashes intentionally exclude owned protocol properties.
                // Verify the complete planned packet too, including untouched vendor identity
                // and directory fields, before allowing publication.
                val packet = staged.jpeg!!.segments.singleOrNull { it.payloadKind == AppPayloadKind.Xmp }
                    ?: fail("POSTCONDITION_FAILED", "SetKey lost its unique XMP packet", Stage.Verify)
                if (reader.readExactly(packet.range.offset, checkedInt(packet.range.length).toUInt()).orThrow() != prepared.rewrite.patches.single().replacement)
                    fail("POSTCONDITION_FAILED", "SetKey changed metadata outside the planned timestamp edit", Stage.Verify)
                val outputVideo = sha256Range(reader, ranges.single()).orThrow()
                if (sha256Range(reader, staged.jpeg.trailing).orThrow() != suffixDigest)
                    fail("POSTCONDITION_FAILED", "SetKey changed the complete media/trailer suffix", Stage.Verify)
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

    private suspend fun prepare(request: SetKeyRequest, session: SourceSession): Prepared {
        val jpeg = session.jpeg ?: fail("CAPABILITY_UNSUPPORTED", "SetKey currently requires a supported JPEG carrier", Stage.Plan)
        if (session.readers.size != 1 || session.bindings.isEmpty() || session.bindings.any { it.protocol !in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2, ProtocolIds.VivoModern, ProtocolIds.Oplus, ProtocolIds.Samsung) })
            fail("CAPABILITY_UNSUPPORTED", "Vendor key synchronization needs its own metadata writer", Stage.Plan)
        if (session.inspection.issues.any { it.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNKNOWN_PROTOCOL_VARIANT", "UNSUPPORTED_CONTAINER") })
            fail("CAPABILITY_UNSUPPORTED", "SetKey cannot edit an incompletely understood source profile", Stage.Plan)
        val vendors = session.bindings.filter { it.protocol in setOf(ProtocolIds.VivoModern, ProtocolIds.Oplus, ProtocolIds.Samsung) }
        if (vendors.size > 1) fail("CAPABILITY_UNSUPPORTED", "Mixed vendor timestamp profiles need explicit interpretation", Stage.Plan)
        val vendor = vendors.singleOrNull()
        val samsung = vendor?.protocol == ProtocolIds.Samsung
        if (samsung) {
            val sef = session.sef ?: fail("CAPABILITY_UNSUPPORTED", "Samsung SetKey needs a verified JPEG SEF graph", Stage.Plan)
            if (sef.legacyDialect || sef.gaps.isNotEmpty() || sef.records.size != 2 || sef.motionRecord == null || sef.versionRecord == null ||
                session.bindings.map { it.protocol }.toSet() != setOf(ProtocolIds.Samsung, ProtocolIds.GoogleV2) || vendor.items.size != 2)
                fail("CAPABILITY_UNSUPPORTED", "Samsung SetKey requires canonical live-only SEF and an existing V2 directory", Stage.Plan)
        }
        fun acceptedSamsungSuffix(issue: Issue): Boolean = samsung && issue.code.value == "MOTION_VIDEO_LENGTH_MISMATCH" && issue.layer == Layer.Protocol &&
            issue.location?.range == session.bindings.single { it.protocol == ProtocolIds.GoogleV2 }.items.lastOrNull()?.range
        fun acceptedVendorPadding(issue: Issue): Boolean = vendor != null &&
            issue.code.value == "MALFORMED_XMP" && issue.layer == Layer.Protocol &&
            issue.location?.selector == "{$ITEM_URI}Padding" &&
            // The vendor parser must have accepted this exact base-directory dialect.
            vendor.issues.none { it == issue }
        fun editableKeyIssue(issue: Issue): Boolean = issue.code.value == "INVALID_PRESENTATION_TIMESTAMP" &&
            issue.location?.selector in setOf("{$CAMERA_URI}MicroVideoPresentationTimestampUs", "{$CAMERA_URI}MotionPhotoPresentationTimestampUs")
        if (session.inspection.issues.any { it.severity == Severity.Error && !editableKeyIssue(it) && !acceptedVendorPadding(it) && !acceptedSamsungSuffix(it) })
            fail("UNSAFE_METADATA_REWRITE", "Unrelated source errors cannot be repaired by SetKey", Stage.Plan)
        val ranges = session.bindings.mapNotNull { it.video }.distinct()
        if (ranges.size != 1 || session.bindings.any { it.protocol !in session.videos || it.padding?.length?.let { n -> n != 0uL && !(samsung && n == 24uL) } == true || it.items.size > 2 } ||
            (if (samsung) ranges.single() != session.sef?.pureVideoRange || ranges.single().offset != jpeg.primary.endExclusive + 24uL else ranges.single() != jpeg.trailing) || session.gainMaps.isNotEmpty())
            fail("UNSAFE_METADATA_REWRITE", "SetKey needs one complete video suffix with no auxiliary relocation", Stage.Plan)
        val xmp = session.xmp!!
        if (!xmp.rewriteAllowed) fail("UNSAFE_METADATA_REWRITE", "SetKey needs one unambiguous standard XMP packet", Stage.Plan)
        val vendorUri = if (vendor?.protocol == ProtocolIds.Oplus) OPLUS_URI else VIVO_URI
        val vendorFields = if (vendor?.protocol == ProtocolIds.Oplus) OPLUS_FIELDS else VIVO_FIELDS
        if (vendor != null && !samsung && xmp.packets.single().descriptions.any { description ->
                (description.attributes.map { it.name.expanded } + description.children.filterIsInstance<XmlElement>().map { it.name.expanded })
                    .any { it.uri == vendorUri && it.local !in vendorFields }
            }) fail("CAPABILITY_UNSUPPORTED", "Unknown vendor fields may change timestamp semantics", Stage.Plan)
        val key = selectKey(session.videos.values.first(), request.position)
        val timestamp = microseconds(key.position!!).toString()
        val updates = session.bindings.associate { binding -> ExpandedName(CAMERA_URI, if (binding.protocol == ProtocolIds.GoogleV1) "MicroVideoPresentationTimestampUs" else "MotionPhotoPresentationTimestampUs") to timestamp }
        val xml = XmpWriter.merge(xmp.packets.single(), updates, request.context).orThrow()
        val rewrite = GoogleJpegWriter.patch(jpeg, xml)
        val changes = updates.map { (name, value) -> Change("{${name.uri}}${name.local}", xmp.scalar(name.uri, name.local).orThrow()?.let(Value::Text), Value.Text(value), "Set requested key photo timestamp without replacing the primary image", true) }
        return Prepared(session, rewrite, key, changes, ranges.single())
    }
}
