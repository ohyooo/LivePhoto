package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.*
import livephoto.core.xml.*
import livephoto.core.xmp.*
import livephoto.core.jpeg.*

/** Empty issue filters allow every proven-safe repair; dryRun remains independently enforced. */
internal object RepairOperations {
    private data class Prepared(val session: SourceSession, val result: RepairResult, val rewrite: JpegRewritePlan? = null)
    suspend fun repair(request: RepairRequest): CoreResult<RepairResult> = attempt {
        val prepared = prepare(request)
        val result = prepared.result
        if (request.dryRun || result.blocked.isNotEmpty() || result.proposedChanges.isEmpty()) return@attempt result
        val session = prepared.session
        val video = session.jpeg!!.trailing
        val videoHash = sha256Range(session.reader, video).orThrow()
        val imageHash = codingDigest(session)
        val metadataHash = ordinaryDigest(session)
        var after: List<Issue> = emptyList()
        val asset = StagedAsset(OutputAssetSpec(AssetRole.Composite, mime = "image/jpeg"), ImageFormat.Jpeg,
            write = { writer ->
                JpegRewrite.write(session.reader, writer, session.jpeg, prepared.rewrite!!, request.context).orThrow()
                copyRange(session.reader, writer, video, request.context).orThrow()
            },
            verify = { id, reader ->
                val staged = SourceSession.open(SourceSet.Single(reader.source), request.context, ParseBudget(request.context)).orThrow()
                val report = validateSession(staged, listOf(Layer.Structure, Layer.Protocol)).orThrow()
                val binding = staged.bindings.singleOrNull()
                if (report.verdict == Verdict.Invalid || report.coverage != Coverage.Complete || binding?.protocol != ProtocolIds.GoogleV1 ||
                    !binding.structurallyValid || binding.video != staged.jpeg?.trailing || binding.protocol !in staged.videos)
                    fail("POSTCONDITION_FAILED", "Repaired carrier failed complete structural and protocol validation", Stage.Verify)
                for (field in V1_FIELDS - "MicroVideoOffset") if (session.xmp!!.scalar(CAMERA_URI, field).orThrow() != staged.xmp!!.scalar(CAMERA_URI, field).orThrow())
                    fail("POSTCONDITION_FAILED", "Repair changed an unrequested protocol field", Stage.Verify)
                val outVideo = sha256Range(reader, binding.video!!).orThrow()
                val outImage = codingDigest(staged)
                val outMetadata = ordinaryDigest(staged)
                if (outVideo != videoHash || outImage != imageHash || outMetadata != metadataHash)
                    fail("POSTCONDITION_FAILED", "Repair changed media bytes or ordinary metadata", Stage.Verify)
                val originalIssues = result.issuesBefore.map { Triple(it.code, it.layer, it.severity) }.toSet()
                if (staged.inspection.issues.any { Triple(it.code, it.layer, it.severity) !in originalIssues })
                    fail("POSTCONDITION_FAILED", "Repair introduced a new inspection issue", Stage.Verify)
                after = staged.inspection.issues
                AssetVerification(report, listOf(
                    GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable),
                    GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.Verified, imageHash, outImage),
                    GuaranteeRecord(id, Guarantee.BitstreamPreserving, GuaranteeOutcome.Verified, videoHash, outVideo),
                    GuaranteeRecord(id, Guarantee.MetadataPreserving, if (opaqueOffsetsPreserved(session, staged)) GuaranteeOutcome.Verified else GuaranteeOutcome.Unknown, metadataHash, outMetadata)), staged.inspection.keyPhoto)
            })
        val operation = publish(request.output!!, request.policy, request.context, session.readers, listOf(asset), result.proposedChanges).orThrow()
        result.copy(changesApplied = result.proposedChanges, issuesAfter = after, operation = operation)
    }

    suspend fun plan(request: RepairRequest): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request)
        val blocked = prepared.result.blocked
        if (!request.dryRun && blocked.isEmpty() && prepared.rewrite != null) {
            val caps = request.output!!.capabilities()
            if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic || request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
                fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Repair requires verified atomic publication", Stage.Plan)
        }
        prepared.session.recheck()
        ExecutionPlan(prepared.session.snapshot, prepared.session.bindings.single().selector,
            listOf(PlanStep(if (request.dryRun) Stage.Plan else Stage.WriteProtocol, listOf(Operation.Repair), prepared.session.inspection.layout.resources.map { it.id }, if (request.dryRun) "Read-only unique-offset preview; no staging or publication" else "Repair only the proven offset; validate staging before atomic publication")),
            PreservationReport(changes = prepared.result.proposedChanges), CapabilitySet(if (blocked.isEmpty()) Availability.Conditional else Availability.Unsupported,
                listOf(CapabilityEntry(Operation.Repair, if (blocked.isEmpty()) Implementation.Experimental else Implementation.Unsupported,
                    conditions = listOf(Condition(ConditionOperator.Equals, "repairScope", Value.Text("unique-google-v1-offset"))), reasons = blocked.map { it.code })), blocked))
    }

    private suspend fun prepare(request: RepairRequest): Prepared {
        RequestValidation.validate(request).orThrow()
        if (request.mode != RepairMode.SafeMetadataOnly || request.authority != null || request.policy.authority != null)
            fail("CAPABILITY_UNSUPPORTED", "This preview cannot remux, re-pair, or select conflicting authority", Stage.Plan)
        if (request.input !is SourceSet.Single) fail("REPAIR_AMBIGUOUS", "Offset preview requires one explicit carrier", Stage.Plan)
        val budget = ParseBudget(request.context)
        val session = SourceSession.open(request.input, request.context, budget, probeEmbeddedVideo = false).orThrow()
        val jpeg = session.jpeg ?: fail("REPAIR_NOT_POSSIBLE", "Offset preview requires a parsed JPEG", Stage.Plan)
        if (session.bindings.size != 1 || session.bindings.single().protocol != ProtocolIds.GoogleV1 || session.gainMaps.isNotEmpty())
            fail("REPAIR_AMBIGUOUS", "Preview requires only the Google V1 authority and no auxiliary resource graph", Stage.Plan)
        val xmp = session.xmp!!
        if (!xmp.rewriteAllowed || xmp.scalar(CAMERA_URI, "MicroVideo").orThrow() != "1" || xmp.scalar(CAMERA_URI, "MicroVideoVersion").orThrow() != "1")
            fail("REPAIR_AMBIGUOUS", "Preview cannot choose duplicate, qualified, or unknown protocol authority", Stage.Plan)
        val field = "MicroVideoOffset"
        val old = xmp.scalar(CAMERA_URI, field).orThrow()
        val sourceIssues = session.inspection.issues
        if (sourceIssues.any { it.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNKNOWN_PROTOCOL_VARIANT", "CONFLICTING_METADATA") })
            fail("REPAIR_AMBIGUOUS", "Unknown or qualified metadata cannot authorize an offset proposal", Stage.Plan)
        val independentErrors = sourceIssues.filter { it.severity == Severity.Error && it.code.value !in setOf("MOTION_VIDEO_LENGTH_MISMATCH", "OFFSET_OUT_OF_BOUNDS", "MISSING_REQUIRED_XMP", "MALFORMED_XMP") }
        if (independentErrors.isNotEmpty()) return Prepared(session, RepairResult(sourceIssues, emptyList(), emptyList(), sourceIssues, blocked = independentErrors))
        if (jpeg.trailing.length == 0uL) fail("REPAIR_NOT_POSSIBLE", "JPEG has no complete physical suffix", Stage.Plan)
        // No byte scanning or magic guesses: only the exact post-EOI extent is eligible.
        val candidate = when (val result = BmffVideoProbe(session.reader, budget).probe(jpeg.trailing)) {
            is CoreResult.Success -> result.value
            is CoreResult.Failure -> {
                if (result.error.code.value in setOf("CANCELLED", "SOURCE_CHANGED", "IO_READ_FAILED", "RESOURCE_LIMIT_EXCEEDED")) throw CoreFault(result.error)
                throw CoreFault(CoreError(IssueCode("REPAIR_NOT_POSSIBLE"), Stage.Plan, "The whole post-JPEG extent is not one verified video",
                    details = mapOf("cause" to Value.Text(result.error.code.value))))
            }
        }
        if (googleVideoIssues(candidate, session.bindings.single().selector, false).isNotEmpty()) fail("REPAIR_NOT_POSSIBLE", "Physical suffix does not satisfy source profile", Stage.Plan)
        val rawKey = xmp.scalar(CAMERA_URI, "MicroVideoPresentationTimestampUs").orThrow()
        if (rawKey != null && rawKey != "-1") {
            val ticks = rawKey.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toLongOrNull()
            val end = candidate.tracks.filter { it.handler == "vide" }.maxOf { it.presentationDuration }
            if (ticks == null || Time(ticks, 1_000_000u) >= end) {
                val invalidKey = Issue(IssueCode("INVALID_PRESENTATION_TIMESTAMP"), Severity.Error, Layer.Protocol,
                    Location(selector = "{$CAMERA_URI}MicroVideoPresentationTimestampUs"), observed = Value.Text(rawKey))
                val before = sourceIssues + invalidKey
                return Prepared(session, RepairResult(before, emptyList(), emptyList(), before, blocked = listOf(invalidKey)))
            }
        }
        val target = jpeg.trailing.length.toString()
        if (old == target) {
            val normal = SourceSession.open(request.input, request.context, budget).orThrow()
            return Prepared(normal, RepairResult(normal.inspection.issues, emptyList(), emptyList(), normal.inspection.issues))
        }
        val issue = Issue(IssueCode(if (old == null) "MISSING_REQUIRED_XMP" else "MOTION_VIDEO_LENGTH_MISMATCH"), Severity.Error, Layer.Protocol,
            Location(source = session.snapshot.identities.single().id, range = jpeg.trailing, selector = "{$CAMERA_URI}$field"),
            expected = Value.Text(target), observed = old?.let(Value::Text), repairability = Repairability.Safe)
        val before = sourceIssues + issue
        val repairedCodes = session.bindings.single().issues.filter { it.code.value in setOf("MOTION_VIDEO_LENGTH_MISMATCH", "OFFSET_OUT_OF_BOUNDS", "MISSING_REQUIRED_XMP", "MALFORMED_XMP") }.map { it.code } + issue.code
        if (request.allowedIssueCodes.isNotEmpty() && repairedCodes.none { it in request.allowedIssueCodes })
            return Prepared(session, RepairResult(before, emptyList(), emptyList(), before, blocked = listOf(issue)))
        val replacement = XmpWriter.merge(xmp.packets.single(), mapOf(ExpandedName(CAMERA_URI, field) to target), request.context).orThrow()
        // Even a preview must pass the existing EXIF/MPF/XMP relocation gate.
        val rewrite = GoogleJpegWriter.patch(jpeg, replacement)
        session.recheck()
        val change = Change("{$CAMERA_URI}$field", old?.let(Value::Text), Value.Text(target), "Unique verified complete video starts exactly after JPEG; offset is its byte length", true)
        return Prepared(session, RepairResult(before, listOf(change), emptyList(), before), rewrite)
    }
}
