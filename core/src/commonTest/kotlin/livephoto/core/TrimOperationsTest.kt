package livephoto.core

import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.planLosslessTrim
import livephoto.core.implementation.videoFacts
import livephoto.core.memory.*
import kotlin.test.*

/** Synthetic planning/orchestration/hostile backend tests; real shortened-media tests are separate. */
class TrimOperationsTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private val bytes = GoogleFixtures.video().bytes
    private fun source(value: ByteArray = bytes): BinarySource = MemoryBinarySource(Bytes(value), SourceId("trim-input"))
    private fun request(output: OutputTransaction, spec: TrimSpec = TrimSpec(TimeRange(Time.Zero, Time(80, 1000u)))): TrimRequest =
        TrimRequest(ResourceRef(SourceSet.Single(source())), spec, output = output, context = context)
    @Test fun verifiedFullRangeTrimDisclosesRangesAndNoEncoding(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "trim-whole"); val backend = TrimBackend(bytes)
        val result = DefaultLivePhotoCore(backend).trim(request(output).copy(policy = MutationPolicy(preservation = PreservationPolicy.Strict,
            requiredGuarantees = listOf(Guarantee.BitstreamPreserving, Guarantee.MetadataPreserving)))).orThrow()
        assertEquals(Time.Zero.compareTo(result.actualStart), 0); assertEquals(Time(80, 1000u), result.actualEnd)
        assertFalse(result.wasTranscoded); assertTrue(result.wasBitstreamPreserved); assertFalse(result.retainedHiddenContent)
        assertEquals(TimeRange(Time(0, 1000u), Time(80, 1000u)), result.tracks.single().encoded)
        assertEquals(Bytes(bytes), output.committedAssets().values.single())
        result.operation.output.assets.forEach { it.readableSource?.close() }
    }
    @Test fun preferredCoveringDisclosesDeviationAndOnlyExactNeverEncode(): Unit = runImmediate {
        val spec = TrimSpec(TimeRange(Time(20, 1000u), Time(70, 1000u)))
        val output = MemoryOutputTransaction(context, "trim-preferred")
        val result = DefaultLivePhotoCore(TrimBackend(bytes)).trim(request(output, spec)).orThrow()
        assertEquals(spec.range.start, result.requestedStart); assertEquals(spec.range.end, result.requestedEnd)
        assertEquals(Time(0, 1000u), result.actualStart); assertEquals(Time(80, 1000u), result.actualEnd)
        result.operation.output.assets.forEach { it.readableSource?.close() }
        for (mode in listOf(TrimMode.LosslessOnly, TrimMode.Exact)) {
            val declined = MemoryOutputTransaction(context, "trim-$mode"); val backend = TrimBackend(bytes)
            val run = DefaultLivePhotoCore(backend).trim(request(declined, spec.copy(mode = mode)).copy(policy = MutationPolicy(transcode = TranscodePolicy.Explicit)))
            assertEquals(if (mode == TrimMode.Exact) "EXACT_TRIM_UNAVAILABLE" else "LOSSLESS_TRIM_UNAVAILABLE", assertIs<CoreResult.Failure>(run).error.code.value)
            assertEquals(0, backend.calls); assertEquals(TransactionState.Open, declined.query().orThrow().state)
        }
    }
    @Test fun explicitDeviationAudioAndBFrameGatesArePreflight(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "trim-deviation"); val backend = TrimBackend(bytes); val core = DefaultLivePhotoCore(backend)
        val spec = TrimSpec(TimeRange(Time(20, 1000u), Time(70, 1000u)), maxBoundaryDeviation = Time(10, 1000u))
        assertEquals("TRIM_BOUNDARY_DEVIATION_EXCEEDED", assertIs<CoreResult.Failure>(core.trim(request(output, spec))).error.code.value)
        val composition = GoogleFixtures.fullBox("ctts", GoogleFixtures.u32(1u) + GoogleFixtures.u32(2u) + GoogleFixtures.u32(1u))
        for (value in listOf(GoogleFixtures.video(aac = true).bytes, GoogleFixtures.video(composition = composition).bytes))
            assertEquals("LOSSLESS_TRIM_UNAVAILABLE", assertIs<CoreResult.Failure>(core.trim(request(output).copy(video = ResourceRef(SourceSet.Single(source(value)))))).error.code.value)
        assertEquals(0, backend.calls); assertTrue(output.committedAssets().isEmpty())
    }
    @Test fun exactBoundaryNeedsAnIdrNotJustASyncClaim(): Unit = runImmediate {
        val altered = bytes.copyOf()
        altered[GoogleFixtures.video().sampleOffset.toInt() + 4] = 0x41
        val output = MemoryOutputTransaction(context, "trim-false-sync"); val backend = TrimBackend(altered)
        assertEquals("LOSSLESS_TRIM_UNAVAILABLE", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).trim(request(output).copy(video = ResourceRef(SourceSet.Single(source(altered)))))).error.code.value)
        assertEquals(0, backend.calls)
    }
    @Test fun unrequestedTrackHeaderDurationChangeWithinParserToleranceIsRejected(): Unit = runImmediate {
        val changed = bytes.copyOf()
        val offset = (4 until changed.size - 4).first { changed.copyOfRange(it, it + 4).contentEquals("tkhd".encodeToByteArray()) } + 4
        GoogleFixtures.u32(79u).copyInto(changed, offset + 20)
        val output = MemoryOutputTransaction(context, "trim-duration")
        assertEquals("POSTCONDITION_FAILED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(TrimBackend(changed)).trim(request(output))).error.code.value)
        assertEquals(TransactionState.Aborted, output.query().orThrow().state); assertTrue(output.committedAssets().isEmpty())
    }
    @Test fun falseTimelineEncodingAndSampleBytesAreNotTrusted(): Unit = runImmediate {
        for (wrong in listOf("mapping", "encoding", "sample")) {
            val changed = bytes.copyOf().also { if (wrong == "sample") it[GoogleFixtures.video().sampleOffset.toInt() + 5] = 0x89.toByte() }
            val output = MemoryOutputTransaction(context, "trim-false-$wrong")
            assertEquals("POSTCONDITION_FAILED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(TrimBackend(changed, wrong)).trim(request(output))).error.code.value)
            assertEquals(TransactionState.Aborted, output.query().orThrow().state); assertTrue(output.committedAssets().isEmpty())
        }
    }
    @Test fun planNeverCallsBackendAndMissingBackendNeverSimulatesTrim(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "trim-plan"); val backend = TrimBackend(bytes)
        assertEquals(Availability.Conditional, DefaultLivePhotoCore(backend).plan(request(output)).orThrow().capabilities.availability)
        assertEquals(0, backend.calls); assertEquals(TransactionState.Open, output.query().orThrow().state)
        assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore().trim(request(output))).error.code.value)
    }
    @Test fun inputChangesAndPartialFailuresAbortAllOutputs(): Unit = runImmediate {
        for (wrong in listOf("failure", "changed")) {
            val original = source(); var changed = false
            val input = object : BinarySource by original {
                override suspend fun identity(): CoreResult<SourceIdentity> = original.identity().let { result ->
                    if (result is CoreResult.Success && changed) CoreResult.Success(result.value.copy(generation = GenerationToken("changed"))) else result
                }
            }
            val output = MemoryOutputTransaction(context, "trim-$wrong")
            val backend = TrimBackend(bytes, wrong, after = { if (wrong == "changed") changed = true })
            assertEquals(if (wrong == "failure") "REMUX_FAILED" else "SOURCE_CHANGED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).trim(request(output).copy(video = ResourceRef(SourceSet.Single(input))))).error.code.value)
            assertEquals(TransactionState.Aborted, output.query().orThrow().state); assertTrue(output.committedAssets().isEmpty())
        }
    }
    @Test fun exactToleranceNeverMeansAnEncoderFallback(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "trim-tolerance")
        val spec = TrimSpec(TimeRange(Time(1, 1000u), Time(79, 1000u)), TrimMode.Exact, exactTolerance = Time(1, 1000u))
        val result = DefaultLivePhotoCore(TrimBackend(bytes)).trim(request(output, spec)).orThrow()
        assertEquals(Time(0, 1000u), result.actualStart); assertEquals(Time(80, 1000u), result.actualEnd)
        assertEquals(Time(1, 1000u), result.exactTolerance); assertFalse(result.wasTranscoded)
        result.operation.output.assets.forEach { it.readableSource?.close() }
    }
    @Test fun beyondEndUnknownInbandDependenciesAndPrivateBoxesDoNotReachBackend(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "trim-invalid"); val backend = TrimBackend(bytes); val core = DefaultLivePhotoCore(backend)
        assertEquals("INVALID_PRESENTATION_TIMESTAMP", assertIs<CoreResult.Failure>(core.trim(request(output, TrimSpec(TimeRange(Time.Zero, Time(81, 1000u)))))).error.code.value)
        // Keep a VCL NAL so this exercises the planner, not an earlier malformed-sample error.
        val sampleOffset = GoogleFixtures.video().sampleOffset.toInt()
        val parameter = GoogleFixtures.u32(2u) + byteArrayOf(0x67, 0)
        val inBand = bytes.copyOfRange(0, sampleOffset) + parameter + bytes.copyOfRange(sampleOffset, bytes.size)
        val stszOffset = (4 until inBand.size - 4).first { inBand.copyOfRange(it, it + 4).contentEquals("stsz".encodeToByteArray()) } + 4
        GoogleFixtures.u32(12u).copyInto(inBand, stszOffset + 12)
        GoogleFixtures.u32(27u).copyInto(inBand, sampleOffset - 8)
        assertEquals("LOSSLESS_TRIM_UNAVAILABLE", assertIs<CoreResult.Failure>(core.trim(request(output).copy(video = ResourceRef(SourceSet.Single(source(inBand)))))).error.code.value)
        val private = bytes + GoogleFixtures.box("uuid", ByteArray(16))
        assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(core.trim(request(output).copy(video = ResourceRef(SourceSet.Single(source(private)))))).error.code.value)
        assertEquals(0, backend.calls); assertEquals(TransactionState.Open, output.query().orThrow().state)
    }
    @Test fun atomicityExactExtractionAndCancellationGatesDoNotCreateAssets(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "trim-atomic"); val backend = TrimBackend(bytes); val core = DefaultLivePhotoCore(backend)
        val nonAtomic = object : OutputTransaction by output { override fun capabilities(): OutputCapabilities = output.capabilities().copy(assetSetAtomic = false) }
        assertEquals("ATOMIC_PUBLICATION_UNAVAILABLE", assertIs<CoreResult.Failure>(core.trim(request(nonAtomic))).error.code.value)
        assertEquals("PRESERVATION_REQUIREMENT_FAILED", assertIs<CoreResult.Failure>(core.trim(request(output).copy(policy = MutationPolicy(requiredGuarantees = listOf(Guarantee.ExactExtraction))))).error.code.value)
        assertEquals("CANCELLED", assertIs<CoreResult.Failure>(core.trim(request(output).copy(context = context.copy(cancellation = Cancellation { true })))).error.code.value)
        assertEquals(0, backend.calls); assertTrue(output.committedAssets().isEmpty())
    }
    @Test fun embeddedResourceIsIsolatedAndOriginalLivePhotoKeyIsNotRewritten(): Unit = runImmediate {
        val original = GoogleFixtures.v1Photo(bytes, timestamp = "40000")
        val input = source(original); val output = MemoryOutputTransaction(context, "trim-embedded")
        val result = DefaultLivePhotoCore(TrimBackend(bytes)).trim(request(output).copy(video = ResourceRef(SourceSet.Single(input), livephoto.core.implementation.videoId(ProtocolIds.GoogleV1)))).orThrow()
        assertEquals(Bytes(original), input.readAt(0uL, original.size.toUInt()).orThrow())
        result.operation.output.assets.forEach { it.readableSource?.close() }
    }
    private class TrimBackend(val bytes: ByteArray, val wrong: String = "", val after: () -> Unit = {}) : MediaBackend {
        var calls = 0
        override fun capabilities(): MediaCapabilities = MediaCapabilities(listOf("synthetic-trim"), listOf(CapabilityEntry(Operation.Trim, Implementation.Experimental)))
        override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = attempt {
            job.validate().orThrow(); calls++
            val reader = BinaryReader((job.inputs.single().input as SourceSet.Single).source, job.context)
            val video = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
            val plan = planLosslessTrim(reader, video, job.trim!!)
            val actualReader = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("backend-trim-output")), job.context)
            val actual = BmffVideoProbe(actualReader).probe(ByteRange(0uL, bytes.size.toULong())).orThrow()
            val facts = videoFacts(actual)
            val handle = job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = facts.mime!!)).orThrow()
            handle.sink.write(Bytes(bytes)).orThrow(); handle.sink.close().orThrow(); after()
            if (wrong == "failure") fail("REMUX_FAILED", "Synthetic failure after partial staging", Stage.Trim)
            BackendResult(listOf(StagedAsset(handle.id, AssetRole.MotionVideo, facts.mime, bytes.size.toULong())), listOf(facts),
                listOf(ExecutionRecord(Stage.Trim, "synthetic-trim", "Synthetic test only", wrong == "encoding", true, false, videoFacts(video), facts)),
                tracks = listOf(plan.trackTrim), timelineMap = if (wrong == "mapping") emptyList() else plan.mapping)
        }
        override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = error("Unexpected probe")
        override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = error("Unexpected remux")
        override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = error("Unexpected transcode")
        override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = error("Unexpected frame")
    }
}
