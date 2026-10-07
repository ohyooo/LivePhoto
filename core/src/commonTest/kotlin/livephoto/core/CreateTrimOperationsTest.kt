package livephoto.core

import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

class CreateTrimOperationsTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private val spec = TrimSpec(TimeRange(Time(80, 1000u), Time(160, 1000u)), TrimMode.LosslessOnly)
    private fun input(bytes: ByteArray, id: String): BinarySource = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private suspend fun request(tx: OutputTransaction): CreateRequest = CreateRequest(input(GoogleFixtures.jpeg(), "trim-create-image"), input(fourSampleVideo(), "trim-create-video"),
        ProtocolSelector(ProtocolIds.GoogleV2), edits = EditSpec(spec, CoverPosition.FrameIndex(3uL)), policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = tx, context = context)
    @Test fun createResolvesSourceFrameBeforeTrimAndWritesFinalLengthAndMappedKey(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "create-trim-success"); val backend = Backend()
        val original = request(tx); val result = DefaultLivePhotoCore(backend).create(original).orThrow()
        val after = SourceSession.open(SourceSet.Single(result.output.assets.single().readableSource!!), context, ParseBudget(context)).orThrow()
        assertEquals(Time(40_000, 1_000_000u), after.inspection.keyPhoto.position)
        assertEquals(Bytes(GoogleFixtures.video().bytes), after.reader.readExactly(after.bindings.single().video!!.offset, GoogleFixtures.video().bytes.size.toUInt()).orThrow())
        assertEquals(2, after.videos.values.single().tracks.single().samples.size)
        assertTrue(result.execution.any { it.stage == Stage.Trim }); assertTrue(result.execution.none { it.transcoded })
        assertEquals(GuaranteeOutcome.Verified, result.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }.outcome)
        assertEquals(GuaranteeOutcome.Verified, result.preservation.records.single { it.guarantee == Guarantee.ImageDataPreserving }.outcome)
        assertTrue(result.preservation.changes.any { it.selector == "videoTrim" && it.after is Value.ObjectValue })
        assertEquals(Bytes(fourSampleVideo()), original.video.readAt(0uL, fourSampleVideo().size.toUInt()).orThrow())
        result.output.assets.forEach { it.readableSource?.close() }
    }
    @Test fun defaultCreateKeyIsDerivedFromFinalVideoNotInventedAsInheritedSourceKey(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "create-trim-default")
        val result = DefaultLivePhotoCore(Backend()).create(request(tx).copy(edits = EditSpec(trim = spec))).orThrow()
        assertEquals(KeySource.DerivedDefault, result.keyPhoto?.source)
        assertEquals(Time(40_000, 1_000_000u), result.keyPhoto?.position)
        result.output.assets.forEach { it.readableSource?.close() }
    }
    @Test fun preferredActualBoundsNotRequestedBoundsGovernKeyAndAreDisclosed(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "create-preferred-trim")
        val trim = spec.copy(range = TimeRange(Time(90, 1000u), Time(150, 1000u)), mode = TrimMode.LosslessPreferred)
        val result = DefaultLivePhotoCore(Backend()).create(request(tx).copy(edits = EditSpec(trim, CoverPosition.Timestamp(Time(90, 1000u), tolerance = Time(10, 1000u))))).orThrow()
        assertEquals(0, result.keyPhoto!!.position!!.compareTo(Time.Zero)) // selected source PTS=80ms is retained by actual [80,160), not requested [90,150)
        val detail = result.preservation.changes.single { it.selector == "videoTrim" }.after as Value.ObjectValue
        assertNotEquals(detail.entries["requestedStart"], detail.entries["actualStart"])
        result.output.assets.forEach { it.readableSource?.close() }
    }
    @Test fun convertSavesInheritedKeyBeforeCleanupAndRebasesInSourceDomain(): Unit = runImmediate {
        val bytes = GoogleFixtures.v1Photo(fourSampleVideo(), timestamp = "120000")
        val tx = MemoryOutputTransaction(context, "convert-trim-key")
        val result = DefaultLivePhotoCore(Backend()).convert(ConvertRequest(SourceSet.Single(input(bytes, "convert-trim-input")), ProtocolSelector(ProtocolIds.GoogleV2), edits = EditSpec(trim = spec), output = tx, context = context)).orThrow()
        assertEquals(Time(40_000, 1_000_000u), result.keyPhoto?.position)
        val session = SourceSession.open(SourceSet.Single(result.output.assets.single().readableSource!!), context, ParseBudget(context)).orThrow()
        assertEquals(ProtocolIds.GoogleV2, session.bindings.single().protocol)
        assertEquals(Bytes(GoogleFixtures.video().bytes), session.reader.readExactly(session.bindings.single().video!!.offset, GoogleFixtures.video().bytes.size.toUInt()).orThrow())
        result.output.assets.forEach { it.readableSource?.close() }
    }
    @Test fun outsideInheritedKeyIsRejectedUnlessExplicitClampOrClear(): Unit = runImmediate {
        val bytes = GoogleFixtures.v1Photo(fourSampleVideo(), timestamp = "40000")
        for (outside in KeyOutsidePolicy.entries) {
            val tx = MemoryOutputTransaction(context, "convert-trim-outside-$outside"); val backend = Backend()
            val run = DefaultLivePhotoCore(backend).convert(ConvertRequest(SourceSet.Single(input(bytes, "convert-key-outside")), ProtocolSelector(ProtocolIds.GoogleV2),
                edits = EditSpec(trim = spec.copy(keyOutside = outside)), output = tx, context = context))
            if (outside == KeyOutsidePolicy.Reject) {
                assertEquals("KEY_PHOTO_OUTSIDE_TRIM", assertIs<CoreResult.Failure>(run).error.code.value); assertEquals(0, backend.calls); assertEquals(TransactionState.Open, tx.query().orThrow().state)
            } else {
                val result = run.orThrow()
                if (outside == KeyOutsidePolicy.ClampExplicitly) assertEquals(0, result.keyPhoto!!.position!!.compareTo(Time.Zero)) else {
                    // V2 -1 clears the explicit timestamp; a separately labelled display default is valid.
                    assertEquals(KeySource.DerivedDefault, result.keyPhoto!!.source)
                    assertTrue(result.keyPhoto.rawFields.any { it.rawValue == Value.Text("-1") })
                    val after = SourceSession.open(SourceSet.Single(result.output.assets.single().readableSource!!), context, ParseBudget(context)).orThrow()
                    assertNull(after.bindings.single().key.position)
                }
                result.output.assets.forEach { it.readableSource?.close() }
            }
        }
    }
    @Test fun planIsReadOnlyAndPreserveAsIsCannotSilentlyApplyEdits(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "create-trim-plan"); val backend = Backend(); val core = DefaultLivePhotoCore(backend)
        val plan = core.plan(request(tx)).orThrow(); assertEquals(2, plan.snapshot.identities.size)
        assertTrue(plan.steps.any { it.stage == Stage.Trim }); assertEquals(0, backend.calls); assertTrue(tx.committedAssets().isEmpty())
        val source = input(GoogleFixtures.v1Photo(fourSampleVideo(), timestamp = "120000"), "same-target-source")
        assertEquals("INVALID_ARGUMENT", assertIs<CoreResult.Failure>(core.convert(ConvertRequest(SourceSet.Single(source), ProtocolSelector(ProtocolIds.GoogleV1), edits = EditSpec(trim = spec), output = tx, context = context))).error.code.value)
    }
    @Test fun privateFailureAndSourceImageMutationDoNotTouchPublicTransaction(): Unit = runImmediate {
        for (kind in listOf("failure", "image-change")) {
            val tx = MemoryOutputTransaction(context, "create-trim-private-$kind"); var changed = false
            val original = input(GoogleFixtures.jpeg(), "mutable-create-image")
            val image = object : BinarySource by original { override suspend fun identity(): CoreResult<SourceIdentity> = original.identity().let { result ->
                if (changed && result is CoreResult.Success) CoreResult.Success(result.value.copy(generation = GenerationToken("changed"))) else result
            } }
            val backend = Backend(failure = kind == "failure", after = { if (kind == "image-change") changed = true })
            val result = DefaultLivePhotoCore(backend).create(request(tx).copy(image = image))
            assertEquals(if (kind == "failure") "REMUX_FAILED" else "SOURCE_CHANGED", assertIs<CoreResult.Failure>(result).error.code.value)
            assertEquals(TransactionState.Open, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
        }
    }
    @Test fun missingBackendAndUnsupportedReplacementOrTargetArePreflightGated(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "create-trim-gates")
        assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore().create(request(tx))).error.code.value)
        val backend = Backend(); val core = DefaultLivePhotoCore(backend)
        for (req in listOf(request(tx).copy(target = ProtocolSelector(ProtocolIds.Samsung)), request(tx).copy(edits = EditSpec(spec, replacementFrame = CoverPosition.FrameIndex(0uL)))))
            assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(core.create(req)).error.code.value)
        assertEquals(0, backend.calls)
    }
    /** Independently reconstruct four samples by extending the existing two-sample fixture's tables. */
    private suspend fun fourSampleVideo(): ByteArray {
        val original = GoogleFixtures.video(); val source = input(original.bytes, "four-sample-builder"); val reader = BinaryReader(source, context); val boxes = BmffReader(reader)
        val roots = boxes.readBoxes(ByteRange(0uL, original.bytes.size.toULong())).orThrow()
        suspend fun rebuild(box: BmffBox, sampleOffset: UInt): ByteArray {
            val payload = reader.readExactly(box.payload.offset, box.payload.length.toUInt()).orThrow().toByteArray()
            return when (box.type) {
                "moov", "trak", "mdia", "minf", "stbl" -> GoogleFixtures.box(box.type, boxes.readBoxes(box.payload).orThrow().fold(byteArrayOf()) { accumulated, child -> accumulated + rebuild(child, sampleOffset) })
                "mvhd", "tkhd", "mdhd" -> { GoogleFixtures.u32(160u).copyInto(payload, if (box.type == "tkhd") 20 else 16); GoogleFixtures.box(box.type, payload) }
                "stts" -> GoogleFixtures.fullBox("stts", GoogleFixtures.u32(1u) + GoogleFixtures.u32(4u) + GoogleFixtures.u32(40u))
                "stsc" -> GoogleFixtures.fullBox("stsc", GoogleFixtures.u32(1u) + GoogleFixtures.u32(1u) + GoogleFixtures.u32(4u) + GoogleFixtures.u32(1u))
                "stsz" -> GoogleFixtures.fullBox("stsz", GoogleFixtures.u32(0u) + GoogleFixtures.u32(4u) + original.samples.fold(byteArrayOf()) { accumulated, sample -> accumulated + GoogleFixtures.u32(sample.size.toUInt()) }.let { it + it })
                "stss" -> GoogleFixtures.fullBox("stss", GoogleFixtures.u32(2u) + GoogleFixtures.u32(1u) + GoogleFixtures.u32(3u))
                "stco" -> GoogleFixtures.fullBox("stco", GoogleFixtures.u32(1u) + GoogleFixtures.u32(sampleOffset))
                else -> GoogleFixtures.box(box.type, payload)
            }
        }
        val ftyp = roots.single { it.type == "ftyp" }.let { reader.readExactly(it.range.offset, it.range.length.toUInt()).orThrow().toByteArray() }
        val preliminary = rebuild(roots.single { it.type == "moov" }, 0u)
        val moov = rebuild(roots.single { it.type == "moov" }, (ftyp.size + preliminary.size + 8).toUInt())
        source.close()
        return ftyp + moov + GoogleFixtures.box("mdat", original.samples.fold(byteArrayOf()) { bytes, sample -> bytes + sample }.let { it + it })
    }
    private class Backend(val failure: Boolean = false, val after: () -> Unit = {}) : MediaBackend {
        var calls = 0
        override fun capabilities(): MediaCapabilities = MediaCapabilities(listOf("synthetic-create-trim"), listOf(CapabilityEntry(Operation.Trim, Implementation.Experimental)))
        override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = attempt {
            job.validate().orThrow(); calls++
            val reader = BinaryReader((job.inputs.single().input as SourceSet.Single).source, job.context)
            val video = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow(); val plan = planLosslessTrim(reader, video, job.trim!!)
            val bytes = GoogleFixtures.video().bytes; val outputReader = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("private-trim-video")), job.context)
            val actual = BmffVideoProbe(outputReader).probe(ByteRange(0uL, bytes.size.toULong())).orThrow(); val facts = videoFacts(actual)
            val handle = job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = "video/mp4")).orThrow(); handle.sink.write(Bytes(bytes)).orThrow(); handle.sink.close().orThrow(); after()
            if (failure) fail("REMUX_FAILED", "Synthetic private trim failure", Stage.Trim)
            BackendResult(listOf(StagedAsset(handle.id, AssetRole.MotionVideo, "video/mp4", bytes.size.toULong())), listOf(facts),
                listOf(ExecutionRecord(Stage.Trim, "synthetic-create-trim", "Synthetic test only", false, true, false, videoFacts(video), facts)), listOf(plan.trackTrim), plan.mapping)
        }
        override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = error("Unexpected probe")
        override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = error("Unexpected remux")
        override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = error("Unexpected transcode")
        override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = error("Unexpected frame")
    }
}
