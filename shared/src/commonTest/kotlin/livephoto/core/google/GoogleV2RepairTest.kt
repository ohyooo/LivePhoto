package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

class GoogleV2RepairTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private fun input(bytes: ByteArray): SourceSet = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("v2-repair-input")))
    private fun directory(length: String?, primary: String = "item:Length='0' item:Padding='0'", motion: String = ""): String =
        "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='image/jpeg' item:Semantic='Primary' $primary/></rdf:li>" +
            "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='video/mp4' item:Semantic='MotionPhoto' ${length?.let { "item:Length='$it'" } ?: ""} $motion/></rdf:li>"
    @Test fun uniqueDirectoryLengthPreviewAndPlanAreReadOnly(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        for (length in listOf(null, "0", "1", (video.size - 1).toString(), (video.size + 1).toString())) {
            val source = input(GoogleFixtures.v2Photo(directory = directory(length)))
            val tx = MemoryOutputTransaction(context, "v2-repair-preview-$length")
            val req = RepairRequest(source, output = tx, context = context)
            val result = core.repair(req).orThrow()
            assertEquals(Value.Text(video.size.toString()), result.proposedChanges.single().after)
            assertTrue(result.changesApplied.isEmpty()); assertNull(result.operation)
            assertEquals(result.issuesBefore, result.issuesAfter); assertEquals(TransactionState.Open, tx.query().orThrow().state)
            val plan = core.plan(req).orThrow()
            assertEquals(result.proposedChanges, plan.predictedPreservation.changes)
            assertEquals(ProtocolIds.GoogleV2, plan.target?.protocol); assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
        assertEquals(Implementation.Experimental, core.getProtocolCapabilities(ProtocolSelector(ProtocolIds.GoogleV2)).operations.single { it.operation == Operation.Repair }.implementation)
    }
    @Test fun applyPreservesCodingVideoOrdinaryMetadataAndKeyThenSecondRepairIsNoOp(): Unit = runImmediate {
        val extra = "<p:Copyright xmlns:p='urn:ordinary'>keep &amp; preserve</p:Copyright>"
        val original = input(GoogleFixtures.v2Photo(timestamp = "40000", directory = directory("1"), extra = extra))
        val before = SourceSession.open(original, context, ParseBudget(context), probeEmbeddedVideo = false).orThrow()
        val tx = MemoryOutputTransaction(context, "v2-repair-apply")
        val result = core.repair(RepairRequest(original, dryRun = false, policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = tx, context = context)).orThrow()
        assertEquals(result.proposedChanges, result.changesApplied); assertTrue(result.blocked.isEmpty())
        val operation = assertNotNull(result.operation)
        try {
            val afterInput = SourceSet.Single(operation.output.assets.single().readableSource!!)
            val after = SourceSession.open(afterInput, context, ParseBudget(context)).orThrow()
            assertEquals(codingDigest(before), codingDigest(after)); assertEquals(ordinaryDigest(before), ordinaryDigest(after))
            assertEquals(0, after.inspection.keyPhoto.position!!.compareTo(Time(40, 1000u)))
            assertEquals(Bytes(GoogleFixtures.video().bytes), after.reader.readExactly(after.bindings.single().video!!.offset, GoogleFixtures.video().bytes.size.toUInt()).orThrow())
            assertTrue(operation.preservation.records.filter { it.guarantee in setOf(Guarantee.ImageDataPreserving, Guarantee.BitstreamPreserving, Guarantee.MetadataPreserving) }.all { it.outcome == GuaranteeOutcome.Verified })
            val secondTx = MemoryOutputTransaction(context, "v2-repair-noop")
            val second = core.repair(RepairRequest(afterInput, dryRun = false, output = secondTx, context = context)).orThrow()
            assertTrue(second.proposedChanges.isEmpty()); assertTrue(second.changesApplied.isEmpty()); assertNull(second.operation)
            assertEquals(TransactionState.Open, secondTx.query().orThrow().state)
        } finally { operation.output.assets.forEach { it.readableSource?.close() } }
    }
    @Test fun paddingAuxiliaryUnknownAndReferenceGraphsCannotAuthorizeRepair(): Unit = runImmediate {
        val bad = listOf(directory("1", primary = "item:Padding='1'"), directory("1", motion = "item:Padding='0'"),
            directory("1", motion = "item:Unknown='keep'"), directory("1").replace("rdf:parseType='Resource'", "rdf:resource='urn:external'"),
            directory("1").replace("item:Semantic='MotionPhoto'", "item:Semantic='GainMap'"))
        for ((index, graph) in bad.withIndex()) {
            val tx = MemoryOutputTransaction(context, "v2-repair-unsafe-$index")
            assertIs<CoreResult.Failure>(core.repair(RepairRequest(input(GoogleFixtures.v2Photo(directory = graph)), dryRun = false, output = tx, context = context)))
            assertEquals(TransactionState.Open, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
        }
    }
    @Test fun completePhysicalSuffixMustBeOneVideoNotMagicScanOrConcatenation(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        for (suffix in listOf(byteArrayOf(0) + video, video + video, video + byteArrayOf(0))) {
            val tx = MemoryOutputTransaction(context, "v2-repair-invalid-suffix-${suffix.size}")
            val result = core.repair(RepairRequest(input(GoogleFixtures.v2Photo(video = suffix, directory = directory("1"))), dryRun = false, output = tx, context = context))
            assertEquals("REPAIR_NOT_POSSIBLE", assertIs<CoreResult.Failure>(result).error.code.value)
            assertEquals(TransactionState.Open, tx.query().orThrow().state)
        }
    }
    @Test fun invalidKeyAndExplicitFilterBlockWithoutSilentlyFixingAnotherField(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "v2-repair-invalid-key")
        val result = core.repair(RepairRequest(input(GoogleFixtures.v2Photo(timestamp = "80000", directory = directory("1"))), dryRun = false, output = tx, context = context)).orThrow()
        assertTrue(result.blocked.any { it.code.value == "INVALID_PRESENTATION_TIMESTAMP" }); assertTrue(result.proposedChanges.isEmpty())
        assertEquals(TransactionState.Open, tx.query().orThrow().state)
        val filtered = core.repair(RepairRequest(input(GoogleFixtures.v2Photo(directory = directory("1"))), allowedIssueCodes = listOf(IssueCode("SEF_DIRECTORY_INVALID")), dryRun = false, output = tx, context = context)).orThrow()
        assertTrue(filtered.blocked.isNotEmpty()); assertTrue(filtered.proposedChanges.isEmpty())
    }
    @Test fun finalStagingCorruptionAndOriginalIdentityChangePreventCommit(): Unit = runImmediate {
        val source = input(GoogleFixtures.v2Photo(directory = directory("1")))
        val tx = MemoryOutputTransaction(context, "v2-repair-corruption")
        val output = object : OutputTransaction by tx {
            override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> = tx.openStaged(id).let { value ->
                if (value !is CoreResult.Success) value else CoreResult.Success(object : BinarySource by value.value {
                    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = value.value.readAt(offset, length).let {
                        if (it is CoreResult.Success && offset == 0uL && it.value.size > 0) CoreResult.Success(Bytes(it.value.toByteArray().also { bytes -> bytes[0] = 0 })) else it
                    }
                })
            }
        }
        assertIs<CoreResult.Failure>(core.repair(RepairRequest(source, dryRun = false, output = output, context = context)))
        assertEquals(TransactionState.Aborted, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
        val original = (source as SourceSet.Single).source; var changed = false
        val mutable = object : BinarySource by original {
            override suspend fun identity(): CoreResult<SourceIdentity> = original.identity().let { if (changed && it is CoreResult.Success) CoreResult.Success(it.value.copy(generation = GenerationToken("changed"))) else it }
        }
        val secondTx = MemoryOutputTransaction(context, "v2-repair-source-change")
        val changing = object : OutputTransaction by secondTx {
            override suspend fun prepare(): CoreResult<Unit> { changed = true; return secondTx.prepare() }
        }
        assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(core.repair(RepairRequest(SourceSet.Single(mutable), dryRun = false, output = changing, context = context))).error.code.value)
        assertEquals(TransactionState.Aborted, secondTx.query().orThrow().state); assertTrue(secondTx.committedAssets().isEmpty())
    }
    @Test fun duplicateDirectoriesAndMixedAuthoritiesCannotBeChosenAutomatically(): Unit = runImmediate {
        val xml = GoogleFixtures.v2Xml(GoogleFixtures.video().bytes.size, directory = directory("1"))
            .replace("</rdf:Description>", "<container:Directory><rdf:Seq>${directory("1")}</rdf:Seq></container:Directory></rdf:Description>")
        val duplicate = GoogleFixtures.jpeg(GoogleFixtures.xmpSegment(xml)) + GoogleFixtures.video().bytes
        val mixed = GoogleFixtures.v2Photo(directory = directory("1"), extra = "<c:MicroVideo xmlns:c='http://ns.google.com/photos/1.0/camera/'>1</c:MicroVideo>")
        for ((index, bytes) in listOf(duplicate, mixed).withIndex()) {
            val tx = MemoryOutputTransaction(context, "v2-repair-duplicate-$index")
            assertEquals("REPAIR_AMBIGUOUS", assertIs<CoreResult.Failure>(core.repair(RepairRequest(input(bytes), dryRun = false, output = tx, context = context))).error.code.value)
            assertEquals(TransactionState.Open, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
        }
    }
    @Test fun wellFormedStagingMetadataChangeOutsideLengthIsRejected(): Unit = runImmediate {
        val original = input(GoogleFixtures.v2Photo(directory = directory("1"), extra = "<p:Copyright xmlns:p='urn:ordinary'>kept</p:Copyright>"))
        val tx = MemoryOutputTransaction(context, "v2-repair-ordinary-corruption")
        val output = object : OutputTransaction by tx {
            override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> = tx.openStaged(id).let { value ->
                if (value !is CoreResult.Success) value else CoreResult.Success(object : BinarySource by value.value {
                    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = value.value.readAt(offset, length).let { read ->
                        if (read !is CoreResult.Success) read else {
                            val bytes = read.value.toByteArray(); val needle = ">kept<".encodeToByteArray()
                            val index = bytes.indices.firstOrNull { position -> position + needle.size <= bytes.size && needle.indices.all { bytes[position + it] == needle[it] } }
                            if (index != null) ">lost<".encodeToByteArray().copyInto(bytes, index)
                            CoreResult.Success(Bytes(bytes))
                        }
                    }
                })
            }
        }
        val result = core.repair(RepairRequest(original, dryRun = false, output = output, context = context))
        assertEquals("POSTCONDITION_FAILED", assertIs<CoreResult.Failure>(result).error.code.value)
        assertEquals(TransactionState.Aborted, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
    }
}
