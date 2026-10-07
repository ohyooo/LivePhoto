package livephoto.core

import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.videoFacts
import livephoto.core.implementation.videoId
import livephoto.core.memory.*
import kotlin.test.*

/** Synthetic container and hostile-backend tests, not decoder or device certification. */
class RemuxOperationsTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private fun source(bytes: ByteArray): MemoryBinarySource = MemoryBinarySource(Bytes(bytes), SourceId("remux-input"))
    private fun mov(bytes: ByteArray): ByteArray = bytes.copyOf().also { result ->
        "qt  ".encodeToByteArray().copyInto(result, 8)
        val visual = listOf("avc1", "hvc1").firstNotNullOf { type ->
            val start = offsetOrNull(result, type, offset(result, "stsd"))
            start?.let { start }
        }
        ("FFMP".encodeToByteArray() + GoogleFixtures.u32(512u) + GoogleFixtures.u32(512u)).copyInto(result, visual + 12)
    }
    private fun request(bytes: ByteArray, output: OutputTransaction, policy: MutationPolicy = MutationPolicy()): RemuxRequest =
        RemuxRequest(ResourceRef(SourceSet.Single(source(bytes))), VideoContainer.Mov, policy, output, context)
    private fun offset(bytes: ByteArray, type: String): Int = (4 until bytes.size - 4).first { bytes.copyOfRange(it, it + 4).contentEquals(type.encodeToByteArray()) } + 4
    private fun offsetOrNull(bytes: ByteArray, type: String, start: Int): Int? = (start until bytes.size - 4).firstOrNull { bytes.copyOfRange(it, it + 4).contentEquals(type.encodeToByteArray()) }?.plus(4)
    private suspend fun failure(bytes: ByteArray, backend: MediaBackend, expected: String): Unit {
        val output = MemoryOutputTransaction(context, "negative")
        assertEquals(expected, assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).remux(request(bytes, output))).error.code.value)
        assertTrue(output.committedAssets().isEmpty())
        assertEquals(TransactionState.Aborted, output.query().orThrow().state)
    }

    @Test fun verifiedRemuxPublishesOnceWithoutEncoding(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val output = MemoryOutputTransaction(context, "positive")
        val backend = CopyBackend(mov(bytes))
        val result = DefaultLivePhotoCore(backend).remux(request(bytes, output, MutationPolicy(preservation = PreservationPolicy.Strict,
            requiredGuarantees = listOf(Guarantee.BitstreamPreserving, Guarantee.MetadataPreserving)))).orThrow()
        assertEquals(1, backend.calls)
        assertEquals(VideoContainer.Mov, result.output.assets.single().videoContainer)
        assertEquals(Bytes(mov(bytes)), output.committedAssets().values.single())
        assertTrue(result.execution.any { it.stage == Stage.Remux && it.remuxed && !it.transcoded })
        assertTrue(result.execution.none { it.transcoded })
        assertEquals(Coverage.Partial, result.validation.coverage)
        assertEquals(GuaranteeOutcome.Verified, result.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }.outcome)
        result.output.assets.forEach { it.readableSource?.close() }
    }

    @Test fun allAudioSamplesHevcAndVfrAreIndependentlyPreserved(): Unit = runImmediate {
        val bytes = GoogleFixtures.video(aac = true, hevc = true, sampleDurations = 20u to 60u).bytes
        val output = MemoryOutputTransaction(context, "audio")
        val result = DefaultLivePhotoCore(CopyBackend(mov(bytes))).remux(request(bytes, output)).orThrow()
        assertEquals(2, result.execution.first().outputFacts!!.tracks.size)
        assertEquals(AudioCodec.Aac, result.execution.first().outputFacts!!.tracks.last().audioCodec)
        result.output.assets.forEach { it.readableSource?.close() }
    }

    @Test fun changedSampleBytesAbortBeforePublication(): Unit = runImmediate {
        val video = GoogleFixtures.video()
        val altered = mov(video.bytes).also { it[video.sampleOffset.toInt() + 5] = 0x89.toByte() }
        failure(video.bytes, CopyBackend(altered), "POSTCONDITION_FAILED")
    }
    @Test fun changedPresentationTimestampsAbortBeforePublication(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val changed = GoogleFixtures.video(composition = GoogleFixtures.fullBox("ctts", GoogleFixtures.u32(1u) + GoogleFixtures.u32(2u) + GoogleFixtures.u32(1u))).bytes
        failure(bytes, CopyBackend(mov(changed)), "POSTCONDITION_FAILED")
    }
    @Test fun changedDecoderConfigurationAbortBeforePublication(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val changed = mov(bytes).also { it[offset(it, "avcC") + 3] = 31 }
        failure(bytes, CopyBackend(changed), "POSTCONDITION_FAILED")
    }
    @Test fun changedOrdinaryHeaderMetadataAbortBeforePublication(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val changed = mov(bytes).also { it[offset(it, "mvhd") + 7] = 1 }
        failure(bytes, CopyBackend(changed), "POSTCONDITION_FAILED")
    }
    @Test fun changedTrackHeaderDurationWithinStructuralRoundingToleranceIsStillRejected(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val changed = mov(bytes).also { GoogleFixtures.u32(79u).copyInto(it, offset(it, "tkhd") + 20) }
        failure(bytes, CopyBackend(changed), "POSTCONDITION_FAILED")
    }
    @Test fun droppedAudioTrackIsRejectedEvenWhenVideoBytesAreUnchanged(): Unit = runImmediate {
        failure(GoogleFixtures.video(aac = true).bytes, CopyBackend(mov(GoogleFixtures.video().bytes)), "POSTCONDITION_FAILED")
    }
    @Test fun privateMetadataAndUnreferencedPayloadAreRejectedBeforeBackendWrites(): Unit = runImmediate {
        for (bytes in listOf(GoogleFixtures.video().bytes + GoogleFixtures.box("uuid", ByteArray(16)),
            GoogleFixtures.video().bytes + GoogleFixtures.box("free", byteArrayOf(1)),
            GoogleFixtures.video().bytes.also { "dby1".encodeToByteArray().copyInto(it, 24) })) {
            val output = MemoryOutputTransaction(context, "private")
            val backend = CopyBackend(mov(bytes))
            assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).remux(request(bytes, output))).error.code.value)
            assertEquals(0, backend.calls); assertEquals(TransactionState.Open, output.query().orThrow().state)
        }
    }
    @Test fun backendFailureAfterPartialWriteAbortsWholeTransaction(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        failure(bytes, CopyBackend(mov(bytes), failAfterWrite = true), "REMUX_FAILED")
    }
    @Test fun falseEncodingAndAssetDeclarationsAreNeverTrusted(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        failure(bytes, CopyBackend(mov(bytes), claimedEncoding = true), "POSTCONDITION_FAILED")
        failure(bytes, CopyBackend(mov(bytes), wrongSize = true), "POSTCONDITION_FAILED")
    }
    @Test fun planningDoesNotCallBackendOrTouchTransaction(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val output = MemoryOutputTransaction(context, "plan")
        val backend = CopyBackend(mov(bytes))
        val plan = DefaultLivePhotoCore(backend).plan(request(bytes, output)).orThrow()
        assertEquals(Availability.Conditional, plan.capabilities.availability)
        assertEquals(0, backend.calls); assertTrue(output.query().orThrow().assetIds.isEmpty())
        assertEquals(TransactionState.Open, output.query().orThrow().state)
    }
    @Test fun embeddedVideoIsIsolatedAndAllCarrierIdentitiesRemainGuarded(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val carrier = source(GoogleFixtures.v1Photo(video))
        val output = MemoryOutputTransaction(context, "embedded")
        val backend = CopyBackend(mov(video))
        val result = DefaultLivePhotoCore(backend).remux(RemuxRequest(ResourceRef(SourceSet.Single(carrier), videoId(ProtocolIds.GoogleV1)),
            VideoContainer.Mov, output = output, context = context)).orThrow()
        assertEquals(video.size.toULong(), backend.inputSize)
        assertEquals(1, output.committedAssets().size)
        assertEquals(GoogleFixtures.v1Photo(video).size.toULong(), carrier.size().orThrow())
        result.output.assets.forEach { it.readableSource?.close() }; carrier.close()
    }
    @Test fun missingBackendAndExactExtractionAreRejectedWithoutWriting(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val output = MemoryOutputTransaction(context, "missing")
        assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore().remux(request(bytes, output))).error.code.value)
        val backend = CopyBackend(mov(bytes))
        assertEquals("PRESERVATION_REQUIREMENT_FAILED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).remux(request(bytes, output,
            MutationPolicy(requiredGuarantees = listOf(Guarantee.ExactExtraction))))).error.code.value)
        assertEquals(0, backend.calls); assertEquals(TransactionState.Open, output.query().orThrow().state)
    }

    @Test fun inputIdentityChangeAfterBackendExecutionAborts(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val original = source(bytes)
        var changed = false
        val mutable = object : BinarySource by original {
            override suspend fun identity(): CoreResult<SourceIdentity> = original.identity().let { result ->
                if (result is CoreResult.Success && changed) CoreResult.Success(result.value.copy(generation = GenerationToken("changed"))) else result
            }
        }
        val output = MemoryOutputTransaction(context, "identity-change")
        val backend = CopyBackend(mov(bytes), afterWrite = { changed = true })
        val result = DefaultLivePhotoCore(backend).remux(RemuxRequest(ResourceRef(SourceSet.Single(mutable)), VideoContainer.Mov, output = output, context = context))
        assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(result).error.code.value)
        assertEquals(TransactionState.Aborted, output.query().orThrow().state); assertTrue(output.committedAssets().isEmpty())
        original.close()
    }
    @Test fun outputBudgetAndCancellationNeverPublish(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val small = Context(Limits(8_000_000uL, 256uL))
        val output = MemoryOutputTransaction(small, "budget")
        val result = DefaultLivePhotoCore(CopyBackend(mov(bytes))).remux(request(bytes, output).copy(context = small))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", assertIs<CoreResult.Failure>(result).error.code.value)
        assertEquals(TransactionState.Aborted, output.query().orThrow().state); assertTrue(output.committedAssets().isEmpty())
        val cancelled = context.copy(cancellation = Cancellation { true })
        val untouched = MemoryOutputTransaction(context, "cancel")
        val backend = CopyBackend(mov(bytes))
        assertEquals("CANCELLED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).remux(request(bytes, untouched).copy(context = cancelled))).error.code.value)
        assertEquals(0, backend.calls); assertEquals(TransactionState.Open, untouched.query().orThrow().state)
    }
    @Test fun unavailableAtomicityStopsBeforeBackendAndSourceIO(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val output = MemoryOutputTransaction(context, "no-atomicity")
        val incapable = object : OutputTransaction by output {
            override fun capabilities(): OutputCapabilities = output.capabilities().copy(assetSetAtomic = false)
        }
        val backend = CopyBackend(mov(bytes))
        assertEquals("ATOMIC_PUBLICATION_UNAVAILABLE", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).remux(request(bytes, incapable))).error.code.value)
        assertEquals(0, backend.calls); assertTrue(output.query().orThrow().assetIds.isEmpty())
    }

    private class CopyBackend(val bytes: ByteArray, val failAfterWrite: Boolean = false, val claimedEncoding: Boolean = false, val wrongSize: Boolean = false, val afterWrite: () -> Unit = {}) : MediaBackend {
        var calls = 0
        var inputSize = 0uL
        override fun capabilities(): MediaCapabilities = MediaCapabilities(listOf("synthetic-remux"), listOf(CapabilityEntry(Operation.Remux, Implementation.Experimental)))
        override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = attempt {
            calls++
            job.validate().orThrow()
            assertNull(job.inputs.single().resourceId); assertNull(job.inputs.single().snapshot)
            val input = BinaryReader((job.inputs.single().input as SourceSet.Single).source, job.context)
            inputSize = input.identity().orThrow().size
            val before = BmffVideoProbe(input).probe(ByteRange(0uL, input.identity().orThrow().size)).orThrow()
            val handle = job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = "video/quicktime")).orThrow()
            BinaryWriter(handle.sink, job.context).writeAll(Bytes(bytes)).orThrow(); handle.sink.close().orThrow()
            afterWrite()
            if (failAfterWrite) fail("REMUX_FAILED", "Synthetic failure after staging", Stage.Remux)
            val reader = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("synthetic-backend")), job.context)
            val facts = videoFacts(BmffVideoProbe(reader).probe(ByteRange(0uL, bytes.size.toULong())).orThrow())
            BackendResult(listOf(StagedAsset(handle.id, AssetRole.MotionVideo, "video/quicktime", bytes.size.toULong() + if (wrongSize) 1uL else 0uL)), listOf(facts),
                listOf(ExecutionRecord(Stage.Remux, "synthetic-remux", "Synthetic independently packaged fixture", claimedEncoding, true, false, videoFacts(before), facts)))
        }
        override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = error("Unexpected probe")
        override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = error("Unexpected trim")
        override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = error("Unexpected transcode")
        override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = error("Unexpected frame")
    }
}
