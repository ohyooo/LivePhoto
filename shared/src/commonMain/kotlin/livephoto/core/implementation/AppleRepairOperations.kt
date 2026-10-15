package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.apple.*
import livephoto.core.binary.*

/** Explicit source-bound authority, fixed CID field only; never creates an ID or chooses by filename. */
internal object AppleRepairOperations {
    fun conditions(): List<Condition> = listOf(
        Condition(ConditionOperator.Equals, "repairMode", Value.Text("ExplicitRePair")),
        Condition(ConditionOperator.Equals, "authority", Value.Text("explicit-selected-pair-current-source-generation-formal-cid-inspection-evidence")),
        Condition(ConditionOperator.Equals, "rewriteScope", Value.Text("closed-cid-only-maker-note-and-dedicated-movie-bindings-fixed-width-36-or-37-byte-field-one-role-only-no-strict-opaque-metadata-association-proof")))
    private data class Prepared(
        val originals: List<BinaryReader>, val snapshot: Snapshot, val target: ProtocolSelector,
        val result: RepairResult, val pair: SourceSession? = null,
        val image: BinaryReader? = null, val video: BinaryReader? = null,
        val expected: List<Digest> = emptyList(), val changedImage: Boolean = false,
    )

    private suspend fun prepare(request: RepairRequest): Prepared {
        RequestValidation.validate(request).orThrow()
        if (request.mode != RepairMode.ExplicitRePair) fail("CAPABILITY_UNSUPPORTED", "Apple repair requires ExplicitRePair", Stage.Plan)
        val input = request.input as? SourceSet.Pair
            ?: fail("REPAIR_AMBIGUOUS", "Re-pair requires two explicitly selected image/video roles; candidates are not authority", Stage.Plan)
        if (request.context.limits.maxSources < 2u) fail("RESOURCE_LIMIT_EXCEEDED", "Pair exceeds source budget", Stage.Plan)
        if (request.authority != null && request.policy.authority != null && request.authority != request.policy.authority)
            fail("INVALID_ARGUMENT", "Repair and policy authorities disagree", Stage.Plan)
        val budget = ParseBudget(request.context)
        val image = SourceSession.open(SourceSet.Single(input.image), request.context, budget).orThrow()
        val video = SourceSession.open(SourceSet.Single(input.video), request.context, budget).orThrow()
        val originals = image.readers + video.readers
        val identities = originals.map { it.identity().orThrow() }
        if (identities.map { it.id }.distinct().size != 2) fail("INVALID_ARGUMENT", "Explicit pair sources must have distinct identities", Stage.Plan)
        val hash = Sha256()
        for (identity in identities) for (field in listOf(identity.id.value, identity.generation.value, identity.size.toString(), identity.digest?.value ?: "")) {
            val bytes = Bytes(field.encodeToByteArray())
            hash.update(unsignedBytes(bytes.size.toULong(), 8, Endian.Big)); hash.update(bytes)
        }
        val snapshot = Snapshot(identities, GenerationToken(hash.finish().value))
        val imageId = image.inspection.pairing?.imageIdentifier
            ?: fail("PAIR_ASSET_MISSING", "Selected primary has no formal Apple identifier", Stage.Plan)
        val videoId = video.inspection.pairing?.videoIdentifier
            ?: fail("PAIR_ASSET_MISSING", "Selected movie has no formal Apple identifier", Stage.Plan)
        val media = video.videos[ProtocolIds.Apple]
            ?: fail("CAPABILITY_UNSUPPORTED", "Re-pair requires independently parsed compatible movie facts", Stage.Plan)
        if (media.container !in setOf(VideoContainer.Mov, VideoContainer.Mp4) ||
            listOf(image, video).any { session -> session.bindings.any { it.protocol != ProtocolIds.Apple } ||
                session.inspection.issues.any { it.severity == Severity.Error && it.code.value != "PAIR_ASSET_MISSING" } })
            fail("REPAIR_NOT_POSSIBLE", "Re-pair cannot conceal unrelated protocol or media errors", Stage.Plan)
        val target = ProtocolSelector(ProtocolIds.Apple, ProfileId("${if (image.heifItems != null) "heic" else "jpeg"}-${if (media.container == VideoContainer.Mp4) "mp4" else "mov"}"))
        val evidence = listOf(image, video).map { it.inspection.pairing!!.evidence.singleOrNull()
            ?: fail("REPAIR_AMBIGUOUS", "One formally parsed evidence item per selected role is required", Stage.Plan) }
        val authority = request.authority ?: request.policy.authority
        val conflict = Issue(IssueCode("INVALID_PAIR_IDENTIFIER"), Severity.Error, Layer.Protocol,
            Location(selector = "apple:pair:content-identifier"), expected = Value.Text(imageId), observed = Value.Text(videoId),
            evidenceIds = evidence.map { it.id }, repairability = Repairability.Conditional)
        val ordinaryIssues = (image.inspection.issues + video.inspection.issues).filter { it.code.value != "PAIR_ASSET_MISSING" }
        val before = ordinaryIssues + if (imageId == videoId) emptyList() else listOf(conflict)
        if (imageId != videoId && request.allowedIssueCodes.isNotEmpty() && conflict.code !in request.allowedIssueCodes) {
            originals.forEach { it.validateIdentity().orThrow() }
            return Prepared(originals, snapshot, target, RepairResult(before, emptyList(), emptyList(), before, blocked = listOf(conflict)))
        }
        if (authority != null && authority !in evidence.map { it.id } || imageId != videoId &&
            (authority == null || request.policy.conflicts != ConflictPolicy.ExplicitAuthority))
            fail("REPAIR_AMBIGUOUS", "Conflicting CID needs explicit authority from one of the selected sources in its current generation", Stage.Plan)
        if (imageId == videoId) {
            val pair = SourceSession.open(input, request.context, budget).orThrow()
            verifiedPair(pair).orThrow()
            pair.recheck()
            originals.forEach { it.validateIdentity().orThrow() }
            return Prepared(originals, snapshot, target, RepairResult(pair.inspection.issues, emptyList(), emptyList(), pair.inspection.issues), pair)
        }
        val changedImage = authority == evidence[1].id
        val chosen = if (changedImage) videoId else imageId
        val field = if (changedImage) image.inspection.metadata.singleOrNull { it.selector == "apple:image:content-identifier" }
            else video.inspection.metadata.singleOrNull { it.selector == APPLE_CID }
        val range = field?.location?.range ?: fail("REPAIR_AMBIGUOUS", "Formal CID physical field is unavailable", Stage.Plan)
        val original = originals[if (changedImage) 0 else 1]
        // Both confirmed UUID profiles are ASCII, with either no terminator or one NUL. Preserve width.
        if (range.length !in setOf(36uL, 37uL)) fail("UNSAFE_METADATA_REWRITE", "Only fixed-width UUID fields with at most one terminator are writable", Stage.Plan)
        val raw = original.readExactly(range.offset, range.length.toUInt()).orThrow()
        val old = if (changedImage) imageId else videoId
        if (raw.slice(0, 36) != Bytes(old.encodeToByteArray()) || range.length == 37uL && raw[36] != 0.toByte())
            fail("UNSAFE_METADATA_REWRITE", "CID physical bytes do not match the bounded canonical field", Stage.Plan)
        val replacement = Bytes(chosen.encodeToByteArray() + if (range.length == 37uL) byteArrayOf(0) else byteArrayOf())
        val fixed = BinaryReader(FixedPatchSource.create(original, listOf(FixedPatch(range, replacement))).orThrow(), request.context)
        val imageView = if (changedImage) fixed else originals[0]
        val videoView = if (changedImage) originals[1] else fixed
        val pair = SourceSession.open(SourceSet.Pair(imageView.source, videoView.source), request.context, budget).orThrow()
        verifiedPair(pair).orThrow()
        // Existing closed ownership/dependency proof authorizes the smaller CID-only patch.
        // Its cleanup views are never published; ordinary MakerNote/keys/tracks remain forbidden here.
        AppleClean.prepare(pair, budget).orThrow()
        if (pair.videos[ProtocolIds.Apple]?.tracks != media.tracks || pair.inspection.keyPhoto.position != video.inspection.keyPhoto.position)
            fail("POSTCONDITION_FAILED", "CID patch changed movie tracks or presentation key", Stage.Verify)
        if (request.policy.preservation == PreservationPolicy.Strict || Guarantee.MetadataPreserving in request.policy.requiredGuarantees || Guarantee.ExactExtraction in request.policy.requiredGuarantees)
            fail("PRESERVATION_REQUIREMENT_FAILED", "Rewritten CID is not exact extraction; opaque identifier associations lack a strict metadata proof", Stage.Plan)
        val total = checkedAdd(identities[0].size, identities[1].size)
        if (total > request.context.limits.maxOutputBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Re-pair exceeds shared output budget", Stage.Plan)
        if (!request.dryRun) {
            val caps = request.output!!.capabilities()
            if (!caps.assetSetAtomic || !caps.canReadStaged || request.policy.atomicity != Atomicity.AssetSetRequired ||
                request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
                fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Re-pair requires verified complete asset-set publication", Stage.Plan)
        }
        val expected = listOf(imageView, videoView).map { sha256Range(it, ByteRange(0uL, it.identity().orThrow().size)).orThrow() }
        for (reader in originals) reader.validateIdentity().orThrow()
        val change = Change(if (changedImage) "apple:image:content-identifier" else APPLE_CID, Value.Text(old), Value.Text(chosen),
            "Use explicit current-generation CID authority; modify only the other role's fixed field; capture provenance remains unproven", true)
        return Prepared(originals, snapshot, target, RepairResult(before, listOf(change), emptyList(), before), pair,
            imageView, videoView, expected, changedImage)
    }

    private fun verifiedPair(session: SourceSession): CoreResult<ValidationReport> = attemptNow {
        val report = validateSession(session, listOf(Layer.Structure, Layer.Protocol)).orThrow()
        val required = listOf("bmff.samples", "apple.pair", "apple.media-profile") +
            if (session.heifItems != null) listOf("heif.item-locations", "heif.item-graph") else listOf("jpeg.markers", "jpeg.frame")
        if (required.any { name -> report.checks.singleOrNull { it.id == name }?.let { it.verdict == Verdict.Valid && it.coverage == Coverage.Complete } != true } || report.issues.any { it.severity == Severity.Error })
            fail("POSTCONDITION_FAILED", "Re-pair requires complete relevant structure/protocol checks without concealing partial generic metadata coverage", Stage.Verify)
        report
    }

    suspend fun plan(request: RepairRequest): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request)
        prepared.originals.forEach { it.validateIdentity().orThrow() }
        ExecutionPlan(prepared.snapshot, prepared.target, listOf(PlanStep(if (request.dryRun) Stage.Plan else Stage.WriteProtocol,
            listOf(Operation.Repair), emptyList(), "Explicit fixed-width CID authority; independently verify both assets before one atomic publication")),
            PreservationReport(changes = prepared.result.proposedChanges), CapabilitySet(if (prepared.result.blocked.isEmpty()) Availability.Conditional else Availability.Unsupported,
                listOf(DefaultLivePhotoCore().getProtocolCapabilities(prepared.target).operations.single { it.operation == Operation.Repair }), prepared.result.blocked))
    }

    suspend fun repair(request: RepairRequest): CoreResult<RepairResult> = attempt {
        val prepared = prepare(request)
        if (request.dryRun || prepared.result.blocked.isNotEmpty() || prepared.result.proposedChanges.isEmpty()) return@attempt prepared.result
        val readers = listOf(prepared.image!!, prepared.video!!)
        val pair = prepared.pair!!
        var primaryId: AssetId? = null
        var primaryIdentity: SourceIdentity? = null
        var after: List<Issue> = emptyList()
        suspend fun verifyBytes(index: Int, reader: BinaryReader) {
            if (reader.identity().orThrow().size != readers[index].identity().orThrow().size ||
                sha256Range(reader, ByteRange(0uL, reader.identity().orThrow().size)).orThrow() != prepared.expected[index])
                fail("POSTCONDITION_FAILED", "Re-pair changed bytes outside its one authorized fixed field", Stage.Verify)
        }
        fun guarantees(id: AssetId, index: Int): List<GuaranteeRecord> {
            val changed = prepared.changedImage == (index == 0)
            return listOf(
                GuaranteeRecord(id, Guarantee.ExactExtraction, if (changed) GuaranteeOutcome.NotApplicable else GuaranteeOutcome.Verified, proof = if (changed) "Requested CID changes this asset" else "Whole unchanged asset SHA-256 verified"),
                GuaranteeRecord(id, Guarantee.ImageDataPreserving, if (index == 0) GuaranteeOutcome.Verified else GuaranteeOutcome.NotApplicable, proof = "No coding bytes relocated or changed"),
                GuaranteeRecord(id, Guarantee.BitstreamPreserving, if (index == 1) GuaranteeOutcome.Verified else GuaranteeOutcome.NotApplicable, proof = "Complete samples/configuration/timeline retained; only CID field may differ"),
                GuaranteeRecord(id, Guarantee.MetadataPreserving, if (changed) GuaranteeOutcome.Unknown else GuaranteeOutcome.Verified,
                    proof = if (changed) "Every unrequested byte remains at its offset, but opaque CID associations are unproven" else "Whole asset unchanged"))
        }
        val image = StagedAsset(OutputAssetSpec(AssetRole.PrimaryImage, mime = pair.inspection.media.first().mime!!), imageFormat = pair.inspection.media.first().imageFormat,
            write = { writer -> copyRange(readers[0], writer, ByteRange(0uL, readers[0].identity().orThrow().size), request.context).orThrow() },
            verify = { id, reader ->
                verifyBytes(0, reader)
                val inspected = SourceSession.open(SourceSet.Single(reader.source), request.context, ParseBudget(request.context)).orThrow()
                if (inspected.inspection.pairing?.imageIdentifier != pair.inspection.pairing?.imageIdentifier)
                    fail("POSTCONDITION_FAILED", "Prepared primary lost its authorized CID", Stage.Verify)
                primaryId = id; primaryIdentity = reader.identity().orThrow()
                AssetVerification(ValidationReport(Verdict.Valid, Coverage.Complete,
                    listOf(CheckResult("apple.cid.primary", Layer.Structure, Verdict.Valid, Coverage.Complete)), snapshot = inspected.snapshot), guarantees(id, 0))
            })
        val media = pair.videos.getValue(ProtocolIds.Apple)
        val video = StagedAsset(OutputAssetSpec(AssetRole.MotionVideo, mime = videoFacts(media).mime!!), container = media.container,
            write = { writer -> copyRange(readers[1], writer, ByteRange(0uL, readers[1].identity().orThrow().size), request.context).orThrow() },
            verify = { id, reader ->
                verifyBytes(1, reader)
                val source = request.output!!.openStaged(primaryId ?: fail("POSTCONDITION_FAILED", "Primary must verify first", Stage.Verify)).orThrow()
                var alias = false
                try {
                    val primary = BinaryReader(source, request.context)
                    val identity = primary.identity().orThrow()
                    if ((prepared.originals + readers).any { it.source === source || it.identity().orThrow().id == identity.id }) {
                        alias = true; fail("OUTPUT_ALIASES_INPUT", "Joint verifier aliases input", Stage.Verify)
                    }
                    if (identity != primaryIdentity) fail("POSTCONDITION_FAILED", "Primary generation changed during joint verification", Stage.Verify)
                    verifyBytes(0, primary)
                    val staged = SourceSession.open(SourceSet.Pair(source, reader.source), request.context, ParseBudget(request.context)).orThrow()
                    val report = verifiedPair(staged).orThrow()
                    if (staged.inspection.detection.primaryProtocol != prepared.target ||
                        staged.inspection.pairing?.copy(evidence = emptyList()) != pair.inspection.pairing?.copy(evidence = emptyList()) ||
                        staged.videos[ProtocolIds.Apple]?.tracks != media.tracks || staged.inspection.keyPhoto.position != pair.inspection.keyPhoto.position)
                        fail("POSTCONDITION_FAILED", "Joint re-pair verification changed media, key, profile or identifiers", Stage.Verify)
                    val known = prepared.result.issuesBefore.map { Triple(it.code, it.layer, it.severity) }.toSet()
                    if (staged.inspection.issues.any { Triple(it.code, it.layer, it.severity) !in known })
                        fail("POSTCONDITION_FAILED", "Re-pair introduced a new inspection issue", Stage.Verify)
                    after = staged.inspection.issues
                    AssetVerification(report, guarantees(id, 1), staged.inspection.keyPhoto)
                } finally { if (!alias) source.close() }
            })
        val operation = publish(request.output!!, request.policy, request.context, prepared.originals + readers, listOf(image, video), prepared.result.proposedChanges).orThrow()
        prepared.result.copy(changesApplied = prepared.result.proposedChanges, issuesAfter = after, operation = operation)
    }
}
