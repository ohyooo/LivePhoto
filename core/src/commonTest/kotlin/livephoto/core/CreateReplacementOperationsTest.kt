package livephoto.core

import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

class CreateReplacementOperationsTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private fun source(bytes: ByteArray, id: String): BinarySource = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun request(output: OutputTransaction): CreateRequest = CreateRequest(source(GoogleFixtures.jpeg(), "replace-create-image"),
        source(GoogleFixtures.video().bytes, "replace-create-video"), ProtocolSelector(ProtocolIds.GoogleV2),
        edits = EditSpec(replacementFrame = CoverPosition.FrameIndex(1uL)), output = output, context = context)
    @Test fun replacementDoesNotImplicitlySynchronizeKeyAndVideoIsExact(): Unit = runImmediate {
        val core = DefaultLivePhotoCore(ReplaceOperationsTest.FrameBackend())
        for (key in listOf(null, CoverPosition.FrameIndex(0uL))) {
            val req = request(MemoryOutputTransaction(context, "create-replacement-$key"))
            val result = core.create(req.copy(edits = req.edits!!.copy(keyPosition = key))).orThrow()
            try {
                val session = SourceSession.open(SourceSet.Single(result.output.assets.single().readableSource!!), context, ParseBudget(context)).orThrow()
                assertEquals(0, session.inspection.keyPhoto.position!!.compareTo(if (key == null) Time(40, 1000u) else Time.Zero))
                val record = result.preservation.records.single { it.guarantee == Guarantee.ImageDataPreserving }
                assertEquals(GuaranteeOutcome.Changed, record.outcome); assertNotEquals(record.sourceDigest, record.outputDigest)
                assertEquals(Bytes(GoogleFixtures.video().bytes), session.reader.readExactly(session.bindings.single().video!!.offset, GoogleFixtures.video().bytes.size.toUInt()).orThrow())
                assertTrue(result.execution.any { it.stage == Stage.EncodeImage }); assertTrue(result.execution.none { it.transcoded })
            } finally { result.output.assets.forEach { it.readableSource?.close() } }
        }
    }
    @Test fun conversionSavesInheritedKeyBeforeCleaningAndReplacementIsIndependent(): Unit = runImmediate {
        val original = source(GoogleFixtures.v1Photo(timestamp = "0"), "replace-convert-source")
        val core = DefaultLivePhotoCore(ReplaceOperationsTest.FrameBackend())
        val result = core.convert(ConvertRequest(SourceSet.Single(original), ProtocolSelector(ProtocolIds.GoogleV2),
            edits = EditSpec(replacementFrame = CoverPosition.FrameIndex(1uL)), output = MemoryOutputTransaction(context, "convert-replacement"), context = context)).orThrow()
        assertEquals(0, result.keyPhoto!!.position!!.compareTo(Time.Zero))
        assertEquals(GuaranteeOutcome.Changed, result.preservation.records.single { it.guarantee == Guarantee.ImageDataPreserving }.outcome)
        result.output.assets.forEach { it.readableSource?.close() }
        val tx = MemoryOutputTransaction(context, "same-target-replacement")
        assertEquals("INVALID_ARGUMENT", assertIs<CoreResult.Failure>(core.convert(ConvertRequest(SourceSet.Single(original), ProtocolSelector(ProtocolIds.GoogleV1),
            edits = EditSpec(replacementFrame = CoverPosition.FrameIndex(1uL)), output = tx, context = context))).error.code.value)
        assertEquals(TransactionState.Open, tx.query().orThrow().state)
    }
    @Test fun combinationSelectsReplacementInOriginalTimelineEvenOutsideTrim(): Unit = runImmediate {
        val original = CreateTrimOperationsTest().fourSampleVideo()
        val frame = ReplaceOperationsTest.FrameBackend(); val trim = CreateTrimOperationsTest.Backend()
        val backend = object : MediaBackend by frame {
            override fun capabilities(): MediaCapabilities = MediaCapabilities(frame.capabilities().backendIds + trim.capabilities().backendIds, frame.capabilities().operations + trim.capabilities().operations)
            override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = trim.trim(job)
        }
        val core = DefaultLivePhotoCore(backend)
        val spec = TrimSpec(TimeRange(Time(80, 1000u), Time(160, 1000u)), TrimMode.LosslessOnly)
        for (target in listOf(ProtocolIds.GoogleV2, ProtocolIds.Oplus, ProtocolIds.Samsung, ProtocolIds.VivoModern)) for (convert in listOf(false, true)) {
            val tx = MemoryOutputTransaction(context, "replacement-plus-trim-$target-$convert")
            val edits = EditSpec(spec, CoverPosition.FrameIndex(3uL), CoverPosition.FrameIndex(1uL))
            val result = (if (convert) core.convert(ConvertRequest(SourceSet.Single(source(GoogleFixtures.v1Photo(original, "120000"), "replace-trim-convert")), ProtocolSelector(target), edits = edits, output = tx, context = context))
                else core.create(request(tx).copy(video = source(original, "replace-trim-video"), target = ProtocolSelector(target), edits = edits))).orThrow()
            try {
                assertEquals(0, result.keyPhoto!!.position!!.compareTo(Time(40, 1000u)))
                val change = result.preservation.changes.single { it.selector == "primaryImage" }
                assertEquals(Value.Text("1"), assertIs<Value.ObjectValue>(change.after).entries["sourceFrameIndex"])
                assertTrue(result.execution.indexOfFirst { it.stage == Stage.DecodeFrame } < result.execution.indexOfFirst { it.stage == Stage.Trim })
                assertEquals(GuaranteeOutcome.Verified, result.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }.outcome)
            } finally { result.output.assets.forEach { it.readableSource?.close() } }
        }
        assertEquals(8, frame.calls); assertEquals(8, trim.calls)
    }
    @Test fun policyMetadataAndMissingBackendRejectBeforeDecoderOrPublicWrite(): Unit = runImmediate {
        val backend = ReplaceOperationsTest.FrameBackend(); val core = DefaultLivePhotoCore(backend)
        for (policy in listOf(MutationPolicy(preservation = PreservationPolicy.Strict), MutationPolicy(requiredGuarantees = listOf(Guarantee.ImageDataPreserving)), MutationPolicy(requiredGuarantees = listOf(Guarantee.ExactExtraction)))) {
            val tx = MemoryOutputTransaction(context, "create-replace-policy")
            assertEquals("PRESERVATION_REQUIREMENT_FAILED", assertIs<CoreResult.Failure>(core.create(request(tx).copy(policy = policy))).error.code.value)
            assertEquals(TransactionState.Open, tx.query().orThrow().state)
        }
        val tx = MemoryOutputTransaction(context, "create-replace-metadata")
        val jpeg = GoogleFixtures.jpeg(GoogleFixtures.segment(0xe2, "ICC_PROFILE\u0000".encodeToByteArray() + byteArrayOf(1, 1, 7)))
        assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(core.create(request(tx).copy(image = source(jpeg, "replace-create-icc")))).error.code.value)
        assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore().create(request(tx))).error.code.value)
        assertEquals(0, backend.calls); assertTrue(tx.committedAssets().isEmpty())
    }
    @Test fun planningAndFailedPrivateFrameLeavePublicTransactionOpen(): Unit = runImmediate {
        val backend = ReplaceOperationsTest.FrameBackend(failDecode = true); val core = DefaultLivePhotoCore(backend)
        val tx = MemoryOutputTransaction(context, "replacement-plan")
        assertEquals(Availability.Conditional, core.plan(request(tx)).orThrow().capabilities.availability); assertEquals(0, backend.calls)
        assertEquals("DECODE_FAILED", assertIs<CoreResult.Failure>(core.create(request(tx))).error.code.value)
        assertEquals(TransactionState.Open, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
    }
    @Test fun originalImageIdentityIsRetainedThroughPrivateReplacementWork(): Unit = runImmediate {
        val original = source(GoogleFixtures.jpeg(), "create-replacement-changing-image"); var changed = false
        val mutable = object : BinarySource by original { override suspend fun identity(): CoreResult<SourceIdentity> = original.identity().let {
            if (it is CoreResult.Success && changed) CoreResult.Success(it.value.copy(generation = GenerationToken("changed"))) else it
        } }
        val core = DefaultLivePhotoCore(ReplaceOperationsTest.FrameBackend(after = { changed = true })); val tx = MemoryOutputTransaction(context, "create-replacement-changed")
        assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(core.create(request(tx).copy(image = mutable))).error.code.value)
        assertEquals(TransactionState.Open, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
    }
    @Test fun introducedJfifDoesNotBecomeAnOriginalMetadataGuarantee(): Unit = runImmediate {
        val jfif = byteArrayOf(0x4a, 0x46, 0x49, 0x46, 0, 1, 2, 0, 0, 1, 0, 1, 0, 0)
        val core = DefaultLivePhotoCore(ReplaceOperationsTest.FrameBackend(jfif = jfif))
        val result = core.create(request(MemoryOutputTransaction(context, "create-replace-added-jfif"))).orThrow()
        val record = result.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }
        assertEquals(GuaranteeOutcome.Unknown, record.outcome); assertNotEquals(record.sourceDigest, record.outputDigest)
        result.output.assets.forEach { it.readableSource?.close() }
        val required = MemoryOutputTransaction(context, "create-replace-required-metadata")
        assertEquals("PRESERVATION_REQUIREMENT_FAILED", assertIs<CoreResult.Failure>(core.create(request(required).copy(policy = MutationPolicy(requiredGuarantees = listOf(Guarantee.MetadataPreserving))))).error.code.value)
        assertEquals(TransactionState.Open, required.query().orThrow().state); assertTrue(required.committedAssets().isEmpty())
    }
    @Test fun failedPrivateTrimAfterFrameEncodingPublishesNeitherImageNorCarrier(): Unit = runImmediate {
        val frame = ReplaceOperationsTest.FrameBackend(); val trim = CreateTrimOperationsTest.Backend(failure = true)
        val backend = object : MediaBackend by frame {
            override fun capabilities(): MediaCapabilities = MediaCapabilities(frame.capabilities().backendIds + trim.capabilities().backendIds, frame.capabilities().operations + trim.capabilities().operations)
            override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = trim.trim(job)
        }
        val tx = MemoryOutputTransaction(context, "create-replacement-private-trim-failure")
        val req = request(tx).copy(video = source(CreateTrimOperationsTest().fourSampleVideo(), "failed-replacement-trim-video"),
            edits = EditSpec(TrimSpec(TimeRange(Time(80, 1000u), Time(160, 1000u)), TrimMode.LosslessOnly), CoverPosition.FrameIndex(3uL), CoverPosition.FrameIndex(1uL)))
        assertEquals("REMUX_FAILED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).create(req)).error.code.value)
        assertEquals(1, frame.calls); assertEquals(1, trim.calls)
        assertEquals(TransactionState.Open, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
    }
    @Test fun convertPlanRetainsPredictedEditsAndRequiredMediaCapabilitiesWithoutExecuting(): Unit = runImmediate {
        val frame = ReplaceOperationsTest.FrameBackend(); val trim = CreateTrimOperationsTest.Backend()
        val backend = object : MediaBackend by frame {
            override fun capabilities(): MediaCapabilities = MediaCapabilities(frame.capabilities().backendIds + trim.capabilities().backendIds, frame.capabilities().operations + trim.capabilities().operations)
            override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = trim.trim(job)
        }
        val input = SourceSet.Single(source(GoogleFixtures.v1Photo(CreateTrimOperationsTest().fourSampleVideo(), "120000"), "conversion-media-plan"))
        val tx = MemoryOutputTransaction(context, "conversion-media-plan-public")
        val plan = DefaultLivePhotoCore(backend).plan(ConvertRequest(input, ProtocolSelector(ProtocolIds.GoogleV2),
            edits = EditSpec(trim = TrimSpec(TimeRange(Time(80, 1000u), Time(160, 1000u)), TrimMode.LosslessOnly), replacementFrame = CoverPosition.FrameIndex(1uL)), output = tx, context = context)).orThrow()
        assertTrue(plan.predictedPreservation.changes.any { it.selector == "primaryImage" })
        assertTrue(plan.predictedPreservation.changes.any { it.selector == "videoTrim" })
        assertTrue(plan.capabilities.operations.map { it.operation }.containsAll(listOf(Operation.ConvertFrom, Operation.Trim, Operation.ExtractFrame)))
        assertTrue(plan.steps.any { it.stage == Stage.DecodeFrame }); assertTrue(plan.steps.any { it.stage == Stage.Trim })
        assertEquals(0, frame.calls); assertEquals(0, trim.calls); assertEquals(TransactionState.Open, tx.query().orThrow().state)
    }
}
