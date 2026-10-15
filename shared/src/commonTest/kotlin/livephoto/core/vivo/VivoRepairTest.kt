package livephoto.core.vivo

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

class VivoRepairTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private fun input(bytes: ByteArray): SourceSet = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("vivo-repair")))
    private fun missingLength(): ByteArray = GoogleFixtures.jpeg(GoogleFixtures.xmpSegment(VivoFixtures.xml("1").replace("item:Length='1'", ""))) + GoogleFixtures.video().bytes

    @Test fun finiteLengthPreviewAndPlanNeverOpenOutput(): Unit = runImmediate {
        val size = GoogleFixtures.video().bytes.size
        val samples = listOf(missingLength()) + listOf("0", "1", (size - 1).toString(), (size + 1).toString()).map { VivoFixtures.photo(videoLength = it).bytes }
        for ((index, bytes) in samples.withIndex()) {
            val tx = MemoryOutputTransaction(context, "vivo-preview-$index")
            val request = RepairRequest(input(bytes), output = tx, context = context)
            val preview = core.repair(request).orThrow()
            assertTrue(preview.blocked.isEmpty()); assertEquals(Value.Text(size.toString()), preview.proposedChanges.single().after)
            assertTrue(preview.changesApplied.isEmpty()); assertNull(preview.operation)
            val plan = core.plan(request).orThrow()
            assertEquals(ProtocolIds.VivoModern, plan.target!!.protocol); assertEquals(preview.proposedChanges, plan.predictedPreservation.changes)
            assertEquals(TransactionState.Open, tx.query().orThrow().state); assertTrue(tx.query().orThrow().assetIds.isEmpty())
            val applied = core.repair(request.copy(dryRun = false, output = MemoryOutputTransaction(context, "vivo-length-apply-$index"),
                policy = MutationPolicy(preservation = PreservationPolicy.Strict))).orThrow()
            assertEquals(preview.proposedChanges, applied.changesApplied)
            assertNotNull(applied.operation).output.assets.forEach { it.readableSource?.close() }
        }
        assertEquals(Implementation.Experimental, core.getProtocolCapabilities(ProtocolSelector(ProtocolIds.VivoModern)).operations.single { it.operation == Operation.Repair }.implementation)
    }

    @Test fun strictApplyPreservesVideoCodingMetadataKeyAndVendorFieldsAndIsIdempotent(): Unit = runImmediate {
        val source = input(VivoFixtures.photo(videoLength = "1", timestamp = "40000", extra = "<p:Copyright xmlns:p='urn:ordinary'>kept</p:Copyright>").bytes)
        val before = SourceSession.open(source, context, ParseBudget(context), probeEmbeddedVideo = false).orThrow()
        val tx = MemoryOutputTransaction(context, "vivo-apply")
        val repair = core.repair(RepairRequest(source, dryRun = false, output = tx, policy = MutationPolicy(preservation = PreservationPolicy.Strict), context = context)).orThrow()
        assertTrue(repair.blocked.isEmpty()); assertEquals(repair.proposedChanges, repair.changesApplied)
        val operation = assertNotNull(repair.operation)
        try {
            val output = SourceSet.Single(operation.output.assets.single().readableSource!!)
            val after = SourceSession.open(output, context, ParseBudget(context)).orThrow()
            assertEquals(codingDigest(before), codingDigest(after)); assertEquals(ordinaryDigest(before), ordinaryDigest(after))
            assertEquals(0, after.inspection.keyPhoto.position!!.compareTo(Time(40, 1000u)))
            for (field in VIVO_FIELDS) assertEquals(before.xmp!!.scalar(VIVO_URI, field).orThrow(), after.xmp!!.scalar(VIVO_URI, field).orThrow())
            val binding = after.bindings.single { it.protocol == ProtocolIds.VivoModern }
            assertEquals(after.jpeg!!.trailing, binding.video)
            assertEquals(Bytes(GoogleFixtures.video().bytes), after.reader.readExactly(binding.video!!.offset, binding.video.length.toUInt()).orThrow())
            assertTrue(operation.preservation.records.filter { it.guarantee in setOf(Guarantee.ImageDataPreserving, Guarantee.BitstreamPreserving, Guarantee.MetadataPreserving) }.all { it.outcome == GuaranteeOutcome.Verified })
            val secondTx = MemoryOutputTransaction(context, "vivo-second")
            val second = core.repair(RepairRequest(output, dryRun = false, output = secondTx, context = context)).orThrow()
            assertTrue(second.proposedChanges.isEmpty()); assertNull(second.operation); assertEquals(TransactionState.Open, secondTx.query().orThrow().state)
        } finally { operation.output.assets.forEach { it.readableSource?.close() } }
    }

    @Test fun unknownProfilePaddingAuxiliaryAndMixedAuthorityCannotAuthorizeRepair(): Unit = runImmediate {
        val bytes = listOf(
            VivoFixtures.photo(videoLength = "1", version = "2").bytes,
            VivoFixtures.photo(videoLength = "1", kit = "unknown").bytes,
            VivoFixtures.photo(videoLength = "1", primaryAttrs = "item:Length='0'").bytes,
            VivoFixtures.photo(videoLength = "1", motionAttrs = "").bytes,
            VivoFixtures.photo(videoLength = "1", motionAttrs = "item:Padding='1'").bytes,
            VivoFixtures.photo(videoLength = "1", gainMap = VivoFixtures.gainMap()).bytes,
            VivoFixtures.photo(videoLength = "1", extra = "<v:VMotionPhotoFlags>keep</v:VMotionPhotoFlags>").bytes,
            VivoFixtures.photo(videoLength = "1", extra = "<camera:MicroVideo>1</camera:MicroVideo>").bytes,
        )
        for ((index, sample) in bytes.withIndex()) {
            val tx = MemoryOutputTransaction(context, "vivo-unsafe-$index")
            assertIs<CoreResult.Failure>(core.repair(RepairRequest(input(sample), dryRun = false, output = tx, context = context)))
            assertEquals(TransactionState.Open, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
        }
    }

    @Test fun invalidSuffixIsNeverScannedForVideoMagic(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        for ((index, suffix) in listOf(byteArrayOf(0) + video, video + video, video + byteArrayOf(0)).withIndex()) {
            val tx = MemoryOutputTransaction(context, "vivo-suffix-$index")
            val result = core.repair(RepairRequest(input(VivoFixtures.photo(video = suffix, videoLength = "1").bytes), dryRun = false, output = tx, context = context))
            assertEquals("REPAIR_NOT_POSSIBLE", assertIs<CoreResult.Failure>(result).error.code.value)
            assertEquals(TransactionState.Open, tx.query().orThrow().state)
        }
    }

    @Test fun invalidKeyAndIssueFilterBlockWithoutChangingOtherFields(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "vivo-key-filter")
        val key = core.repair(RepairRequest(input(VivoFixtures.photo(videoLength = "1", timestamp = "80000").bytes), dryRun = false, output = tx, context = context)).orThrow()
        assertTrue(key.blocked.any { it.code.value == "INVALID_PRESENTATION_TIMESTAMP" }); assertTrue(key.proposedChanges.isEmpty())
        val filter = core.repair(RepairRequest(input(VivoFixtures.photo(videoLength = "1").bytes), allowedIssueCodes = listOf(IssueCode("SEF_DIRECTORY_INVALID")), dryRun = false, output = tx, context = context)).orThrow()
        assertTrue(filter.blocked.isNotEmpty()); assertTrue(filter.proposedChanges.isEmpty()); assertEquals(TransactionState.Open, tx.query().orThrow().state)
    }

    @Test fun stagedOrdinaryMetadataCorruptionRollsBack(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "vivo-staging-corrupt")
        val output = object : OutputTransaction by tx {
            override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> = tx.openStaged(id).let { value ->
                if (value !is CoreResult.Success) value else CoreResult.Success(object : BinarySource by value.value {
                    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = value.value.readAt(offset, length).let { read ->
                        if (read !is CoreResult.Success) read else {
                            val bytes = read.value.toByteArray(); val needle = ">kept<".encodeToByteArray()
                            val position = bytes.indices.firstOrNull { i -> i + needle.size <= bytes.size && needle.indices.all { bytes[i + it] == needle[it] } }
                            if (position != null) ">lost<".encodeToByteArray().copyInto(bytes, position)
                            CoreResult.Success(Bytes(bytes))
                        }
                    }
                })
            }
        }
        val result = core.repair(RepairRequest(input(VivoFixtures.photo(videoLength = "1", extra = "<p:Copyright xmlns:p='urn:ordinary'>kept</p:Copyright>").bytes), dryRun = false, output = output, context = context))
        assertEquals("POSTCONDITION_FAILED", assertIs<CoreResult.Failure>(result).error.code.value)
        assertEquals(TransactionState.Aborted, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
    }

}
