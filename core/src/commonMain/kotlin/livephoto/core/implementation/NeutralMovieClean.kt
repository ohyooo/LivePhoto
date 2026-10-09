package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** SPL-01: bounded, already-neutral movies are copied, never remuxed or "repaired". */
internal object NeutralMovieClean {
    fun accepts(session: SourceSession): Boolean = session.readers.size == 1 && session.jpeg == null && session.heifItems == null

    suspend fun preflight(session: SourceSession, budget: ParseBudget): VideoStructure {
        if (!accepts(session) || session.bindings.isNotEmpty() || session.inspection.pairing != null)
            fail("CAPABILITY_UNSUPPORTED", "Already-neutral Clean requires one unbound ordinary movie", Stage.Plan)
        val reader = session.reader
        val size = reader.identity().orThrow().size
        if (size > reader.context.limits.maxOutputBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Neutral movie exceeds output budget", Stage.Plan)
        val facts = BmffVideoProbe(reader, budget, allowTimedMetadata = true).probe(ByteRange(0uL, size)).orThrow()
        if (facts.tracks.count { it.handler == "vide" } != 1 || facts.tracks.any { it.handler !in setOf("vide", "soun") })
            fail("UNSAFE_METADATA_REWRITE", "Timed or unclassified tracks are not proven neutral", Stage.Plan)
        val parser = BmffReader(reader, budget)
        val schemas = mapOf(
            "root" to setOf("ftyp", "moov", "mdat", "free", "wide"),
            "moov" to setOf("mvhd", "trak", "free", "udta"),
            "trak" to setOf("tkhd", "edts", "mdia"), "edts" to setOf("elst"),
            "mdia" to setOf("mdhd", "hdlr", "minf"),
            "minf" to setOf("vmhd", "smhd", "hdlr", "dinf", "stbl"), "dinf" to setOf("dref"),
            "stbl" to setOf("stsd", "stts", "ctts", "stsc", "stsz", "stco", "co64", "stss", "sdtp", "sgpd", "sbgp"))
        suspend fun visit(range: ByteRange, kind: String, depth: UInt) {
            val children = parser.readBoxes(range, depth).orThrow()
            if (kind == "moov" && children.count { it.type == "udta" } > 1)
                fail("UNSAFE_METADATA_REWRITE", "Neutral movie has multiple user metadata directories", Stage.Plan)
            for (box in children) {
                budget.poll()
                if (box.type !in schemas.getValue(kind))
                    fail("UNSAFE_METADATA_REWRITE", "Unclassified movie hierarchy cannot be declared clean", Stage.Plan, Location(selector = box.type))
                when {
                    box.type in schemas -> visit(box.payload, box.type, depth + 1u)
                    // This path copies the entire already-neutral file with an exact SHA proof.
                    box.type == "udta" -> if (!EmptyMovieMetadata.matches(reader, parser, box, depth) &&
                        !(kind == "moov" && OrdinaryMovieText.matches(reader, parser, box, depth, budget)))
                        fail("UNSAFE_METADATA_REWRITE", "Ordinary movie metadata needs a dedicated neutral classification", Stage.Plan)
                    box.type in setOf("free", "wide") -> {
                        var offset = box.payload.offset
                        while (offset < box.payload.endExclusive) {
                            budget.poll()
                            val bytes = reader.readBuffer(offset, minOf(65_536uL, box.payload.endExclusive - offset).toUInt()).orThrow()
                            if (bytes.toByteArray().any { it != 0.toByte() })
                                fail("UNSAFE_METADATA_REWRITE", "Padding contains unclassified data", Stage.Plan)
                            offset += bytes.size.toULong()
                        }
                    }
                }
            }
        }
        visit(facts.range, "root", 0u)
        session.recheck()
        return facts
    }

    suspend fun split(request: SplitRequest, session: SourceSession, budget: ParseBudget): CoreResult<OperationResult> = attempt {
        val facts = preflight(session, budget)
        val raw = GoogleOperations.rawAsset(session, facts.range, AssetRole.MotionVideo, videoFacts(facts).mime!!,
            request.context, facts.container)
        val asset = raw.copy(verify = { id, reader ->
            val verified = raw.verify(id, reader)
            val staged = SourceSession.open(SourceSet.Single(reader.source), request.context, ParseBudget(request.context)).orThrow()
            val after = preflight(staged, ParseBudget(request.context))
            if (after != facts) fail("POSTCONDITION_FAILED", "Already-neutral movie structure changed", Stage.Verify)
            verified.copy(validation = verified.validation.copy(checks = verified.validation.checks +
                CheckResult("neutral-movie.structure-and-unbound-hierarchy", Layer.Structure, Verdict.Valid, Coverage.Complete)),
                guarantees = verified.guarantees.map { record -> if (record.guarantee == Guarantee.BitstreamPreserving)
                    record.copy(outcome = GuaranteeOutcome.Verified, proof = "Independent retained track/sample/configuration/timeline equality plus entire-file SHA-256") else record })
        })
        publish(request.output, request.policy, request.context, session.readers, listOf(asset)).orThrow()
    }

    suspend fun plan(request: SplitRequest, session: SourceSession, budget: ParseBudget): CoreResult<ExecutionPlan> = attempt {
        val facts = preflight(session, budget)
        val caps = request.output.capabilities()
        if (!caps.canReadStaged || request.policy.atomicity == Atomicity.AssetSetRequired && !caps.assetSetAtomic ||
            request.policy.existingOutput == ExistingOutput.Replace && !caps.replacesAtomically)
            fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Neutral Clean plan cannot satisfy transaction guarantees", Stage.Plan)
        session.recheck()
        ExecutionPlan(session.snapshot, null, listOf(
            PlanStep(Stage.Clean, listOf(Operation.SplitClean), emptyList(), "Copy the entire already-neutral movie; no edits, encoding or remux"),
            PlanStep(Stage.Verify, listOf(Operation.Validate), emptyList(), "Independently classify staged hierarchy and compare whole-file SHA and track/sample/configuration/timeline facts")),
            PreservationReport(), CapabilitySet(Availability.Conditional, listOf(CapabilityEntry(Operation.SplitClean, Implementation.Experimental,
                conditions = listOf(Condition(ConditionOperator.Equals, "sourceContent", Value.Text(videoFacts(facts).mime!!)),
                    Condition(ConditionOperator.Equals, "cleanupScope", Value.Text("already-neutral-one-video-optional-audio-no-cid-timed-track-uuid-or-unknown-hierarchy-zero-padding-whole-file-copy"))),
                verification = listOf(Verification.SourceReviewed)))))
    }
}
