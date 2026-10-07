package livephoto.core

import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

class ReplaceOperationsTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private fun request(output: OutputTransaction, bytes: ByteArray = GoogleFixtures.v1Photo()): ReplaceRequest = ReplaceRequest(
        SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("replace-input"))), CoverPosition.FrameIndex(1uL), ImageEncoding(ImageFormat.Jpeg), output = output, context = context)

    @Test fun imageChangesButOriginalVideoAndUnrequestedKeyDoNot(): Unit = runImmediate {
        for (bytes in listOf(GoogleFixtures.v1Photo(), GoogleFixtures.v2Photo())) {
            val output = MemoryOutputTransaction(context, "replace-success-${bytes.size}")
            val result = DefaultLivePhotoCore(FrameBackend()).replacePrimaryImageFromFrame(request(output, bytes)).orThrow()
            val session = SourceSession.open(SourceSet.Single(result.output.assets.single().readableSource!!), context, ParseBudget(context)).orThrow()
            assertEquals(Time(0, 1_000_000u), session.inspection.keyPhoto.position)
            assertEquals(Bytes(GoogleFixtures.video().bytes), session.reader.readExactly(session.bindings.single().video!!.offset, GoogleFixtures.video().bytes.size.toUInt()).orThrow())
            assertEquals(GuaranteeOutcome.Changed, result.preservation.records.single { it.guarantee == Guarantee.ImageDataPreserving }.outcome)
            assertEquals(GuaranteeOutcome.Verified, result.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }.outcome)
            assertTrue(result.execution.any { it.stage == Stage.EncodeImage }); assertTrue(result.execution.none { it.transcoded })
            result.output.assets.forEach { it.readableSource?.close() }
        }
    }
    @Test fun keySynchronizationIsAnIndependentExplicitOption(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "replace-update-key")
        val result = DefaultLivePhotoCore(FrameBackend()).replacePrimaryImageFromFrame(request(output).copy(updateKeyPosition = true)).orThrow()
        assertEquals(Time(40_000, 1_000_000u), result.keyPhoto?.position)
        val session = SourceSession.open(SourceSet.Single(result.output.assets.single().readableSource!!), context, ParseBudget(context)).orThrow()
        assertEquals(Time(40_000, 1_000_000u), session.inspection.keyPhoto.position)
        result.output.assets.forEach { it.readableSource?.close() }
    }
    @Test fun strictAndImagePreservationConflictBeforeDecoderWork(): Unit = runImmediate {
        for (policy in listOf(MutationPolicy(preservation = PreservationPolicy.Strict), MutationPolicy(requiredGuarantees = listOf(Guarantee.ImageDataPreserving)), MutationPolicy(requiredGuarantees = listOf(Guarantee.ExactExtraction)))) {
            val output = MemoryOutputTransaction(context, "replace-policy"); val backend = FrameBackend()
            assertEquals("PRESERVATION_REQUIREMENT_FAILED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).replacePrimaryImageFromFrame(request(output).copy(policy = policy))).error.code.value)
            assertEquals(0, backend.calls); assertEquals(TransactionState.Open, output.query().orThrow().state)
        }
    }
    @Test fun ordinaryIccExifAndUnknownXmpAreNeverReusedOrDropped(): Unit = runImmediate {
        val plain = GoogleFixtures.v1Photo()
        val variants = listOf(GoogleFixtures.segment(0xe2, "ICC_PROFILE\u0000".encodeToByteArray() + byteArrayOf(1, 1, 7)),
            GoogleFixtures.segment(0xe1, "Exif\u0000\u0000".encodeToByteArray() + byteArrayOf(77, 77, 0, 42) + GoogleFixtures.u32(8u) + ByteArray(6)))
            .map { plain.copyOfRange(0, 2) + it + plain.copyOfRange(2, plain.size) } +
            GoogleFixtures.v1Photo(extra = "<private:Note xmlns:private='urn:private'>keep</private:Note>")
        for (bytes in variants) {
            val output = MemoryOutputTransaction(context, "replace-metadata-${bytes.size}"); val backend = FrameBackend()
            assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).replacePrimaryImageFromFrame(request(output, bytes))).error.code.value)
            assertEquals(0, backend.calls); assertTrue(output.committedAssets().isEmpty())
        }
    }
    @Test fun planningIsReadOnlyAndFrameFailureDoesNotTouchPublicTransaction(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "replace-plan"); val backend = FrameBackend()
        assertEquals(Availability.Conditional, DefaultLivePhotoCore(backend).plan(request(output)).orThrow().capabilities.availability)
        assertEquals(0, backend.calls); assertEquals(TransactionState.Open, output.query().orThrow().state)
        val bad = FrameBackend(failDecode = true)
        assertEquals("DECODE_FAILED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(bad).replacePrimaryImageFromFrame(request(output))).error.code.value)
        assertEquals(TransactionState.Open, output.query().orThrow().state); assertTrue(output.committedAssets().isEmpty())
    }
    @Test fun canonicalJfifIsComparedBeforePublicationAndUnknownDensityOrThumbnailIsRefused(): Unit = runImmediate {
        val jfif = byteArrayOf(0x4a, 0x46, 0x49, 0x46, 0, 1, 2, 0, 0, 1, 0, 1, 0, 0)
        fun source(payload: ByteArray): ByteArray {
            val plain = GoogleFixtures.v1Photo()
            return plain.copyOfRange(0, 2) + GoogleFixtures.segment(0xe0, payload) + plain.copyOfRange(2, plain.size)
        }
        val output = MemoryOutputTransaction(context, "replace-jfif")
        val result = DefaultLivePhotoCore(FrameBackend(jfif = jfif)).replacePrimaryImageFromFrame(request(output, source(jfif))).orThrow()
        val record = result.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }
        assertEquals(record.sourceDigest, record.outputDigest)
        result.output.assets.forEach { it.readableSource?.close() }
        val mismatch = MemoryOutputTransaction(context, "replace-jfif-mismatch")
        assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(FrameBackend()).replacePrimaryImageFromFrame(request(mismatch, source(jfif)))).error.code.value)
        assertEquals(TransactionState.Open, mismatch.query().orThrow().state)
        for (bad in listOf(jfif.copyOf().also { it[7] = 1 }, jfif.copyOf().also { it[12] = 1; it[13] = 1 } + byteArrayOf(1, 2, 3), jfif + byteArrayOf(7))) {
            val backend = FrameBackend(jfif = jfif); val tx = MemoryOutputTransaction(context, "replace-jfif-refuse-${bad.size}")
            assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).replacePrimaryImageFromFrame(request(tx, source(bad)))).error.code.value)
            assertEquals(0, backend.calls); assertTrue(tx.committedAssets().isEmpty())
        }
    }
    @Test fun newJfifCannotBeReportedAsOriginalMetadataPreserved(): Unit = runImmediate {
        val jfif = byteArrayOf(0x4a, 0x46, 0x49, 0x46, 0, 1, 2, 0, 0, 1, 0, 1, 0, 0)
        val core = DefaultLivePhotoCore(FrameBackend(jfif = jfif)); val output = MemoryOutputTransaction(context, "replace-added-jfif")
        val result = core.replacePrimaryImageFromFrame(request(output)).orThrow()
        val record = result.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }
        assertEquals(GuaranteeOutcome.Unknown, record.outcome); assertNotEquals(record.sourceDigest, record.outputDigest)
        result.output.assets.forEach { it.readableSource?.close() }
        val required = MemoryOutputTransaction(context, "replace-required-metadata")
        assertEquals("PRESERVATION_REQUIREMENT_FAILED", assertIs<CoreResult.Failure>(core.replacePrimaryImageFromFrame(request(required).copy(policy = MutationPolicy(requiredGuarantees = listOf(Guarantee.MetadataPreserving))))).error.code.value)
        assertEquals(TransactionState.Open, required.query().orThrow().state); assertTrue(required.committedAssets().isEmpty())
    }
    @Test fun sourceChangeDuringPrivateFrameWorkPreventsPublicPublication(): Unit = runImmediate {
        val original = MemoryBinarySource(Bytes(GoogleFixtures.v1Photo()), SourceId("replace-mutable")); var changed = false
        val source = object : BinarySource by original { override suspend fun identity(): CoreResult<SourceIdentity> = original.identity().let { value ->
            if (value is CoreResult.Success && changed) CoreResult.Success(value.value.copy(generation = GenerationToken("changed"))) else value
        } }
        val output = MemoryOutputTransaction(context, "replace-source-change")
        val backend = FrameBackend(after = { changed = true })
        assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).replacePrimaryImageFromFrame(request(output).copy(input = SourceSet.Single(source)))).error.code.value)
        assertEquals(TransactionState.Open, output.query().orThrow().state); assertTrue(output.committedAssets().isEmpty())
    }
    @Test fun finalWriteFailureAbortsWithoutPublishingPrivateFrame(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "replace-final-failure")
        val failing = object : OutputTransaction by output {
            override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.WriteProtocol, "Synthetic final staging failure"))
        }
        val backend = FrameBackend()
        assertEquals("IO_WRITE_FAILED", assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backend).replacePrimaryImageFromFrame(request(failing))).error.code.value)
        assertEquals(1, backend.calls); assertEquals(TransactionState.Aborted, output.query().orThrow().state); assertTrue(output.committedAssets().isEmpty())
    }
    private class FrameBackend(val failDecode: Boolean = false, val after: () -> Unit = {}, val jfif: ByteArray? = null) : MediaBackend {
        var calls = 0
        override fun capabilities(): MediaCapabilities = MediaCapabilities(listOf("synthetic-replace"), listOf(CapabilityEntry(Operation.ExtractFrame, Implementation.Experimental)))
        override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = attempt {
            job.validate().orThrow(); calls++; if (failDecode) fail("DECODE_FAILED", "Synthetic decode failure", Stage.DecodeFrame)
            val reader = BinaryReader((job.inputs.single().input as SourceSet.Single).source, job.context)
            val video = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow(); val selected = selectFrame(video, job.position!!)
            val bytes = GoogleFixtures.jpeg(jfif?.let { GoogleFixtures.segment(0xe0, it) } ?: byteArrayOf()).also { it[it.size - 3] = 0x2f }
            val handle = job.destination.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/jpeg")).orThrow()
            handle.sink.write(Bytes(bytes)).orThrow(); handle.sink.close().orThrow(); after()
            val facts = MediaFacts(imageFormat = ImageFormat.Jpeg, mime = "image/jpeg", width = 1u, height = 1u, coverage = Coverage.Partial)
            BackendResult(listOf(StagedAsset(handle.id, AssetRole.PrimaryImage, "image/jpeg", bytes.size.toULong())), listOf(facts),
                listOf(Stage.DecodeFrame, Stage.EncodeImage).map { ExecutionRecord(it, "synthetic-replace", "Synthetic image only", false, false, false, videoFacts(video), facts) },
                actualFrameTime = selected.time, actualFrameIndex = selected.index, actualFrameTrack = TrackId("1"))
        }
        override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = error("Unexpected probe")
        override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = error("Unexpected trim")
        override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = error("Unexpected remux")
        override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = error("Unexpected transcode")
    }
}
