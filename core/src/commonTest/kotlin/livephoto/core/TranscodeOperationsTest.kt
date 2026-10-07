package livephoto.core

import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

class TranscodeOperationsTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private fun request(tx: OutputTransaction, bytes: ByteArray = GoogleFixtures.video().bytes): TranscodeRequest = TranscodeRequest(
        ResourceRef(SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("transcode-input")))), VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4),
        MutationPolicy(transcode = TranscodePolicy.Explicit), tx, context)
    @Test fun authorizedEncodingReportsChangedBitstreamAndOnlyPartialMetadataEvidence(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "transcode-success"); val backend = Backend()
        val result = DefaultLivePhotoCore(backend).transcode(request(tx, GoogleFixtures.video(sampleDurations = 20u to 60u).bytes)).orThrow()
        assertEquals(GuaranteeOutcome.Changed, result.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }.outcome)
        assertEquals(GuaranteeOutcome.Unknown, result.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }.outcome)
        assertEquals(Coverage.Partial, result.validation.coverage)
        assertTrue(result.execution.any { it.stage == Stage.Transcode && it.transcoded && it.hardwareUsed == false && !it.remuxed })
        result.output.assets.forEach { it.readableSource?.close() }
    }
    @Test fun authorizationStrictAndRequiredGuaranteesFailBeforeBackendWork(): Unit = runImmediate {
        for (policy in listOf(MutationPolicy(), MutationPolicy(preservation = PreservationPolicy.Strict, transcode = TranscodePolicy.Explicit)) + Guarantee.entries.map {
                MutationPolicy(requiredGuarantees = listOf(it), transcode = TranscodePolicy.Explicit) }) {
            val tx = MemoryOutputTransaction(context, "transcode-policy"); val backend = Backend()
            val error = assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).transcode(request(tx).copy(policy = policy))).error
            assertEquals(if (policy.transcode == TranscodePolicy.Forbid) "TRANSCODE_NOT_AUTHORIZED" else "PRESERVATION_REQUIREMENT_FAILED", error.code.value)
            assertEquals(0, backend.calls); assertEquals(TransactionState.Open, tx.query().orThrow().state)
        }
    }
    @Test fun audioReorderHevcAndPrivateMetadataAreRefused(): Unit = runImmediate {
        val variants = listOf(GoogleFixtures.video(aac = true).bytes, GoogleFixtures.video(hevc = true).bytes,
            GoogleFixtures.video(composition = GoogleFixtures.fullBox("ctts", GoogleFixtures.u32(1u) + GoogleFixtures.u32(2u) + GoogleFixtures.u32(1u))).bytes,
            GoogleFixtures.video().bytes + GoogleFixtures.box("uuid", ByteArray(16)))
        for (bytes in variants) {
            val tx = MemoryOutputTransaction(context, "transcode-unsupported"); val backend = Backend()
            assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).transcode(request(tx, bytes)))
            assertEquals(0, backend.calls); assertTrue(tx.committedAssets().isEmpty())
        }
    }
    @Test fun explicitResizeCfrToneMapBitrateAndOtherTargetAreNotSilentlyIgnored(): Unit = runImmediate {
        val encodings = listOf(VideoEncoding(VideoCodec.Hevc, VideoContainer.Mp4), VideoEncoding(VideoCodec.Avc, VideoContainer.Mov),
            VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4, width = 2u), VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4, bitrate = 1000uL),
            VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4, dynamicRange = DynamicRangePolicy.ExplicitToneMapToSdr),
            VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4, frameRatePolicy = FrameRatePolicy.ExplicitConstantRate, constantFrameRate = RationalRate(25u, 1u)))
        for (encoding in encodings) {
            val tx = MemoryOutputTransaction(context, "transcode-encoding"); val backend = Backend()
            assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).transcode(request(tx).copy(encoding = encoding))).error.code.value)
            assertEquals(0, backend.calls)
        }
    }
    @Test fun planDoesNotEncodeOrPublishAndNoBackendNeverClaimsCapability(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "transcode-plan"); val backend = Backend()
        val plan = DefaultLivePhotoCore(backend).plan(request(tx)).orThrow()
        assertEquals(Availability.Conditional, plan.capabilities.availability); assertEquals(0, backend.calls)
        assertEquals(TransactionState.Open, tx.query().orThrow().state)
        assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore().transcode(request(tx))).error.code.value)
    }
    @Test fun zeroOutputBudgetDoesNotLaunchAnEncoder(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "transcode-zero-budget"); val backend = Backend()
        assertEquals("RESOURCE_LIMIT_EXCEEDED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).transcode(request(tx).copy(context = context.copy(limits = context.limits.copy(maxOutputBytes = 0uL))))).error.code.value)
        assertEquals(0, backend.calls); assertEquals(TransactionState.Open, tx.query().orThrow().state)
    }
    @Test fun falseExecutionAndChangedTimelineAreRejectedBeforeCommit(): Unit = runImmediate {
        for (backend in listOf(Backend(falseExecution = true), Backend(changeTimeline = true))) {
            val tx = MemoryOutputTransaction(context, "transcode-hostile")
            assertEquals("POSTCONDITION_FAILED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).transcode(request(tx))).error.code.value)
            assertEquals(TransactionState.Aborted, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
        }
    }
    @Test fun partialBackendFailureAndCancellationCannotPublish(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "transcode-partial")
        assertEquals("ENCODE_FAILED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(Backend(partialFailure = true)).transcode(request(tx))).error.code.value)
        assertEquals(TransactionState.Aborted, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
        val cancelled = request(MemoryOutputTransaction(context, "transcode-cancel")).copy(context = context.copy(cancellation = Cancellation { true }))
        assertEquals("CANCELLED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(Backend()).transcode(cancelled)).error.code.value)
    }
    @Test fun sourceMutationDuringBackendWorkPreventsCommit(): Unit = runImmediate {
        val original = MemoryBinarySource(Bytes(GoogleFixtures.video().bytes), SourceId("transcode-mutable")); var changed = false
        val source = object : BinarySource by original { override suspend fun identity(): CoreResult<SourceIdentity> = original.identity().let { result ->
            if (changed && result is CoreResult.Success) CoreResult.Success(result.value.copy(generation = GenerationToken("changed"))) else result
        } }
        val tx = MemoryOutputTransaction(context, "transcode-mutation")
        assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(Backend(after = { changed = true })).transcode(request(tx).copy(video = ResourceRef(SourceSet.Single(source))))).error.code.value)
        assertTrue(tx.committedAssets().isEmpty())
    }
    private class Backend(val falseExecution: Boolean = false, val changeTimeline: Boolean = false, val partialFailure: Boolean = false, val after: () -> Unit = {}) : MediaBackend {
        var calls = 0
        override fun capabilities(): MediaCapabilities = MediaCapabilities(listOf("synthetic-transcode"), listOf(CapabilityEntry(Operation.Transcode, Implementation.Experimental)))
        override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = attempt {
            job.validate().orThrow(); calls++
            val reader = BinaryReader((job.inputs.single().input as SourceSet.Single).source, job.context)
            val identity = reader.identity().orThrow(); val video = BmffVideoProbe(reader).probe(ByteRange(0uL, identity.size)).orThrow()
            val bytes = reader.readExactly(0uL, identity.size.toUInt()).orThrow().toByteArray().also { it[it.size - 1] = 0x23 }
            // Same total duration but redistribute VFR boundaries in a separate hostile fixture.
            val out = if (changeTimeline) GoogleFixtures.video(sampleDurations = 20u to 60u).bytes else bytes
            val actual = BmffVideoProbe(BinaryReader(MemoryBinarySource(Bytes(out), SourceId("synthetic-encoded")), job.context)).probe(ByteRange(0uL, out.size.toULong())).orThrow()
            val handle = job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = "video/mp4")).orThrow()
            handle.sink.write(Bytes(out)).orThrow()
            if (partialFailure) fail("ENCODE_FAILED", "Synthetic partial encoder failure", Stage.Transcode)
            handle.sink.close().orThrow(); after()
            val facts = videoFacts(actual)
            BackendResult(listOf(StagedAsset(handle.id, AssetRole.MotionVideo, "video/mp4", out.size.toULong())), listOf(facts),
                listOf(ExecutionRecord(Stage.Transcode, "synthetic-transcode", "Synthetic protocol test, no real encode claim", !falseExecution, false, false, videoFacts(video), facts)))
        }
        override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = error("Unexpected probe")
        override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = error("Unexpected trim")
        override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = error("Unexpected remux")
        override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = error("Unexpected frame")
    }
}
