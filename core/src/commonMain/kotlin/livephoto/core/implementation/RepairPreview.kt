package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.*
import livephoto.core.xml.*
import livephoto.core.xmp.*

/** Read-only preview; applying repairs remains separately gated, never implicit from a proposal. */
internal object RepairPreview {
    private data class Prepared(val session: SourceSession, val result: RepairResult)
    suspend fun preview(request: RepairRequest): CoreResult<RepairResult> = attempt { prepare(request).result }

    suspend fun plan(request: RepairRequest): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request)
        val blocked = prepared.result.blocked
        prepared.session.recheck()
        ExecutionPlan(prepared.session.snapshot, prepared.session.bindings.single().selector,
            listOf(PlanStep(Stage.Plan, listOf(Operation.Repair), prepared.session.inspection.layout.resources.map { it.id }, "Read-only unique-offset preview; no staging or publication")),
            PreservationReport(changes = prepared.result.proposedChanges), CapabilitySet(if (blocked.isEmpty()) Availability.Conditional else Availability.Unsupported,
                listOf(CapabilityEntry(Operation.Repair, if (blocked.isEmpty()) Implementation.Experimental else Implementation.Unsupported,
                    conditions = listOf(Condition(ConditionOperator.Equals, "dryRun", Value.BooleanValue(true))), reasons = blocked.map { it.code })), blocked))
    }

    private suspend fun prepare(request: RepairRequest): Prepared {
        RequestValidation.validate(request).orThrow()
        if (!request.dryRun) fail("CAPABILITY_PLANNED", "Repair application is not enabled; use the read-only preview", Stage.Plan)
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
        if (request.allowedIssueCodes.isNotEmpty() && issue.code !in request.allowedIssueCodes)
            return Prepared(session, RepairResult(before, emptyList(), emptyList(), before, blocked = listOf(issue)))
        val replacement = XmpWriter.merge(xmp.packets.single(), mapOf(ExpandedName(CAMERA_URI, field) to target), request.context).orThrow()
        // Even a preview must pass the existing EXIF/MPF/XMP relocation gate.
        GoogleJpegWriter.patch(jpeg, replacement)
        session.recheck()
        val change = Change("{$CAMERA_URI}$field", old?.let(Value::Text), Value.Text(target), "Unique verified complete video starts exactly after JPEG; offset is its byte length", true)
        return Prepared(session, RepairResult(before, listOf(change), emptyList(), before))
    }
}
