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
    private data class Prepared(val session: SourceSession, val result: RepairResult, val rewrite: JpegRewritePlan? = null,
        val fixed: BinarySource? = null, val protocol: ProtocolId = ProtocolIds.GoogleV1)
    suspend fun repair(request: RepairRequest): CoreResult<RepairResult> = attempt {
        val prepared = prepare(request)
        val result = prepared.result
        if (request.dryRun || result.blocked.isNotEmpty() || result.proposedChanges.isEmpty()) return@attempt result
        val session = prepared.session
        val video = if (prepared.protocol == ProtocolIds.Samsung) session.sef!!.pureVideoRange!! else session.jpeg!!.trailing
        val expectedReader = prepared.fixed?.let { BinaryReader(it, request.context) }
        val expectedHash = expectedReader?.let { sha256Range(it, ByteRange(0uL, it.identity().orThrow().size)).orThrow() }
        val videoHash = sha256Range(session.reader, video).orThrow()
        val imageHash = codingDigest(session)
        val metadataHash = ordinaryDigest(session)
        var after: List<Issue> = emptyList()
        val asset = StagedAsset(OutputAssetSpec(AssetRole.Composite, mime = "image/jpeg"), ImageFormat.Jpeg,
            write = { writer ->
                if (expectedReader != null) copyRange(expectedReader, writer, ByteRange(0uL, expectedReader.identity().orThrow().size), request.context).orThrow()
                else {
                    JpegRewrite.write(session.reader, writer, session.jpeg!!, prepared.rewrite!!, request.context).orThrow()
                    copyRange(session.reader, writer, video, request.context).orThrow()
                }
            },
            verify = { id, reader ->
                val staged = SourceSession.open(SourceSet.Single(reader.source), request.context, ParseBudget(request.context)).orThrow()
                val report = validateSession(staged, listOf(Layer.Structure, Layer.Protocol)).orThrow()
                val binding = staged.bindings.singleOrNull { it.protocol == prepared.protocol }
                if (report.verdict == Verdict.Invalid || report.coverage != Coverage.Complete || binding == null ||
                    !binding.structurallyValid || (prepared.protocol == ProtocolIds.GoogleV1 && binding.video != staged.jpeg?.trailing) || binding.protocol !in staged.videos ||
                    session.bindings.map { it.selector }.toSet() != staged.bindings.map { it.selector }.toSet())
                    fail("POSTCONDITION_FAILED", "Repaired carrier failed complete structural and protocol validation", Stage.Verify)
                if (expectedHash != null && sha256Range(reader, ByteRange(0uL, reader.identity().orThrow().size)).orThrow() != expectedHash)
                    fail("POSTCONDITION_FAILED", "Repair changed bytes outside the proven fixed-width patch", Stage.Verify)
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
        if (!request.dryRun && blocked.isEmpty() && (prepared.rewrite != null || prepared.fixed != null)) {
            val caps = request.output!!.capabilities()
            if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic || request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
                fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Repair requires verified atomic publication", Stage.Plan)
        }
        prepared.session.recheck()
        ExecutionPlan(prepared.session.snapshot, prepared.session.bindings.single { it.protocol == prepared.protocol }.selector,
            listOf(PlanStep(if (request.dryRun) Stage.Plan else Stage.WriteProtocol, listOf(Operation.Repair), prepared.session.inspection.layout.resources.map { it.id }, if (request.dryRun) "Read-only proven-metadata preview; no staging or publication" else "Repair only proven metadata; validate staging before atomic publication")),
            PreservationReport(changes = prepared.result.proposedChanges), CapabilitySet(if (blocked.isEmpty()) Availability.Conditional else Availability.Unsupported,
                listOf(CapabilityEntry(Operation.Repair, if (blocked.isEmpty()) Implementation.Experimental else Implementation.Unsupported,
                    conditions = listOf(Condition(ConditionOperator.Equals, "repairScope", Value.Text(if (prepared.protocol == ProtocolIds.Samsung) "unique-sef-legacy-footer-length" else "unique-google-v1-offset"))), reasons = blocked.map { it.code })), blocked))
    }

    private suspend fun prepare(request: RepairRequest): Prepared {
        RequestValidation.validate(request).orThrow()
        if (request.mode != RepairMode.SafeMetadataOnly || request.authority != null || request.policy.authority != null)
            fail("CAPABILITY_UNSUPPORTED", "This preview cannot remux, re-pair, or select conflicting authority", Stage.Plan)
        if (request.input !is SourceSet.Single) fail("REPAIR_AMBIGUOUS", "Offset preview requires one explicit carrier", Stage.Plan)
        val budget = ParseBudget(request.context)
        val session = SourceSession.open(request.input, request.context, budget, probeEmbeddedVideo = false).orThrow()
        val jpeg = session.jpeg ?: fail("REPAIR_NOT_POSSIBLE", "Offset preview requires a parsed JPEG", Stage.Plan)
        if (session.bindings.any { it.protocol == ProtocolIds.Samsung }) return prepareSamsung(request)
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

    private suspend fun prepareSamsung(request: RepairRequest): Prepared {
        val session = SourceSession.open(request.input, request.context, ParseBudget(request.context)).orThrow()
        val sef = session.sef ?: fail("REPAIR_NOT_POSSIBLE", "Repair requires a uniquely parsed SEF footer", Stage.Plan)
        if (session.bindings.any { it.protocol !in setOf(ProtocolIds.Samsung, ProtocolIds.GoogleV2) } || session.gainMaps.isNotEmpty() ||
            sef.gaps.isNotEmpty() || sef.version != 107u || sef.versionRecord == null || ProtocolIds.Samsung !in session.videos)
            fail("REPAIR_AMBIGUOUS", "SEF repair needs a complete unique record graph and verified video", Stage.Plan)
        val sourceIssues = session.inspection.issues
        val scoped = validateSession(session, listOf(Layer.Structure, Layer.Protocol), target = session.bindings.single { it.protocol == ProtocolIds.Samsung }.selector).orThrow()
        val footerIssues = scoped.issues.filter { it.code.value == "SEF_DIRECTORY_INVALID" && it.location?.range == sef.footer && it.observed == Value.Text("legacy-footer-inclusive") }
        val unrelated = scoped.issues.filter { it.severity == Severity.Error && it !in footerIssues || it.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNKNOWN_PROTOCOL_VARIANT") }
        if (unrelated.isNotEmpty()) return Prepared(session, RepairResult(sourceIssues, emptyList(), emptyList(), sourceIssues, blocked = unrelated), protocol = ProtocolIds.Samsung)
        if (!sef.legacyDialect) return Prepared(session, RepairResult(sourceIssues, emptyList(), emptyList(), sourceIssues), protocol = ProtocolIds.Samsung)
        if (footerIssues.isEmpty()) fail("REPAIR_AMBIGUOUS", "No specific legacy footer issue proves this repair", Stage.Plan)
        if (request.allowedIssueCodes.isNotEmpty() && IssueCode("SEF_DIRECTORY_INVALID") !in request.allowedIssueCodes)
            return Prepared(session, RepairResult(sourceIssues, emptyList(), emptyList(), sourceIssues, blocked = footerIssues), protocol = ProtocolIds.Samsung)
        val fixed = FixedPatchSource.create(session.reader, listOf(FixedPatch(ByteRange(sef.footer.offset, 4uL), unsignedBytes(sef.table.length, 4, Endian.Little)))).orThrow()
        val projected = SourceSession.open(SourceSet.Single(fixed), request.context, ParseBudget(request.context)).orThrow()
        val projectedSef = projected.sef ?: fail("REPAIR_NOT_POSSIBLE", "Canonical footer lost its SEF graph", Stage.Plan)
        val verified = validateSession(projected, listOf(Layer.Structure, Layer.Protocol)).orThrow()
        if (verified.verdict == Verdict.Invalid || verified.coverage != Coverage.Complete || projectedSef.legacyDialect ||
            projectedSef.records != sef.records || projectedSef.pureVideoRange != sef.pureVideoRange)
            fail("REPAIR_NOT_POSSIBLE", "Canonical footer patch does not preserve a valid indexed record graph", Stage.Plan)
        session.recheck()
        val change = Change("samsung:SEFT:directoryLength", Value.Number((sef.table.length + 8uL).toString()), Value.Number(sef.table.length.toString()),
            "Unique fully indexed SEF graph proves the legacy footer incorrectly includes its own eight bytes", true)
        return Prepared(session, RepairResult(sourceIssues, listOf(change), emptyList(), sourceIssues), fixed = fixed, protocol = ProtocolIds.Samsung)
    }
}
