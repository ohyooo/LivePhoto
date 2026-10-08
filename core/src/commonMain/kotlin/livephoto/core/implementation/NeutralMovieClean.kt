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
            for (box in parser.readBoxes(range, depth).orThrow()) {
                budget.poll()
                if (box.type !in schemas.getValue(kind))
                    fail("UNSAFE_METADATA_REWRITE", "Unclassified movie hierarchy cannot be declared clean", Stage.Plan, Location(selector = box.type))
                when {
                    box.type in schemas -> visit(box.payload, box.type, depth + 1u)
                    box.type == "udta" -> if (!EmptyMovieMetadata.matches(reader, parser, box, depth))
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
}
