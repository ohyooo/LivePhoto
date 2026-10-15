package livephoto.core

import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.huawei.HuaweiFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import livephoto.core.oplus.OplusFixtures
import livephoto.core.samsung.SamsungFixtures
import livephoto.core.vivo.VivoFixtures
import kotlin.test.*

/** Independent synthetic protocol fixtures, not real device compatibility observations. */
class VendorConvertTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val targets = listOf(ProtocolIds.Oplus, ProtocolIds.Samsung, ProtocolIds.VivoModern)
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private suspend fun session(input: BinarySource): SourceSession = SourceSession.open(SourceSet.Single(input), context, ParseBudget(context)).orThrow()
    @Test fun modernSourceMatrixConvertsToFiniteVendorTargetsWithoutEncoding(): Unit = runImmediate {
        val fixtures = listOf(GoogleFixtures.v1Photo(timestamp = "40000"), GoogleFixtures.v2Photo(timestamp = "40000"),
            OplusFixtures.photo(googleTimestamp = "40000", vendorTimestamp = "0"), VivoFixtures.photo(timestamp = "40000").bytes,
            SamsungFixtures.photo().bytes, HuaweiFixtures.photo().bytes)
        for ((index, bytes) in fixtures.withIndex()) for (target in targets) {
            val original = source(bytes, "vendor-matrix-$index-$target"); val before = session(original)
            val tx = MemoryOutputTransaction(context, "vendor-output-$index-$target")
            val request = ConvertRequest(SourceSet.Single(original), ProtocolSelector(target), sameTarget = SameTargetPolicy.Normalize, output = tx, context = context)
            core.plan(request).orThrow(); assertEquals(TransactionState.Open, tx.query().orThrow().state)
            val run = core.convert(request)
            val result = assertIs<CoreResult.Success<OperationResult>>(run, "$index -> $target: $run").value
            try {
                val after = session(result.output.assets.single().readableSource!!)
                assertEquals(target, after.inspection.detection.primaryProtocol!!.protocol)
                val range = after.bindings.single { it.protocol == target }.video!!
                assertEquals(Bytes(GoogleFixtures.video().bytes), after.reader.readExactly(range.offset, range.length.toUInt()).orThrow())
                assertEquals(codingDigest(before), codingDigest(after))
                if (before.inspection.keyPhoto.source == KeySource.ProtocolField && before.inspection.keyPhoto.position != null)
                    assertEquals(0, before.inspection.keyPhoto.position.compareTo(after.inspection.keyPhoto.position!!))
                assertTrue(result.execution.none { it.transcoded || it.stage in setOf(Stage.DecodeFrame, Stage.EncodeImage) })
                assertEquals(Verdict.Valid, core.validate(ValidationRequest(SourceSet.Single(after.reader.source), layers = listOf(Layer.Structure, Layer.Protocol), context = context)).orThrow().verdict)
                assertEquals(Bytes(bytes), original.readAt(0uL, bytes.size.toUInt()).orThrow())
            } finally { result.output.assets.forEach { it.readableSource?.close() } }
        }
    }
    @Test fun ordinarySefCannotBeDroppedAndUnimplementedProfilesNeverFallback(): Unit = runImmediate {
        for (target in targets) {
            val tx = MemoryOutputTransaction(context, "ordinary-sef-$target")
            val run = core.convert(ConvertRequest(SourceSet.Single(source(SamsungFixtures.photo(ordinaryRecord = true).bytes, "ordinary-sef")), ProtocolSelector(target), sameTarget = SameTargetPolicy.Normalize, output = tx, context = context))
            assertIs<CoreResult.Failure>(run); assertTrue(tx.committedAssets().isEmpty()); assertEquals(TransactionState.Open, tx.query().orThrow().state)
        }
        for (target in listOf(ProtocolSelector(ProtocolIds.Oplus, ProfileId("oneplus-tail-bearing")), ProtocolSelector(ProtocolIds.Samsung, ProfileId("heic-sef-mpv2")), ProtocolSelector(ProtocolIds.VivoModern, ProfileId("unknown")))) {
            val tx = MemoryOutputTransaction(context, "unsupported-target-$target")
            assertIs<CoreResult.Failure>(core.convert(ConvertRequest(SourceSet.Single(source(GoogleFixtures.v1Photo(), "unsupported-source")), target, output = tx, context = context)))
            assertEquals(TransactionState.Open, tx.query().orThrow().state)
        }
    }
    @Test fun vendorCapabilitiesAreConditionalExperimentalNotDeviceSupported() {
        for (target in targets) for (operation in listOf(Operation.ConvertFrom, Operation.ConvertTo)) {
            val cap = core.getProtocolCapabilities(ProtocolSelector(target)).operations.single { it.operation == operation }
            assertEquals(Implementation.Experimental, cap.implementation); assertTrue(cap.conditions.isNotEmpty())
        }
    }
    @Test fun ordinaryXmpIccAndCommentOwnershipSurviveOrRejectBeforePublication(): Unit = runImmediate {
        val ordinary = "<p:Copyright xmlns:p='urn:ordinary'>retained &amp; exact</p:Copyright>"
        val icc = GoogleFixtures.segment(0xe2, "ICC_PROFILE\u0000".encodeToByteArray() + byteArrayOf(1, 1, 9, 8, 7))
        val live = GoogleFixtures.v1Photo(timestamp = "40000", extra = ordinary)
        val original = source(live.copyOfRange(0, 2) + icc + live.copyOfRange(2, live.size), "vendor-ordinary")
        val before = session(original)
        for (target in targets) {
            val tx = MemoryOutputTransaction(context, "vendor-ordinary-$target")
            val result = core.convert(ConvertRequest(SourceSet.Single(original), ProtocolSelector(target), output = tx, context = context)).orThrow()
            try {
                val after = session(result.output.assets.single().readableSource!!)
                assertEquals(ordinaryDigest(before, verifiedExifRewrite = target == ProtocolIds.Oplus), ordinaryDigest(after, verifiedExifRewrite = target == ProtocolIds.Oplus))
                val back = core.convert(ConvertRequest(SourceSet.Single(after.reader.source), ProtocolSelector(ProtocolIds.GoogleV1), output = MemoryOutputTransaction(context, "vendor-ordinary-back-$target"), context = context)).orThrow()
                try { assertEquals(ordinaryDigest(before), ordinaryDigest(session(back.output.assets.single().readableSource!!), verifiedExifRewrite = target == ProtocolIds.Oplus)) }
                finally { back.output.assets.forEach { it.readableSource?.close() } }
            } finally { result.output.assets.forEach { it.readableSource?.close() } }
        }
        val plainComment = OplusFixtures.exifSegment("ordinary note, not vendor-owned")
        val collision = source(live.copyOfRange(0, 2) + plainComment + live.copyOfRange(2, live.size), "vendor-comment-conflict")
        val tx = MemoryOutputTransaction(context, "vendor-comment-rejected")
        assertIs<CoreResult.Failure>(core.convert(ConvertRequest(SourceSet.Single(collision), ProtocolSelector(ProtocolIds.Oplus), output = tx, context = context)))
        assertEquals(TransactionState.Open, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
    }
    @Test fun losslessVendorEditsRetainSamplesAndUseFinalVideoLength(): Unit = runImmediate {
        val video = CreateTrimOperationsTest().fourSampleVideo()
        val image = source(GoogleFixtures.jpeg(), "vendor-trim-image")
        val live = source(GoogleFixtures.v1Photo(video, timestamp = "120000"), "vendor-trim-live")
        val spec = TrimSpec(TimeRange(Time(80, 1000u), Time(160, 1000u)), TrimMode.LosslessOnly)
        for (target in targets) for (convert in listOf(false, true)) {
            val output = MemoryOutputTransaction(context, "vendor-lossless-$target-$convert")
            val backend = CreateTrimOperationsTest.Backend(); val engine = DefaultLivePhotoCore(backend)
            val run = if (convert) engine.convert(ConvertRequest(SourceSet.Single(live), ProtocolSelector(target), edits = EditSpec(trim = spec), output = output, context = context))
                else engine.create(CreateRequest(image, source(video, "vendor-trim-video"), ProtocolSelector(target), edits = EditSpec(spec, CoverPosition.FrameIndex(3uL)), output = output, context = context))
            val result = assertIs<CoreResult.Success<OperationResult>>(run, run.toString()).value
            try {
                val after = session(result.output.assets.single().readableSource!!)
                assertEquals(target, after.inspection.detection.primaryProtocol!!.protocol)
                assertEquals(0, result.keyPhoto!!.position!!.compareTo(Time(40, 1000u)))
                val range = after.bindings.single { it.protocol == target }.video!!
                assertEquals(Bytes(GoogleFixtures.video().bytes), after.reader.readExactly(range.offset, range.length.toUInt()).orThrow())
                assertEquals(GuaranteeOutcome.Verified, result.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }.outcome)
                assertTrue(result.execution.none { it.transcoded })
            } finally { result.output.assets.forEach { it.readableSource?.close() } }
        }
    }
}
