package livephoto.core

import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

class ExactTrimTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private suspend fun plan(spec: TrimSpec, policy: MutationPolicy = MutationPolicy(transcode = TranscodePolicy.Explicit)): CoreResult<PlannedTrim> = attempt {
        val bytes = GoogleFixtures.video().bytes
        val reader = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("exact-trim")), context)
        planTrim(reader, BmffVideoProbe(reader).probe(ByteRange(0uL, bytes.size.toULong())).orThrow(), spec, policy)
    }
    private val exact = TrimSpec(TimeRange(Time(40, 1000u), Time(80, 1000u)), TrimMode.Exact)
    @Test fun exactNonSyncFrameRequiresAuthorizationAndDisclosesCodingChange(): Unit = runImmediate {
        assertEquals("EXACT_TRIM_UNAVAILABLE", assertIs<CoreResult.Failure>(plan(exact, MutationPolicy())).error.code.value)
        val selected = plan(exact).orThrow()
        assertTrue(selected.encoded); assertTrue(selected.trackTrim.transcoded); assertFalse(selected.trackTrim.samplesPreserved)
        assertEquals(Time(40, 1000u), selected.boundaries.start); assertEquals(1, selected.boundaries.samples.size)
        assertEquals(0L, selected.boundaries.expected().tracks.single().samples.single().presentationTime)
    }
    @Test fun strictAndRequiredGuaranteesRejectEncodingBeforeMediaWork(): Unit = runImmediate {
        for (policy in listOf(MutationPolicy(preservation = PreservationPolicy.Strict, transcode = TranscodePolicy.Explicit)) + Guarantee.entries.map {
            MutationPolicy(requiredGuarantees = listOf(it), transcode = TranscodePolicy.Explicit)
        }) assertEquals("PRESERVATION_REQUIREMENT_FAILED", assertIs<CoreResult.Failure>(plan(exact, policy)).error.code.value)
    }
    @Test fun preferredNeverEncodesAndExactAtIdrStillPrefersLossless(): Unit = runImmediate {
        assertFalse(plan(exact.copy(mode = TrimMode.LosslessPreferred)).orThrow().encoded)
        assertFalse(plan(exact.copy(range = TimeRange(Time.Zero, Time(80, 1000u)))).orThrow().encoded)
    }
    @Test fun fractionalBoundaryIsNotSilentlyRounded(): Unit = runImmediate {
        assertEquals("EXACT_TRIM_UNAVAILABLE", assertIs<CoreResult.Failure>(plan(exact.copy(range = TimeRange(Time(41, 1000u), Time(80, 1000u))))).error.code.value)
    }
    @Test fun encodedExecutionTimelineMetadataFailureAndMutationCannotPublish(): Unit = runImmediate {
        for (wrong in listOf("", "execution", "mapping", "timeline", "metadata", "failure", "mutation")) {
            val original = MemoryBinarySource(Bytes(CreateTrimOperationsTest().fourSampleVideo()), SourceId("exact-four")); var changed = false
            val source = object : BinarySource by original {
                override suspend fun identity(): CoreResult<SourceIdentity> = original.identity().let { result ->
                    if (changed && result is CoreResult.Success) CoreResult.Success(result.value.copy(generation = GenerationToken("changed"))) else result
                }
            }
            val output = MemoryOutputTransaction(context, "exact-hostile-$wrong")
            val backend = EncodedBackend(wrong) { if (wrong == "mutation") changed = true }
            val request = TrimRequest(ResourceRef(SourceSet.Single(source)), exact.copy(range = TimeRange(Time(40, 1000u), Time(120, 1000u))),
                MutationPolicy(transcode = TranscodePolicy.Explicit), output, context)
            DefaultLivePhotoCore(backend).plan(request).orThrow(); assertEquals(0, backend.calls)
            val run = DefaultLivePhotoCore(backend).trim(request)
            if (wrong.isEmpty()) {
                val result = run.orThrow(); assertTrue(result.wasTranscoded); assertFalse(result.wasBitstreamPreserved)
                assertEquals(GuaranteeOutcome.Changed, result.operation.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }.outcome)
                assertEquals(GuaranteeOutcome.Unknown, result.operation.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }.outcome)
                result.operation.output.assets.forEach { it.readableSource?.close() }
            } else {
                assertEquals(when (wrong) { "failure" -> "ENCODE_FAILED"; "mutation" -> "SOURCE_CHANGED"; else -> "POSTCONDITION_FAILED" }, assertIs<CoreResult.Failure>(run).error.code.value)
                assertTrue(output.committedAssets().isEmpty()); assertEquals(TransactionState.Aborted, output.query().orThrow().state)
            }
        }
    }
    @Test fun createConvertTransferChangedCodingProofAndMapSourceKeyOnlyOnce(): Unit = runImmediate {
        val bytes = CreateTrimOperationsTest().fourSampleVideo()
        val image = MemoryBinarySource(Bytes(GoogleFixtures.jpeg()), SourceId("exact-create-image"))
        val video = MemoryBinarySource(Bytes(bytes), SourceId("exact-create-video"))
        val live = MemoryBinarySource(Bytes(GoogleFixtures.v1Photo(bytes, timestamp = "80000")), SourceId("exact-convert-live"))
        val spec = exact.copy(range = TimeRange(Time(40, 1000u), Time(120, 1000u)))
        val policy = MutationPolicy(transcode = TranscodePolicy.Explicit)
        for (target in listOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2)) for (convert in listOf(false, true)) {
            val output = MemoryOutputTransaction(context, "exact-composite-$target-$convert")
            val backend = EncodedBackend("") {}; val core = DefaultLivePhotoCore(backend)
            val request = CreateRequest(image, video, ProtocolSelector(target), edits = EditSpec(spec, CoverPosition.FrameIndex(2uL)), policy = policy, output = output, context = context)
            val conversion = ConvertRequest(SourceSet.Single(live), ProtocolSelector(target), edits = EditSpec(trim = spec), sameTarget = SameTargetPolicy.Normalize, policy = policy, output = output, context = context)
            if (convert) core.plan(conversion).orThrow() else core.plan(request).orThrow()
            assertEquals(0, backend.calls); assertEquals(TransactionState.Open, output.query().orThrow().state)
            val result = (if (convert) core.convert(conversion) else core.create(request)).orThrow()
            try {
                assertEquals(0, result.keyPhoto!!.position!!.compareTo(Time(40, 1000u)))
                assertTrue(result.execution.any { it.stage == Stage.Trim && it.transcoded })
                assertEquals(GuaranteeOutcome.Changed, result.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }.outcome)
                assertEquals(GuaranteeOutcome.Unknown, result.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }.outcome)
                assertEquals(Value.BooleanValue(true), (result.preservation.changes.single { it.selector == "videoTrim" }.after as Value.ObjectValue).entries["wasTranscoded"])
                val source = SourceSet.Single(result.output.assets.single().readableSource!!)
                assertEquals(Verdict.Valid, core.validate(ValidationRequest(source, layers = listOf(Layer.Structure, Layer.Protocol), context = context)).orThrow().verdict)
            } finally { result.output.assets.forEach { it.readableSource?.close() } }
        }
    }
    private class EncodedBackend(val wrong: String, val after: () -> Unit) : MediaBackend {
        var calls = 0
        override fun capabilities(): MediaCapabilities = MediaCapabilities(listOf("synthetic-exact"), listOf(CapabilityEntry(Operation.Trim, Implementation.Experimental)))
        override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = attempt {
            job.validate().orThrow(); calls++
            val reader = BinaryReader((job.inputs.single().input as SourceSet.Single).source, job.context)
            val before = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
            val plan = planTrim(reader, before, job.trim!!, job.policy)
            val bytes = (if (wrong == "timeline") GoogleFixtures.video(sampleDurations = 20u to 60u).bytes else GoogleFixtures.video().bytes).copyOf()
            if (wrong == "metadata") {
                val offset = (4 until bytes.size - 4).first { bytes.copyOfRange(it, it + 4).contentEquals("mdhd".encodeToByteArray()) } + 4
                bytes[offset + 20] = 1 // ordinary language field, not an authorized duration/configuration edit
            }
            val actual = BmffVideoProbe(BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("synthetic-output")), job.context)).probe(ByteRange(0uL, bytes.size.toULong())).orThrow()
            val facts = videoFacts(actual); val handle = job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = "video/mp4")).orThrow()
            handle.sink.write(Bytes(bytes)).orThrow(); handle.sink.close().orThrow(); after()
            if (wrong == "failure") fail("ENCODE_FAILED", "Synthetic encoder failure", Stage.Trim)
            BackendResult(listOf(StagedAsset(handle.id, AssetRole.MotionVideo, "video/mp4", bytes.size.toULong())), listOf(facts),
                listOf(ExecutionRecord(Stage.Trim, "synthetic-exact", "Synthetic verification fixture, not real encoding", wrong != "execution", false, false, videoFacts(before), facts)),
                listOf(plan.trackTrim), if (wrong == "mapping") emptyList() else plan.boundaries.mapping)
        }
        override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = error("Unexpected probe")
        override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = error("Unexpected remux")
        override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = error("Unexpected transcode")
        override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = error("Unexpected frame")
    }
}
