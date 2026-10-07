package livephoto.core.oplus

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

class OplusRepairTest {
    private val core = DefaultLivePhotoCore()
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private val size = GoogleFixtures.video().bytes.size.toString()
    private fun input(bytes: ByteArray): SourceSet = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("oplus-repair")))

    @Test fun dAndVIndependentEvidenceMatrixPreviewStrictApplyAndSecondNoOp(): Unit = runImmediate {
        val cases = listOf(null to size, "0" to size, "1" to size, (size.toInt() + 1).toString() to size,
            size to null, size to "0", size to (size.toInt() + 1).toString(), "1" to "1", null to null, "0" to "0")
        for ((index, lengths) in cases.withIndex()) {
            val source = input(OplusFixtures.photo(directoryLength = lengths.first, videoLength = lengths.second,
                googleTimestamp = "40000", vendorTimestamp = "0", extra = "<p:Copyright xmlns:p='urn:ordinary'>kept</p:Copyright>"))
            val before = SourceSession.open(source, context, ParseBudget(context), probeEmbeddedVideo = false).orThrow()
            val previewTx = MemoryOutputTransaction(context, "oplus-preview-$index")
            val request = RepairRequest(source, output = previewTx, context = context)
            val preview = core.repair(request).orThrow()
            val count = (if (lengths.first != size) 1 else 0) + (if (lengths.second != size) 1 else 0)
            assertTrue(preview.blocked.isEmpty()); assertEquals(count, preview.proposedChanges.size)
            assertTrue(preview.proposedChanges.all { it.after == Value.Text(size) }); assertNull(preview.operation)
            assertEquals(ProtocolIds.Oplus, core.plan(request).orThrow().target!!.protocol)
            assertEquals(TransactionState.Open, previewTx.query().orThrow().state); assertTrue(previewTx.query().orThrow().assetIds.isEmpty())
            val tx = MemoryOutputTransaction(context, "oplus-apply-$index")
            val repair = core.repair(request.copy(dryRun = false, output = tx, policy = MutationPolicy(preservation = PreservationPolicy.Strict))).orThrow()
            assertEquals(preview.proposedChanges, repair.changesApplied)
            val operation = assertNotNull(repair.operation)
            try {
                val output = SourceSet.Single(operation.output.assets.single().readableSource!!)
                val after = SourceSession.open(output, context, ParseBudget(context)).orThrow()
                assertEquals(codingDigest(before), codingDigest(after)); assertEquals(ordinaryDigest(before), ordinaryDigest(after))
                assertEquals(before.exifComments, after.exifComments)
                assertEquals("0", after.xmp!!.scalar(OPLUS_URI, "MotionPhotoPrimaryPresentationTimestampUs").orThrow())
                assertEquals(0, after.inspection.keyPhoto.position!!.compareTo(Time(40, 1000u)))
                val binding = after.bindings.single { it.protocol == ProtocolIds.Oplus }
                assertEquals(ProfileId("jpeg-no-tail"), binding.profile); assertNull(binding.trailer); assertEquals(after.jpeg!!.trailing, binding.video)
                assertEquals(Bytes(GoogleFixtures.video().bytes), after.reader.readExactly(binding.video!!.offset, binding.video.length.toUInt()).orThrow())
                assertTrue(operation.preservation.records.filter { it.guarantee in setOf(Guarantee.ImageDataPreserving, Guarantee.BitstreamPreserving, Guarantee.MetadataPreserving) }.all { it.outcome == GuaranteeOutcome.Verified })
                val secondTx = MemoryOutputTransaction(context, "oplus-noop-$index")
                val second = core.repair(RepairRequest(output, dryRun = false, output = secondTx, context = context)).orThrow()
                assertTrue(second.proposedChanges.isEmpty()); assertNull(second.operation); assertEquals(TransactionState.Open, secondTx.query().orThrow().state)
            } finally { operation.output.assets.forEach { it.readableSource?.close() } }
        }
    }

    @Test fun declaredTailShortVAndUnknownMarkerOrProfileCannotBeNormalized(): Unit = runImmediate {
        val cases = listOf(
            OplusFixtures.photo(videoLength = "1"), // Declares a tail: do not guess that V, not D, was damaged.
            OplusFixtures.photo(tail = byteArrayOf(9, 8)),
            OplusFixtures.photo(directoryLength = "1", comment = null),
            OplusFixtures.photo(directoryLength = "1", comment = "ordinary user comment"),
            OplusFixtures.photo(directoryLength = "1", owner = "unknown"),
            OplusFixtures.photo(directoryLength = "1", version = "3"),
            OplusFixtures.photo(directoryLength = "1", videoLength = "-1"),
            OplusFixtures.photo(directoryLength = "1", extra = "<o:Unknown>keep</o:Unknown>"),
            OplusFixtures.photo(directoryLength = "1", secondaryPadding = "0"),
            OplusFixtures.photo(directoryLength = "1", extra = "<o:OLivePhotoVersion>2</o:OLivePhotoVersion>"),
            OplusFixtures.photo(directoryLength = "1", extra = "<o:VideoLength>$size</o:VideoLength>"),
            OplusFixtures.photo(directoryLength = "1", extra = "<camera:MotionPhoto>1</camera:MotionPhoto>"),
        )
        for ((index, bytes) in cases.withIndex()) {
            val tx = MemoryOutputTransaction(context, "oplus-unsafe-$index")
            assertIs<CoreResult.Failure>(core.repair(RepairRequest(input(bytes), dryRun = false, output = tx, context = context)))
            assertEquals(TransactionState.Open, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
        }
    }

    @Test fun filterMustAuthorizeBothProvedLengthRepairs(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "oplus-filter")
        val source = input(OplusFixtures.photo(directoryLength = null, videoLength = "0"))
        for (code in listOf("MISSING_REQUIRED_XMP", "MOTION_VIDEO_LENGTH_MISMATCH", "SEF_DIRECTORY_INVALID")) {
            val result = core.repair(RepairRequest(source, allowedIssueCodes = listOf(IssueCode(code)), dryRun = false, output = tx, context = context)).orThrow()
            assertTrue(result.blocked.isNotEmpty()); assertTrue(result.proposedChanges.isEmpty()); assertNull(result.operation)
        }
        assertEquals(TransactionState.Open, tx.query().orThrow().state)
    }

    @Test fun badKeyOrUnverifiedSuffixCannotProduceRepair(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "oplus-key")
        val key = core.repair(RepairRequest(input(OplusFixtures.photo(directoryLength = "1", googleTimestamp = "80000")), dryRun = false, output = tx, context = context)).orThrow()
        assertTrue(key.blocked.any { it.code.value == "INVALID_PRESENTATION_TIMESTAMP" }); assertTrue(key.proposedChanges.isEmpty())
        val video = GoogleFixtures.video().bytes
        for (suffix in listOf(byteArrayOf(0) + video, video + video, video + byteArrayOf(0))) {
            assertEquals("REPAIR_NOT_POSSIBLE", assertIs<CoreResult.Failure>(core.repair(RepairRequest(input(OplusFixtures.photo(video = suffix, directoryLength = "1")), dryRun = false, output = tx, context = context))).error.code.value)
        }
        assertEquals(TransactionState.Open, tx.query().orThrow().state)
    }

    @Test fun capabilityRemainsFiniteExperimentalAndTailRepairUnsupported() {
        assertEquals(Implementation.Experimental, core.getProtocolCapabilities(ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail"))).operations.single { it.operation == Operation.Repair }.implementation)
        assertEquals(Implementation.Unsupported, core.getProtocolCapabilities(ProtocolSelector(ProtocolIds.Oplus, ProfileId("oneplus-tail-bearing"))).operations.single { it.operation == Operation.Repair }.implementation)
    }

    @Test fun opaqueExifRemainsUnknownAndStrictFailsBeforeStaging(): Unit = runImmediate {
        val bytes = OplusFixtures.photo(directoryLength = "1")
        val parsed = SourceSession.open(input(bytes), context, ParseBudget(context), probeEmbeddedVideo = false).orThrow()
        val tag = parsed.exifComments.single().document.ifds.first().entries.first().entryRange.offset.toInt()
        bytes[tag] = 0x7c; bytes[tag + 1] = 0x92.toByte() // opaque MakerNote, not a standard width field
        val tx = MemoryOutputTransaction(context, "oplus-opaque-strict")
        val request = RepairRequest(input(bytes), dryRun = false, output = tx, policy = MutationPolicy(preservation = PreservationPolicy.Strict), context = context)
        val failure = assertIs<CoreResult.Failure>(core.repair(request))
        assertEquals("PRESERVATION_REQUIREMENT_FAILED", failure.error.code.value); assertEquals(Stage.Plan, failure.error.stage)
        assertEquals(TransactionState.Open, tx.query().orThrow().state); assertTrue(tx.query().orThrow().assetIds.isEmpty())
        val bestEffort = core.repair(request.copy(output = MemoryOutputTransaction(context, "oplus-opaque-best-effort"), policy = MutationPolicy())).orThrow()
        val operation = assertNotNull(bestEffort.operation)
        try { assertEquals(GuaranteeOutcome.Unknown, operation.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }.outcome) }
        finally { operation.output.assets.forEach { it.readableSource?.close() } }
    }
}
