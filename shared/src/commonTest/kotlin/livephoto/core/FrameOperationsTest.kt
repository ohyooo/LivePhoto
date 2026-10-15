package livephoto.core

import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

/** Synthetic protocol orchestration and hostile backend tests, not real decoded pixel evidence. */
class FrameOperationsTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private fun request(output: OutputTransaction, input: BinarySource = MemoryBinarySource(Bytes(GoogleFixtures.video().bytes), SourceId("frame-input"))): ExtractFrameRequest =
        ExtractFrameRequest(ResourceRef(SourceSet.Single(input)), CoverPosition.FrameIndex(1uL), ImageEncoding(ImageFormat.Jpeg), output, context)

    @Test fun atomicDerivedFrameReportsActualPositionWithoutChangingVideo(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "frame-success")
        val backend = FrameBackend()
        val result = DefaultLivePhotoCore(backend).extractFrame(request(output)).orThrow()
        assertEquals(Time(40, 1000u), result.actualTime); assertEquals(1uL, result.actualFrameIndex); assertEquals(TrackId("1"), result.trackId)
        assertEquals(ImageFormat.Jpeg, result.operation.output.assets.single().imageFormat)
        assertEquals(Bytes(GoogleFixtures.jpeg()), output.committedAssets().values.single())
        assertEquals(GuaranteeOutcome.Changed, result.operation.preservation.records.single { it.guarantee == Guarantee.ImageDataPreserving }.outcome)
        assertTrue(result.operation.execution.none { it.transcoded || it.remuxed })
        result.operation.output.assets.forEach { it.readableSource?.close() }
    }
    @Test fun planningIsReadOnlyAndMissingBackendIsUnsupported(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "frame-plan"); val backend = FrameBackend()
        assertEquals(Availability.Conditional, DefaultLivePhotoCore(backend).plan(request(output)).orThrow().capabilities.availability)
        assertEquals(0, backend.calls); assertEquals(TransactionState.Open, output.query().orThrow().state)
        assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore().extractFrame(request(output))).error.code.value)
    }
    @Test fun falseIndexTimeTrackEncodingAndAssetsAbort(): Unit = runImmediate {
        for (wrong in listOf("index", "time", "track", "encoding", "size", "facts")) {
            val output = MemoryOutputTransaction(context, "frame-$wrong")
            assertEquals("POSTCONDITION_FAILED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(FrameBackend(wrong)).extractFrame(request(output))).error.code.value, wrong)
            assertEquals(TransactionState.Aborted, output.query().orThrow().state); assertTrue(output.committedAssets().isEmpty())
        }
    }
    @Test fun malformedStaleMetadataAndExtraVideoTailAreNotPublished(): Unit = runImmediate {
        val outputs = listOf(byteArrayOf(1, 2, 3), GoogleFixtures.jpeg() + GoogleFixtures.video().bytes,
            GoogleFixtures.jpeg(GoogleFixtures.xmpSegment("<x/>")))
        for ((index, bytes) in outputs.withIndex()) {
            val output = MemoryOutputTransaction(context, "frame-malformed-$index")
            assertIs<CoreResult.Failure>(DefaultLivePhotoCore(FrameBackend(bytes = bytes)).extractFrame(request(output)))
            assertEquals(TransactionState.Aborted, output.query().orThrow().state); assertTrue(output.committedAssets().isEmpty())
        }
    }
    @Test fun partialBackendWriteAndBudgetFailureAbort(): Unit = runImmediate {
        for ((label, reqContext) in listOf("backend" to context, "budget" to Context(Limits(8_000_000uL, 3uL)))) {
            val output = MemoryOutputTransaction(reqContext, "frame-$label")
            val result = DefaultLivePhotoCore(FrameBackend(if (label == "backend") "failure" else "")).extractFrame(request(output).copy(context = reqContext))
            assertIs<CoreResult.Failure>(result); assertEquals(TransactionState.Aborted, output.query().orThrow().state); assertTrue(output.committedAssets().isEmpty())
        }
    }
    @Test fun staleSnapshotAndOutOfRangeDoNotInvokeBackend(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "frame-preflight"); val backend = FrameBackend(); val core = DefaultLivePhotoCore(backend)
        val request = request(output)
        assertEquals("FRAME_INDEX_OUT_OF_RANGE", assertIs<CoreResult.Failure>(core.extractFrame(request.copy(position = CoverPosition.FrameIndex(10uL)))).error.code.value)
        val snapshot = Snapshot(emptyList(), GenerationToken("stale"))
        assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(core.extractFrame(request.copy(video = request.video.copy(snapshot = snapshot)))).error.code.value)
        assertEquals(0, backend.calls); assertEquals(TransactionState.Open, output.query().orThrow().state)
    }
    @Test fun embeddedVideoIsIsolatedFromJpegAndBorrowedSourceStaysOpen(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val input = MemoryBinarySource(Bytes(GoogleFixtures.v1Photo(video)), SourceId("frame-carrier"))
        val output = MemoryOutputTransaction(context, "frame-embedded"); val backend = FrameBackend()
        val result = DefaultLivePhotoCore(backend).extractFrame(request(output, input).copy(video = ResourceRef(SourceSet.Single(input), videoId(ProtocolIds.GoogleV1)))).orThrow()
        assertEquals(video.size.toULong(), backend.inputSize); assertEquals(GoogleFixtures.v1Photo(video).size.toULong(), input.size().orThrow())
        result.operation.output.assets.forEach { it.readableSource?.close() }
    }
    @Test fun cancellationAndMissingAtomicityDoNotPublish(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "frame-cancel"); val backend = FrameBackend()
        assertEquals("CANCELLED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).extractFrame(request(output).copy(context = context.copy(cancellation = Cancellation { true })))).error.code.value)
        val nonAtomic = object : OutputTransaction by output { override fun capabilities(): OutputCapabilities = output.capabilities().copy(assetSetAtomic = false) }
        assertEquals("ATOMIC_PUBLICATION_UNAVAILABLE", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).extractFrame(request(nonAtomic))).error.code.value)
        assertEquals(0, backend.calls); assertTrue(output.committedAssets().isEmpty())
    }
    @Test fun inputChangeAfterBackendProcessingAborts(): Unit = runImmediate {
        val original = MemoryBinarySource(Bytes(GoogleFixtures.video().bytes), SourceId("frame-mutable"))
        var changed = false
        val input = object : BinarySource by original {
            override suspend fun identity(): CoreResult<SourceIdentity> = original.identity().let { result ->
                if (result is CoreResult.Success && changed) CoreResult.Success(result.value.copy(generation = GenerationToken("changed"))) else result
            }
        }
        val output = MemoryOutputTransaction(context, "frame-identity")
        assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(FrameBackend(after = { changed = true })).extractFrame(request(output, input))).error.code.value)
        assertEquals(TransactionState.Aborted, output.query().orThrow().state); assertTrue(output.committedAssets().isEmpty())
    }

    private class FrameBackend(val wrong: String = "", val bytes: ByteArray = GoogleFixtures.jpeg(), val after: () -> Unit = {}) : MediaBackend {
        var calls = 0; var inputSize = 0uL
        override fun capabilities(): MediaCapabilities = MediaCapabilities(listOf("synthetic-frame"), listOf(CapabilityEntry(Operation.ExtractFrame, Implementation.Experimental)))
        override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = attempt {
            job.validate().orThrow(); calls++
            val reader = BinaryReader((job.inputs.single().input as SourceSet.Single).source, job.context)
            inputSize = reader.identity().orThrow().size
            val video = BmffVideoProbe(reader).probe(ByteRange(0uL, inputSize)).orThrow()
            val selected = selectFrame(video, job.position!!)
            val handle = job.destination.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/jpeg")).orThrow()
            handle.sink.write(Bytes(bytes)).orThrow(); handle.sink.close().orThrow(); after()
            if (wrong == "failure") fail("DECODE_FAILED", "Synthetic failure after writing", Stage.DecodeFrame)
            val facts = MediaFacts(imageFormat = ImageFormat.Jpeg, mime = "image/jpeg", width = if (wrong == "facts") 2u else 1u, height = 1u, coverage = Coverage.Partial)
            BackendResult(listOf(StagedAsset(handle.id, AssetRole.PrimaryImage, "image/jpeg", bytes.size.toULong() + if (wrong == "size") 1uL else 0uL)), listOf(facts),
                listOf(Stage.DecodeFrame, Stage.EncodeImage).map { ExecutionRecord(it, "synthetic-frame", "Synthetic test", wrong == "encoding", false, false, videoFacts(video), facts) },
                actualFrameTime = if (wrong == "time") Time.Zero else selected.time,
                actualFrameIndex = if (wrong == "index") 0uL else selected.index, actualFrameTrack = TrackId(if (wrong == "track") "2" else "1"))
        }
        override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = error("Unexpected probe")
        override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = error("Unexpected trim")
        override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = error("Unexpected remux")
        override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = error("Unexpected transcode")
    }
}
