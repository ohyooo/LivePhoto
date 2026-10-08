package livephoto.core.samsung

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.videoFacts
import livephoto.core.memory.*
import kotlin.test.*

/** Independent indexed SEF fixtures and adversarial backends; no decoder/device claim. */
class SamsungRemuxRepairTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private fun mov(bytes: ByteArray): ByteArray = bytes.copyOf().also { result ->
        "qt  ".encodeToByteArray().copyInto(result, 8)
        val start = offsetOrNull(result, "stsd")!!
        val type = if (offsetOrNull(result, "hvc1", start) != null) "hvc1" else "avc1"
        ("FFMP".encodeToByteArray() + GoogleFixtures.u32(512u) + GoogleFixtures.u32(512u)).copyInto(result, offsetOrNull(result, type, start)!! + 16)
    }
    private fun offsetOrNull(bytes: ByteArray, type: String, start: Int = 4): Int? = (start until bytes.size - 4).firstOrNull { bytes.copyOfRange(it, it + 4).contentEquals(type.encodeToByteArray()) }
    private fun source(bytes: ByteArray): MemoryBinarySource = MemoryBinarySource(Bytes(bytes), SourceId("repair-source"))
    private fun request(input: BinarySource, output: OutputTransaction? = null, dry: Boolean = true,
        allowed: List<IssueCode> = emptyList(), policy: MutationPolicy = MutationPolicy()): RepairRequest =
        RepairRequest(SourceSet.Single(input), RepairMode.ExplicitRemux, allowedIssueCodes = allowed, dryRun = dry, policy = policy, output = output, context = context)
    private fun carrier(video: ByteArray, key: Long = -1): ByteArray = SamsungFixtures.photo(video,
        xmp = true, timestamp = key.toString()).bytes
    private fun backend(bytes: ByteArray, encoded: Boolean = false, after: () -> Unit = {}): CopyBackend = CopyBackend(bytes, encoded, after)

    @Test fun previewAndAllowlistNeverExecuteBackendOrStage(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val input = source(carrier(mov(bytes)))
        val tx = MemoryOutputTransaction(context, "preview")
        val processor = backend(bytes)
        val core = DefaultLivePhotoCore(processor)
        val preview = core.repair(request(input, tx)).orThrow()
        assertEquals(listOf("videoContainer"), preview.proposedChanges.map { it.selector })
        assertTrue(preview.issuesBefore.any { it.code.value == "UNSUPPORTED_CONTAINER" })
        assertTrue(preview.blocked.isEmpty(), preview.blocked.toString()); assertNull(preview.operation)
        val plan = core.plan(request(input, tx)).orThrow()
        assertTrue(plan.steps.none { it.stage == Stage.Remux })
        val blocked = core.repair(request(input, tx, dry = false, allowed = listOf(IssueCode("SEF_DIRECTORY_INVALID")))).orThrow()
        assertTrue(blocked.blocked.any { it.code.value == "UNSUPPORTED_CONTAINER" })
        assertEquals(0, processor.calls); assertEquals(TransactionState.Open, tx.query().orThrow().state)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
    }

    @Test fun exactSamplesImageMetadataAndKnownOrUnknownKeySurviveOnePublication(): Unit = runImmediate {
        for (key in listOf(-1L, 20L)) for (audio in listOf(false, true)) {
            val bytes = GoogleFixtures.video(aac = audio, hevc = audio, sampleDurations = 20u to 60u).bytes
            val original = carrier(mov(bytes), key)
            val input = source(original)
            val tx = MemoryOutputTransaction(context, "apply-$key-$audio")
            val processor = backend(bytes)
            val core = DefaultLivePhotoCore(processor)
            val policy = MutationPolicy(preservation = PreservationPolicy.Strict,
                requiredGuarantees = listOf(Guarantee.BitstreamPreserving, Guarantee.ImageDataPreserving, Guarantee.MetadataPreserving))
            val result = core.repair(request(input, tx, dry = false, allowed = listOf(IssueCode("UNSUPPORTED_CONTAINER")), policy = policy)).orThrow()
            val operation = assertNotNull(result.operation)
            try {
                assertEquals(1, processor.calls); assertEquals(1, tx.committedAssets().size)
                assertTrue(result.issuesAfter.none { it.code.value == "UNSUPPORTED_CONTAINER" })
                assertEquals(result.proposedChanges, result.changesApplied)
                assertTrue(operation.execution.any { it.stage == Stage.Remux && it.remuxed && !it.transcoded })
                assertTrue(operation.execution.none { it.transcoded })
                assertTrue(operation.preservation.records.filter { it.guarantee != Guarantee.ExactExtraction }.all { it.outcome == GuaranteeOutcome.Verified })
                val repaired = operation.output.assets.single().readableSource!!
                val entries = SamsungFixtures.directory(tx.committedAssets().values.single().toByteArray())
                assertContentEquals(bytes, entries.single { it.type == 0x0a30 }.payload)
                val keyField = core.inspect(ReadRequest(SourceSet.Single(repaired), context)).orThrow().metadata
                    .single { it.selector.endsWith("}MotionPhotoPresentationTimestampUs") }
                assertEquals(Value.Text(key.toString()), keyField.value)
                val second = DefaultLivePhotoCore().repair(request(repaired, MemoryOutputTransaction(context, "noop-$key-$audio"), dry = false)).orThrow()
                assertTrue(second.proposedChanges.isEmpty()); assertNull(second.operation)
                assertEquals(Bytes(original), input.readAt(0uL, original.size.toUInt()).orThrow())
            } finally { operation.output.assets.forEach { it.readableSource?.close() } }
        }
    }

    @Test fun noBackendExactExtractionAndNonemptyOutputRefuseBeforeExecution(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val input = source(carrier(mov(bytes)))
        val missing = DefaultLivePhotoCore().repair(request(input))
        assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(missing).error.code.value)
        val processor = backend(bytes)
        val core = DefaultLivePhotoCore(processor)
        val exact = core.repair(request(input, policy = MutationPolicy(requiredGuarantees = listOf(Guarantee.ExactExtraction))))
        assertEquals("PRESERVATION_REQUIREMENT_FAILED", assertIs<CoreResult.Failure>(exact).error.code.value)
        val tx = MemoryOutputTransaction(context, "not-empty")
        tx.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/jpeg")).orThrow()
        assertEquals("INVALID_ARGUMENT", assertIs<CoreResult.Failure>(core.repair(request(input, tx, dry = false))).error.code.value)
        assertEquals(0, processor.calls)
    }

    @Test fun legacyOrdinaryRecordsUnclassifiedMovieMetadataAndWrongCarrierAreNotGuessed(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val processor = backend(bytes)
        for (input in listOf(
            SamsungFixtures.photo(mov(bytes), xmp = true, ordinaryRecord = true).bytes,
            SamsungFixtures.photo(mov(bytes), xmp = true, legacy = true).bytes,
            SamsungFixtures.photo(mov(bytes), xmp = true, extraSegments = GoogleFixtures.segment(0xe3, byteArrayOf(1, 2, 3))).bytes,
            SamsungFixtures.photo(mov(bytes) + GoogleFixtures.box("uuid", ByteArray(16)), xmp = true).bytes,
            GoogleFixtures.jpeg() + mov(bytes))) {
            val tx = MemoryOutputTransaction(context, "refused")
            assertIs<CoreResult.Failure>(DefaultLivePhotoCore(processor).repair(request(source(input), tx, dry = false)))
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
        assertEquals(0, processor.calls)
    }

    @Test fun dishonestEncodingChangedSamplesConfigurationTimelineAndMetadataNeverPublish(): Unit = runImmediate {
        val fixture = GoogleFixtures.video()
        val bytes = fixture.bytes
        val variants = listOf(
            bytes.copyOf().also { it[fixture.sampleOffset.toInt() + 5] = 0x89.toByte() },
            bytes.copyOf().also { it[offsetOrNull(it, "avcC")!! + 7] = 31 },
            GoogleFixtures.video(composition = GoogleFixtures.fullBox("ctts", GoogleFixtures.u32(1u) + GoogleFixtures.u32(2u) + GoogleFixtures.u32(1u))).bytes,
            bytes.copyOf().also { it[offsetOrNull(it, "mvhd")!! + 11] = 1 })
        for (processor in variants.map { backend(it) } + backend(bytes, encoded = true)) {
            val tx = MemoryOutputTransaction(context, "dishonest")
            val failed = DefaultLivePhotoCore(processor).repair(request(source(carrier(mov(bytes))), tx, dry = false))
            assertEquals("POSTCONDITION_FAILED", assertIs<CoreResult.Failure>(failed).error.code.value)
            assertEquals(1, processor.calls); assertTrue(tx.query().orThrow().assetIds.isEmpty())
            assertTrue(tx.committedAssets().isEmpty())
        }
    }

    @Test fun sourceGenerationChangeAfterPrivateWritePreventsPublicStaging(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val original = carrier(mov(bytes))
        var generation = "first"
        val memory = source(original)
        val input = object : BinarySource by memory {
            override suspend fun identity(): CoreResult<SourceIdentity> = CoreResult.Success(memory.identity().orThrow().copy(generation = GenerationToken(generation)))
        }
        val processor = backend(bytes, after = { generation = "changed" })
        val tx = MemoryOutputTransaction(context, "source-change")
        val failed = DefaultLivePhotoCore(processor).repair(request(input, tx, dry = false))
        assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(failed).error.code.value)
        assertEquals(1, processor.calls); assertEquals(TransactionState.Open, tx.query().orThrow().state)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
    }

    @Test fun finalKeyTamperWriteFailureCancellationAndLimitsCannotCommit(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        for (fault in 0..3) {
            var cancelled = false; var commits = 0
            val current = context.copy(cancellation = Cancellation { cancelled })
            val base = MemoryOutputTransaction(current, "final-fault-$fault")
            val output = object : OutputTransaction by base {
                override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> {
                    if (fault == 1) return CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.WriteProtocol, "Synthetic final write failure"))
                    return base.create(spec)
                }
                override suspend fun prepare(): CoreResult<Unit> {
                    if (fault == 2) cancelled = true
                    return base.prepare()
                }
                override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> {
                    val staged = base.openStaged(id).orThrow()
                    if (fault != 0) return CoreResult.Success(staged)
                    val identity = staged.identity().orThrow()
                    val changed = BinaryReader(staged, current).readExactly(0uL, identity.size.toUInt()).orThrow().toByteArray()
                    staged.close()
                    val marker = "MotionPhotoPresentationTimestampUs=\"20\"".encodeToByteArray()
                    val offset = changed.indices.first { it + marker.size <= changed.size && changed.copyOfRange(it, it + marker.size).contentEquals(marker) }
                    changed[offset + marker.size - 2] = '1'.code.toByte() // 20 -> 21, still in range
                    return CoreResult.Success(MemoryBinarySource(Bytes(changed), identity.id))
                }
                override suspend fun commit(): CoreResult<Receipt> { commits++; return base.commit() }
            }
            val processor = backend(bytes)
            val req = request(source(carrier(mov(bytes), 20)), output, dry = false).copy(context =
                if (fault == 3) current.copy(limits = current.limits.copy(maxSpoolBytes = 16uL)) else current)
            val failed = DefaultLivePhotoCore(processor).repair(req)
            assertEquals(listOf("POSTCONDITION_FAILED", "IO_WRITE_FAILED", "CANCELLED", "RESOURCE_LIMIT_EXCEEDED")[fault],
                assertIs<CoreResult.Failure>(failed).error.code.value)
            assertEquals(0, commits); assertTrue(base.committedAssets().isEmpty())
            assertEquals(if (fault == 3) 0 else 1, processor.calls)
        }
    }

    private class CopyBackend(val bytes: ByteArray, val encoded: Boolean, val after: () -> Unit) : MediaBackend {
        var calls = 0
        override fun capabilities(): MediaCapabilities = MediaCapabilities(listOf("synthetic-repair-remux"), listOf(CapabilityEntry(Operation.Remux, Implementation.Experimental)))
        override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = attempt {
            calls++; job.validate().orThrow()
            assertEquals(VideoContainer.Mp4, job.remuxContainer)
            val reader = BinaryReader((job.inputs.single().input as SourceSet.Single).source, job.context)
            val before = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
            assertEquals(VideoContainer.Mov, before.container)
            val handle = job.destination.create(OutputAssetSpec(AssetRole.MotionVideo, mime = "video/mp4")).orThrow()
            BinaryWriter(handle.sink, job.context).writeAll(Bytes(bytes)).orThrow(); handle.sink.close().orThrow()
            after()
            val output = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("backend-result")), job.context)
            val facts = videoFacts(BmffVideoProbe(output).probe(ByteRange(0uL, bytes.size.toULong())).orThrow())
            BackendResult(listOf(StagedAsset(handle.id, AssetRole.MotionVideo, "video/mp4", bytes.size.toULong())), listOf(facts),
                listOf(ExecutionRecord(Stage.Remux, "synthetic-repair-remux", "Independent bounded fixture", encoded, true, false, videoFacts(before), facts)))
        }
        override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = error("Unexpected probe")
        override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = error("Unexpected trim")
        override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = error("Unexpected frame")
        override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = error("Unexpected transcode")
    }
}
