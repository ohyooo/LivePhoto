package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.heif.*
import livephoto.core.memory.*
import kotlin.test.*

class GoogleHeicKeyTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("heic"))
    private fun source(bytes: Bytes, id: String) = MemoryBinarySource(bytes, SourceId(id))
    private suspend fun photo(video: ByteArray = GoogleFixtures.video(aac = true).bytes, idat: Boolean = false, extended: Boolean = false): Bytes {
        val tx = MemoryOutputTransaction(context, "heic-key-fixture-$idat-$extended")
        core.create(CreateRequest(source(Bytes(HeifFixtures.plain(idat = idat, extended = extended, multiple = true)), "key-image"),
            source(Bytes(video), "key-video"), target, edits = EditSpec(keyPosition = CoverPosition.FrameIndex(0uL)), output = tx, context = context)).orThrow()
        return tx.committedAssets().values.single()
    }
    private suspend fun primary(input: SourceSet): Bytes {
        val split = MemoryOutputTransaction(context, "key-clean-primary")
        val result = core.split(SplitRequest(input, output = split, context = context)).orThrow()
        return split.committedAssets().getValue(result.output.assets.single { it.role == AssetRole.PrimaryImage }.id)
    }
    @Test fun setMetadataPreservesCleanPrimaryAndExactVideoAcrossLayoutsWithoutEncoding(): Unit = runImmediate {
        for (idat in listOf(false, true)) for (extended in listOf(false, true)) for (index in listOf(0uL, 1uL)) {
            val bytes = photo(idat = idat, extended = extended)
            val input = SourceSet.Single(source(bytes, "heic-key-input"))
            val before = primary(input)
            val tx = MemoryOutputTransaction(context, "heic-key-$idat-$extended-$index")
            val request = SetKeyRequest(input, CoverPosition.FrameIndex(index), policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = tx, context = context)
            val plan = core.plan(request).orThrow()
            assertEquals(listOf(input.source.identity().orThrow()), plan.snapshot.identities)
            assertEquals(Operation.SetKey, plan.capabilities.operations.single().operation)
            assertEquals(listOf(Operation.SetKey), plan.steps.first().required)
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
            val result = core.setKeyPhotoPosition(request).orThrow()
            assertEquals(Time(index.toLong() * 40_000, 1_000_000u), result.keyPhoto?.position)
            val edited = SourceSet.Single(source(tx.committedAssets().values.single(), "heic-key-edited"))
            assertEquals(before, primary(edited))
            val raw = MemoryOutputTransaction(context, "heic-key-raw")
            core.extract(ExtractRequest(edited, emptyList(), output = raw, context = context)).orThrow()
            assertEquals(Bytes(GoogleFixtures.video(aac = true).bytes), raw.committedAssets().values.single())
            assertTrue(result.execution.none { it.transcoded || it.remuxed || it.stage in setOf(Stage.DecodeFrame, Stage.EncodeImage) })
            assertTrue(result.preservation.records.none { it.outcome in setOf(GuaranteeOutcome.Unknown, GuaranteeOutcome.Changed) })
            assertTrue(result.preservation.changes.any { it.selector.endsWith("MotionPhotoPresentationTimestampUs") })
            assertEquals(bytes, BinaryReader(input.source, context).readExactly(0uL, bytes.size.toUInt()).orThrow())
        }
    }
    @Test fun selectionUsesPresentationOrderVfrAndZeroWithNearestTies(): Unit = runImmediate {
        val composition = GoogleFixtures.box("ctts", byteArrayOf(1, 0, 0, 0) + GoogleFixtures.u32(2u) + GoogleFixtures.u32(1u) + GoogleFixtures.u32(40u) + GoogleFixtures.u32(1u) + GoogleFixtures.u32(UInt.MAX_VALUE - 39u))
        for ((video, position, expected) in listOf(
            Triple(GoogleFixtures.video(composition = composition).bytes, CoverPosition.FrameIndex(0uL), 0L),
            Triple(GoogleFixtures.video(sampleDurations = 10u to 70u).bytes, CoverPosition.FrameIndex(1uL), 10_000L),
            Triple(GoogleFixtures.video().bytes, CoverPosition.Timestamp(Time(20, 1000u), Selection.Nearest, Time(20, 1000u)), 0L))) {
            val tx = MemoryOutputTransaction(context, "heic-key-selection-$expected")
            val result = core.setKeyPhotoPosition(SetKeyRequest(SourceSet.Single(source(photo(video), "heic-key-timing")), position, output = tx, context = context)).orThrow()
            assertEquals(Time(expected, 1_000_000u), result.keyPhoto?.position)
        }
    }
    @Test fun invalidSelectionPlainImageAndUnrequestedMediaDamageNeverStage(): Unit = runImmediate {
        val bytes = photo()
        val privateBytes = bytes.toByteArray().also { data ->
            val name = "MotionPhotoVersion".encodeToByteArray()
            val at = (0..data.size - name.size).first { data.copyOfRange(it, it + name.size).contentEquals(name) }
            "MotionPhotoPrivate".encodeToByteArray().copyInto(data, at)
        }
        for ((index, pair) in listOf(bytes to CoverPosition.FrameIndex(2uL),
            Bytes(HeifFixtures.plain()) to CoverPosition.FrameIndex(0uL), Bytes(privateBytes) to CoverPosition.FrameIndex(0uL)).withIndex()) {
            val tx = MemoryOutputTransaction(context, "heic-key-invalid-$index")
            val request = SetKeyRequest(SourceSet.Single(source(pair.first, "heic-key-invalid-input")), pair.second, output = tx, context = context)
            assertIs<CoreResult.Failure>(core.plan(request))
            assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(request))
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
    }
    @Test fun originalIdentityChangeDuringPrepareAbortsAndDoesNotCloseBorrowedInput(): Unit = runImmediate {
        val bytes = photo()
        val memory = source(bytes, "heic-key-changing-source")
        var generation = GenerationToken("before")
        var closed = false
        val changing = object : BinarySource by memory {
            override suspend fun identity(): CoreResult<SourceIdentity> = CoreResult.Success(memory.identity().orThrow().copy(generation = generation))
            override suspend fun close() { closed = true }
        }
        val base = MemoryOutputTransaction(context, "heic-key-source-changed")
        val output = object : OutputTransaction by base {
            override suspend fun prepare(): CoreResult<Unit> { generation = GenerationToken("after"); return base.prepare() }
        }
        val result = core.setKeyPhotoPosition(SetKeyRequest(SourceSet.Single(changing), CoverPosition.FrameIndex(1uL), output = output, context = context))
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(result).error.code)
        assertEquals(TransactionState.Aborted, base.query().orThrow().state)
        assertTrue(base.committedAssets().isEmpty())
        assertFalse(closed)
    }
}
