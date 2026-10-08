package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.*
import livephoto.core.jpeg.*
import livephoto.core.memory.MemoryOutputTransaction
import livephoto.core.samsung.*

/** One evidenced container correction, not arbitrary media recovery. Intermediate assets stay private. */
internal object RemuxRepairOperations {
    private data class Prepared(val session: SourceSession, val binding: CarrierBinding, val image: BinarySource,
        val movie: BinarySource, val rawKey: String, val key: KeyPhotoResult, val result: RepairResult)

    private suspend fun prepare(request: RepairRequest, backend: MediaBackend?): Prepared {
        RequestValidation.validate(request).orThrow()
        if (request.mode != RepairMode.ExplicitRemux || request.authority != null || request.policy.authority != null || request.input !is SourceSet.Single)
            fail("REPAIR_AMBIGUOUS", "Explicit remux requires one unambiguous existing carrier without selected authority", Stage.Plan)
        if (Guarantee.ExactExtraction in request.policy.requiredGuarantees)
            fail("PRESERVATION_REQUIREMENT_FAILED", "Container repair cannot preserve the entire original carrier byte-exactly", Stage.Plan)
        val budget = ParseBudget(request.context)
        val session = SourceSession.open(request.input, request.context, budget).orThrow()
        val binding = session.bindings.singleOrNull { it.protocol == ProtocolIds.Samsung }
            ?: fail("CAPABILITY_UNSUPPORTED", "Explicit remux currently requires canonical Samsung JPEG SEF mpv3", Stage.Plan)
        val jpeg = session.jpeg ?: fail("CAPABILITY_UNSUPPORTED", "HEIC container repair is not implemented", Stage.Plan)
        val directory = session.sef ?: fail("REPAIR_NOT_POSSIBLE", "No independently indexed SEF movie", Stage.Plan)
        if (!binding.structurallyValid || binding.profile != ProfileId("jpeg-sef-mpv3") ||
            session.bindings.any { it.protocol !in setOf(ProtocolIds.Samsung, ProtocolIds.GoogleV2) } ||
            session.bindings.count { it.protocol == ProtocolIds.GoogleV2 } != 1 || session.gainMaps.isNotEmpty() ||
            directory.legacyDialect || directory.gaps.isNotEmpty() || directory.records.size != 2 ||
            directory.records.any { it.prefix != 0u.toUShort() || it.type !in setOf(0x0a30u.toUShort(), 0x0a31u.toUShort()) } ||
            jpeg.segments.any { it.payloadKind in setOf(AppPayloadKind.Exif, AppPayloadKind.Mpf, AppPayloadKind.ExtendedXmp) } ||
            session.xmp?.rewriteAllowed != true)
            fail("REPAIR_AMBIGUOUS", "Container repair refuses unknown authority, legacy/ordinary SEF, auxiliary or opaque offset metadata", Stage.Plan)
        val jfif = ReplaceOperations.canonicalJfif(session)
        if (jpeg.segments.any { it.marker in 0xe0..0xef && it.payloadKind == AppPayloadKind.Unknown && it != jfif })
            fail("UNSAFE_METADATA_REWRITE", "Container repair cannot relocate unclassified APP metadata", Stage.Plan)
        val movieRange = binding.video ?: fail("MOTION_VIDEO_MISSING", "No exact indexed video", Stage.Plan)
        val video = session.videos[binding.protocol] ?: fail("REPAIR_NOT_POSSIBLE", "Indexed movie is not independently valid", Stage.Plan)
        if (video.container !in setOf(VideoContainer.Mp4, VideoContainer.Mov))
            fail("CAPABILITY_UNSUPPORTED", "Unknown containers cannot authorize a correction", Stage.Plan)
        val rawKey = session.xmp.scalar(CAMERA_URI, "MotionPhotoPresentationTimestampUs").orThrow()
            ?: fail("REPAIR_AMBIGUOUS", "Container correction requires an existing explicit key field", Stage.Plan)
        val key = if (rawKey == "-1") KeyPhotoResult(source = KeySource.Unknown) else {
            val ticks = rawKey.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
                ?: fail("REPAIR_AMBIGUOUS", "Unknown timestamp semantics cannot be rewritten", Stage.Plan)
            if (ticks.toString() != rawKey) fail("UNSAFE_METADATA_REWRITE", "Container correction does not normalize timestamp spelling", Stage.Plan)
            KeyPhotoResult(Time(ticks, 1_000_000u), source = KeySource.ProtocolField)
        }
        val before = session.inspection.issues
        val independent = before.filter { issue -> !(issue.code.value == "UNSUPPORTED_CONTAINER" && issue.layer == Layer.Protocol && issue.severity == Severity.Warning) &&
            !(issue.code.value == "MALFORMED_XMP" && issue.layer == Layer.Compatibility && issue.severity == Severity.Warning && issue.location?.selector == "{$ITEM_URI}Padding") &&
            !compatibleBaseLengthIssue(session, issue) }
        val issue = before.firstOrNull { it.code.value == "UNSUPPORTED_CONTAINER" && it.layer == Layer.Protocol }
        val changes = if (video.container == VideoContainer.Mov) listOf(Change("videoContainer", Value.Text("Mov"), Value.Text("Mp4"),
            "Existing Samsung mpv3 requires MP4; preserve every encoded sample/configuration/timestamp and rebuild owned lengths", true)) else emptyList()
        val blocked = independent + if (changes.isNotEmpty() && request.allowedIssueCodes.isNotEmpty() && IssueCode("UNSUPPORTED_CONTAINER") !in request.allowedIssueCodes)
            listOf(issue ?: Issue(IssueCode("UNSUPPORTED_CONTAINER"), Severity.Warning, Layer.Protocol)) else emptyList()
        val clean = SamsungJpegWriter.cleanPlan(session, request.context, budget).orThrow()
        if (clean.suffix.length != 0uL) fail("UNSAFE_METADATA_REWRITE", "Ordinary SEF cannot be discarded during repair", Stage.Plan)
        val image = JpegProjectionSource.create(session, clean.image).orThrow()
        val cleanSession = SourceSession.open(SourceSet.Single(image), request.context, budget).orThrow()
        if (cleanSession.bindings.isNotEmpty() || cleanSession.jpeg?.trailing?.length != 0uL || !opaqueOffsetsPreserved(session, cleanSession) ||
            codingDigest(session) != codingDigest(cleanSession) || ordinaryDigest(session) != ordinaryDigest(cleanSession))
            fail("POSTCONDITION_FAILED", "Clean image projection changed unrequested image or metadata", Stage.Plan)
        val movie = RangeSource(session.reader, movieRange)
        if (blocked.isEmpty() && changes.isNotEmpty()) {
            requireGoogleWriteVideo(video.copy(container = VideoContainer.Mp4), binding.selector)
            val limit = privateContext(request).limits.maxOutputBytes
            if (movieRange.length > limit) fail("RESOURCE_LIMIT_EXCEEDED", "Movie exceeds bounded private remux storage", Stage.Plan)
            val privateOutput = MemoryOutputTransaction(privateContext(request), "repair-remux-plan")
            // This classifies all original metadata and checks backend availability without executing it.
            RemuxOperations.plan(RemuxRequest(ResourceRef(SourceSet.Single(movie)), VideoContainer.Mp4,
                request.policy, privateOutput, privateContext(request)), backend).orThrow()
            if (!request.dryRun) checkOutput(request)
        }
        session.recheck()
        return Prepared(session, binding, image, movie, rawKey, key,
            RepairResult(before, changes, emptyList(), before, blocked = blocked))
    }

    private fun privateContext(request: RepairRequest): Context {
        val limit = minOf(request.context.limits.maxOutputBytes, request.context.limits.maxSpoolBytes / 16uL)
        if (limit == 0uL) fail("RESOURCE_LIMIT_EXCEEDED", "No bounded private remux budget", Stage.Plan)
        return request.context.copy(limits = request.context.limits.copy(maxOutputBytes = limit,
            maxSpoolBytes = request.context.limits.maxSpoolBytes - limit * 8uL))
    }

    /** Retain this diagnostic in reports. Samsung owns pure video; its Google-compatible D includes SEF. */
    private fun compatibleBaseLengthIssue(session: SourceSession, issue: Issue): Boolean {
        val directory = session.sef ?: return false
        val range = directory.pureVideoRange ?: return false
        val base = session.bindings.singleOrNull { it.protocol == ProtocolIds.GoogleV2 } ?: return false
        val vendor = session.bindings.singleOrNull { it.protocol == ProtocolIds.Samsung } ?: return false
        return vendor.structurallyValid && base.compatibleBaseOf == ProtocolIds.Samsung && base.video == range &&
            issue in base.issues && issue.code.value == "MOTION_VIDEO_LENGTH_MISMATCH" && issue.layer == Layer.Protocol &&
            issue.location?.range == ByteRange(range.offset, directory.footer.endExclusive - range.offset)
    }

    private suspend fun checkOutput(request: RepairRequest) {
        val output = request.output ?: fail("INVALID_ARGUMENT", "Repair application needs output", Stage.Plan)
        val caps = output.capabilities()
        if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic ||
            request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
            fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Container repair requires readable atomic publication", Stage.Plan)
        val initial = output.query().orThrow()
        if (initial.state != TransactionState.Open || initial.assetIds.isNotEmpty())
            fail("INVALID_ARGUMENT", "Repair output must be empty and open", Stage.Plan)
    }

    suspend fun plan(request: RepairRequest, backend: MediaBackend?): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request, backend)
        ExecutionPlan(prepared.session.snapshot, prepared.binding.selector,
            listOf(PlanStep(Stage.Plan, listOf(Operation.Repair), listOf(videoId(ProtocolIds.Samsung)),
                "Read-only canonical Samsung container correction preview; no backend execution or staging")) +
                if (!request.dryRun && prepared.result.blocked.isEmpty() && prepared.result.proposedChanges.isNotEmpty()) listOf(
                    PlanStep(Stage.Remux, listOf(Operation.Remux), listOf(videoId(ProtocolIds.Samsung)), "Privately streamcopy MOV to MP4; never encode"),
                    PlanStep(Stage.WriteProtocol, listOf(Operation.Repair), emptyList(), "Rebuild only owned binding; verify final media, image, metadata and key before one public commit")) else emptyList(),
            predictedPreservation = PreservationReport(changes = prepared.result.proposedChanges),
            capabilities = CapabilitySet(if (prepared.result.blocked.isEmpty()) Availability.Conditional else Availability.Unsupported,
                listOf(CapabilityEntry(Operation.Repair, if (prepared.result.blocked.isEmpty()) Implementation.Experimental else Implementation.Unsupported,
                    conditions = listOf(Condition(ConditionOperator.Equals, "repairScope", Value.Text("canonical-jpeg-sef-mpv3-mov-to-mp4-classified-metadata-private-remux-no-encoding"))))), prepared.result.blocked),
            issues = prepared.result.issuesBefore)
    }

    suspend fun repair(request: RepairRequest, backend: MediaBackend?): CoreResult<RepairResult> = attempt {
        val prepared = prepare(request, backend)
        if (request.dryRun || prepared.result.blocked.isNotEmpty() || prepared.result.proposedChanges.isEmpty()) return@attempt prepared.result
        val context = privateContext(request)
        val privateOutput = MemoryOutputTransaction(context, "repair-remux-private:${prepared.session.snapshot.token.value}")
        val remuxed = RemuxOperations.remux(RemuxRequest(ResourceRef(SourceSet.Single(prepared.movie)), VideoContainer.Mp4,
            request.policy, privateOutput, context), backend).orThrow()
        try {
            prepared.session.recheck()
            val movie = remuxed.output.assets.single().readableSource ?: fail("POSTCONDITION_FAILED", "Private remux result is unreadable", Stage.Verify)
            val proof = remuxed.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }
            if (proof.outcome != GuaranteeOutcome.Verified) fail("POSTCONDITION_FAILED", "Private remux did not preserve samples", Stage.Verify)
            var after: List<Issue> = emptyList()
            val originalCoding = codingDigest(prepared.session)
            val originalMetadata = ordinaryDigest(prepared.session)
            val operation = GoogleOperations.create(CreateRequest(prepared.image, movie, prepared.binding.selector,
                policy = request.policy, output = request.output!!, context = request.context), prepared.session.readers,
                prepared.result.proposedChanges, prepared.key, verifyAdditional = { staged ->
                    if (staged.xmp?.scalar(CAMERA_URI, "MotionPhotoPresentationTimestampUs")?.orThrow() != prepared.rawKey ||
                        codingDigest(staged) != originalCoding || ordinaryDigest(staged) != originalMetadata || !opaqueOffsetsPreserved(prepared.session, staged) ||
                        staged.inspection.issues.any { it.code.value == "UNSUPPORTED_CONTAINER" && it.layer == Layer.Protocol } ||
                        staged.inspection.issues.any { issue -> issue.severity == Severity.Error && !compatibleBaseLengthIssue(staged, issue) || prepared.result.issuesBefore.none { it.code == issue.code && it.layer == issue.layer && it.severity == issue.severity } })
                        fail("POSTCONDITION_FAILED", "Container correction changed the key/image/ordinary metadata or introduced an issue", Stage.Verify)
                    after = staged.inspection.issues
                }).orThrow()
            val changes = prepared.result.proposedChanges + operation.preservation.changes.filter { it.selector != "videoContainer" }
            val verified = operation.copy(preservation = operation.preservation.copy(changes = changes,
                records = operation.preservation.records.map { record -> if (record.guarantee == Guarantee.BitstreamPreserving)
                    record.copy(sourceDigest = proof.sourceDigest, outputDigest = proof.outputDigest,
                        proof = "All original encoded samples/configuration/timestamps/metadata verified by private remux; entire resulting MP4 embedded byte-identically") else record }),
                execution = remuxed.execution.filter { it.stage == Stage.Remux } + operation.execution)
            prepared.result.copy(proposedChanges = changes, changesApplied = changes, issuesAfter = after, operation = verified)
        } finally { remuxed.output.assets.forEach { it.readableSource?.close() } }
    }
}
