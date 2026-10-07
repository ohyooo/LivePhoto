package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.heif.*
import livephoto.core.xmp.*

/** A finite proof: replace only an equal-width canonical directory length, never relocate or guess media. */
internal object GoogleHeicRepairOperations {
    private val target = ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("heic"))
    private data class Prepared(val session: SourceSession, val result: RepairResult, val fixed: BinaryReader?, val expected: Digest?)
    private suspend fun prepare(request: RepairRequest, session: SourceSession, budget: ParseBudget): Prepared {
        val binding = session.bindings.singleOrNull { it.selector == target }
            ?: fail("REPAIR_AMBIGUOUS", "HEIC repair needs exactly one Google V2 heic authority", Stage.Plan)
        if (session.bindings.size != 1) fail("REPAIR_AMBIGUOUS", "HEIC repair cannot choose competing authorities", Stage.Plan)
        val video = binding.video ?: fail("REPAIR_NOT_POSSIBLE", "No unique standard final mpvd provides an independent complete movie range", Stage.Plan)
        val before = session.inspection.issues
        val unrelated = before.filter { it.severity == Severity.Error && it.code != IssueCode("MOTION_VIDEO_LENGTH_MISMATCH") }
        if (unrelated.isNotEmpty()) return Prepared(session, RepairResult(before, emptyList(), emptyList(), before, blocked = unrelated), null, null)
        val graph = session.heifItems!!
        val metadata = graph.infos.singleOrNull { it.id != graph.primary && it.type == "mime" }
            ?: fail("REPAIR_AMBIGUOUS", "HEIC repair needs a unique linked owned metadata item", Stage.Plan)
        val extent = graph.locations.items.single { it.id == metadata.id }.extents.singleOrNull()?.data
            ?: fail("CAPABILITY_UNSUPPORTED", "HEIC repair only implements a single contiguous metadata extent", Stage.Plan)
        val xmp = session.xmp ?: fail("REPAIR_NOT_POSSIBLE", "HEIC repair needs parsed XMP", Stage.Plan)
        val packet = xmp.packets.singleOrNull() ?: fail("REPAIR_AMBIGUOUS", "HEIC repair cannot select among XMP packets", Stage.Plan)
        val item = packet.properties(CONTAINER_URI, "Directory").singleOrNull()?.element?.elements(RDF_URI, "Seq")?.singleOrNull()
            ?.elements(RDF_URI, "li")?.lastOrNull()?.elements(CONTAINER_URI, "Item")?.singleOrNull()
            ?: fail("REPAIR_AMBIGUOUS", "HEIC repair needs the canonical inline motion directory", Stage.Plan)
        val old = item.attribute(ITEM_URI, "Length") ?: fail("REPAIR_NOT_POSSIBLE", "HEIC repair does not infer a missing length", Stage.Plan)
        val oldLength = old.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toULongOrNull()
            ?: fail("REPAIR_AMBIGUOUS", "Unknown length lexical semantics cannot authorize repair", Stage.Plan)
        val rawKey = xmp.scalar(CAMERA_URI, "MotionPhotoPresentationTimestampUs").orThrow()
            ?: fail("REPAIR_AMBIGUOUS", "HEIC repair does not infer a missing key", Stage.Plan)
        val key = rawKey.takeIf { it == "-1" || it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
            ?: fail("REPAIR_AMBIGUOUS", "HEIC repair does not guess key units or malformed timestamps", Stage.Plan)
        val previous = GoogleDirectoryWriter.heic(oldLength, key, request.context)
        if (extent.length != previous.size.toULong() || session.reader.readExactly(extent.offset, previous.size.toUInt()).orThrow() != previous)
            fail("UNSAFE_METADATA_REWRITE", "Mixed, qualified or unknown XMP cannot authorize a whole-packet length repair", Stage.Plan)
        val replacement = GoogleDirectoryWriter.heic(video.length, key, request.context)
        if (replacement.size != previous.size) fail("CAPABILITY_UNSUPPORTED", "HEIC repair currently requires unchanged decimal/table widths", Stage.Plan)
        val fixed = BinaryReader(FixedPatchSource.create(session.reader, listOf(FixedPatch(extent, replacement))).orThrow(), request.context)
        val repaired = SourceSession.open(SourceSet.Single(fixed.source), request.context, budget).orThrow()
        val duration = repaired.videos[target.protocol]?.tracks?.filter { it.handler == "vide" }?.maxOfOrNull { it.presentationDuration }
            ?: fail("REPAIR_NOT_POSSIBLE", "Length repair needs an independently verified movie timeline", Stage.Plan)
        if (key != -1L && Time(key, 1_000_000u) >= duration)
            fail("REPAIR_NOT_POSSIBLE", "Length repair cannot repair an unrelated invalid key timestamp", Stage.Plan)
        // Use the same independently classified ownership graph as Clean, without deleting anything.
        HeifMotionCleanup.prepare(repaired, budget).orThrow()
        val report = validateSession(repaired, listOf(Layer.Structure, Layer.Protocol), target = target).orThrow()
        if (report.verdict == Verdict.Invalid || report.checks.filter { it.layer == Layer.Protocol }.any { it.coverage != Coverage.Complete || it.verdict != Verdict.Valid })
            fail("REPAIR_NOT_POSSIBLE", "Length-only view cannot satisfy complete protocol checks", Stage.Plan)
        val added = repaired.inspection.issues.filter { it !in before }
        if (added.any { it.severity == Severity.Error }) fail("POSTCONDITION_FAILED", "Length repair introduced an unrelated error", Stage.Plan)
        session.recheck()
        if (old == video.length.toString()) return Prepared(session, RepairResult(before, emptyList(), emptyList(), before), null, null)
        val repairedIssues = before.filter { it.code == IssueCode("MOTION_VIDEO_LENGTH_MISMATCH") }
        if (repairedIssues.isEmpty()) fail("REPAIR_NOT_POSSIBLE", "No independently reported length problem is repairable", Stage.Plan)
        if (request.allowedIssueCodes.isNotEmpty() && IssueCode("MOTION_VIDEO_LENGTH_MISMATCH") !in request.allowedIssueCodes)
            return Prepared(session, RepairResult(before, emptyList(), emptyList(), before, blocked = repairedIssues), null, null)
        if (fixed.identity().orThrow().size > request.context.limits.maxOutputBytes)
            fail("RESOURCE_LIMIT_EXCEEDED", "Repaired HEIC exceeds output budget", Stage.Plan)
        val change = Change("{$ITEM_URI}Length", Value.Text(old), Value.Text(video.length.toString()),
            "Unique explicit final mpvd and independently verified complete movie prove the directory length; all other bytes retained", true)
        val expected = sha256Range(fixed, ByteRange(0uL, fixed.identity().orThrow().size)).orThrow()
        return Prepared(session, RepairResult(before, listOf(change), emptyList(), before), fixed, expected)
    }
    suspend fun plan(request: RepairRequest, session: SourceSession, budget: ParseBudget): CoreResult<ExecutionPlan> = attempt {
        val prepared = prepare(request, session, budget)
        if (!request.dryRun && prepared.fixed != null) {
            val caps = request.output!!.capabilities()
            if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic || request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
                fail("ATOMIC_PUBLICATION_UNAVAILABLE", "HEIC repair requires verified atomic publication", Stage.Plan)
        }
        ExecutionPlan(session.snapshot, target, listOf(PlanStep(if (request.dryRun) Stage.Plan else Stage.WriteProtocol,
            listOf(Operation.Repair), session.inspection.layout.resources.map { it.id }, "Prove equal-width directory length against the unique physical mpvd; preview never stages")),
            PreservationReport(changes = prepared.result.proposedChanges), CapabilitySet(if (prepared.result.blocked.isEmpty()) Availability.Conditional else Availability.Unsupported,
                listOf(DefaultLivePhotoCore().getProtocolCapabilities(target).operations.single { it.operation == Operation.Repair }), prepared.result.blocked))
    }
    suspend fun repair(request: RepairRequest, session: SourceSession, budget: ParseBudget): CoreResult<RepairResult> = attempt {
        val prepared = prepare(request, session, budget)
        val fixed = prepared.fixed
        if (request.dryRun || prepared.result.blocked.isNotEmpty() || fixed == null) return@attempt prepared.result
        val size = fixed.identity().orThrow().size
        var after: List<Issue> = emptyList()
        val asset = StagedAsset(OutputAssetSpec(AssetRole.Composite, mime = "image/heic"), ImageFormat.Heic,
            write = { writer -> copyRange(fixed, writer, ByteRange(0uL, size), request.context).orThrow() },
            verify = { id, reader ->
                val digest = sha256Range(reader, ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
                if (reader.identity().orThrow().size != size || digest != prepared.expected) fail("POSTCONDITION_FAILED", "HEIC repair changed bytes outside the sole proven length patch", Stage.Verify)
                val budget = ParseBudget(request.context)
                val staged = SourceSession.open(SourceSet.Single(reader.source), request.context, budget).orThrow()
                HeifMotionCleanup.prepare(staged, budget).orThrow()
                val report = validateSession(staged, listOf(Layer.Structure, Layer.Protocol), target = target).orThrow()
                if (report.verdict == Verdict.Invalid || report.checks.filter { it.layer == Layer.Protocol }.any { it.coverage != Coverage.Complete || it.verdict != Verdict.Valid })
                    fail("POSTCONDITION_FAILED", "Repaired HEIC protocol validation failed", Stage.Verify)
                after = staged.inspection.issues
                if (after.any { it.severity == Severity.Error && it !in prepared.result.issuesBefore }) fail("POSTCONDITION_FAILED", "HEIC repair introduced new errors", Stage.Verify)
                AssetVerification(report, listOf(
                    GuaranteeRecord(id, Guarantee.ExactExtraction, GuaranteeOutcome.NotApplicable),
                    GuaranteeRecord(id, Guarantee.ImageDataPreserving, GuaranteeOutcome.Verified, proof = "Every byte except owned equal-width length property matches independently proven view"),
                    GuaranteeRecord(id, Guarantee.BitstreamPreserving, GuaranteeOutcome.Verified, proof = "Entire movie byte range untouched"),
                    GuaranteeRecord(id, Guarantee.MetadataPreserving, GuaranteeOutcome.Verified, proof = "Only requested canonical directory length changes; all remaining metadata/table bytes untouched")), staged.inspection.keyPhoto)
            })
        val operation = publish(request.output!!, request.policy, request.context, session.readers + fixed, listOf(asset), prepared.result.proposedChanges).orThrow()
        prepared.result.copy(changesApplied = prepared.result.proposedChanges, issuesAfter = after, operation = operation)
    }
}
