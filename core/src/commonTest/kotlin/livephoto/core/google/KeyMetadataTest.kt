package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

class KeyMetadataTest {
    private val context = Context(Limits(4_000_000uL, 4_000_000uL, maxMetadataBytes = 4_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private fun source(bytes: ByteArray, id: String = "source") = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value

    @Test fun setChangesOnlyKeyMetadataAndKeepsImageCodingAndEntireVideo(): Unit = runImmediate {
        for (bytes in listOf(GoogleFixtures.v1Photo(), GoogleFixtures.v2Photo())) {
            val input = SourceSet.Single(source(bytes))
            val before = value(SourceSession.open(input, context, ParseBudget(context)))
            val output = MemoryOutputTransaction(context, "key-${before.bindings.single().protocol.value}")
            val result = value(core.setKeyPhotoPosition(SetKeyRequest(input, CoverPosition.FrameIndex(1uL), output = output, context = context)))
            val after = value(SourceSession.open(SourceSet.Single(result.output.assets.single().readableSource!!), context, ParseBudget(context)))
            assertEquals(Time(40_000, 1_000_000u), after.inspection.keyPhoto.position)
            assertEquals(codingDigest(before), codingDigest(after))
            assertEquals(ordinaryDigest(before), ordinaryDigest(after))
            assertEquals(value(sha256Range(before.reader, before.bindings.single().video!!)), value(sha256Range(after.reader, after.bindings.single().video!!)))
            assertTrue(result.execution.none { it.transcoded })
            assertEquals(1, result.preservation.changes.size)
        }
    }

    @Test fun zeroIsValidAndNearestTieSelectsEarlierPts(): Unit = runImmediate {
        for (position in listOf(CoverPosition.FrameIndex(0uL), CoverPosition.Timestamp(Time(20, 1000u), Selection.Nearest, Time(20, 1000u)))) {
            val output = MemoryOutputTransaction(context, "key-zero-${position::class.simpleName}")
            val result = value(core.setKeyPhotoPosition(SetKeyRequest(SourceSet.Single(source(GoogleFixtures.v1Photo(timestamp = "40000"))), position, output = output, context = context)))
            assertEquals(Time(0, 1_000_000u), result.keyPhoto?.position)
        }
    }

    @Test fun bothGoogleBindingsAreSynchronized(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val xml = GoogleFixtures.v2Xml(video.size).replace(" camera:MotionPhoto='1'", " camera:MicroVideo='1' camera:MicroVideoVersion='1' camera:MicroVideoOffset='${video.size}' camera:MicroVideoPresentationTimestampUs='0' camera:MotionPhoto='1'")
        val input = SourceSet.Single(source(GoogleFixtures.jpeg(GoogleFixtures.xmpSegment(xml)) + video))
        val output = MemoryOutputTransaction(context, "key-dual")
        val result = value(core.setKeyPhotoPosition(SetKeyRequest(input, CoverPosition.FrameIndex(1uL), output = output, context = context)))
        val session = value(SourceSession.open(SourceSet.Single(result.output.assets.single().readableSource!!), context, ParseBudget(context)))
        assertEquals(2, session.bindings.size)
        assertTrue(session.bindings.all { it.key.position == Time(40_000, 1_000_000u) })
    }

    @Test fun invalidIndexAndUnrelatedSourceErrorsNeverStage(): Unit = runImmediate {
        for ((bytes, position) in listOf(GoogleFixtures.v1Photo() to CoverPosition.FrameIndex(2uL), GoogleFixtures.v1Photo(length = "1") to CoverPosition.FrameIndex(0uL))) {
            val output = MemoryOutputTransaction(context, "key-rejected-${position.index}")
            assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(SetKeyRequest(SourceSet.Single(source(bytes)), position, output = output, context = context)))
            assertTrue(value(output.query()).assetIds.isEmpty())
        }
    }

    @Test fun planDoesNotWriteAndOriginalIdentityIsCheckedBeforeCommit(): Unit = runImmediate {
        val original = TestSource(GoogleFixtures.v2Photo())
        val input = SourceSet.Single(original)
        val transaction = MemoryOutputTransaction(context, "key-changed")
        val output = object : OutputTransaction by transaction {
            override suspend fun prepare(): CoreResult<Unit> {
                original.currentIdentity = original.currentIdentity.copy(generation = GenerationToken("changed"))
                return transaction.prepare()
            }
        }
        val request = SetKeyRequest(input, CoverPosition.FrameIndex(1uL), output = output, context = context)
        assertEquals(Operation.SetKey, value(core.plan(request)).capabilities.operations.single().operation)
        assertTrue(value(output.query()).assetIds.isEmpty())
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(request)).error.code)
        assertEquals(TransactionState.Aborted, value(output.query()).state)
        assertTrue(transaction.committedAssets().isEmpty())
        assertFalse(original.closed)
    }

    @Test fun frameIndexUsesPresentationOrderAndVfrTimingNotDecodeOrderOrAverageFps(): Unit = runImmediate {
        val composition = GoogleFixtures.box("ctts", byteArrayOf(1, 0, 0, 0) + GoogleFixtures.u32(2u) + GoogleFixtures.u32(1u) + GoogleFixtures.u32(40u) + GoogleFixtures.u32(1u) + GoogleFixtures.u32(UInt.MAX_VALUE - 39u))
        val reordered = GoogleFixtures.video(composition = composition).bytes
        val vfr = GoogleFixtures.video(sampleDurations = 10u to 70u).bytes
        for ((video, index, expected) in listOf(Triple(reordered, 0uL, 0L), Triple(vfr, 1uL, 10_000L))) {
            val output = MemoryOutputTransaction(context, "key-presentation-$index")
            val result = value(core.setKeyPhotoPosition(SetKeyRequest(SourceSet.Single(source(GoogleFixtures.v1Photo(video))), CoverPosition.FrameIndex(index), output = output, context = context)))
            assertEquals(Time(expected, 1_000_000u), result.keyPhoto?.position)
        }
    }

    @Test fun unrepresentableSelectionAndUnprovenStrictMetadataDoNotPublish(): Unit = runImmediate {
        val exactOutput = MemoryOutputTransaction(context, "key-exact-unrepresentable")
        val video = GoogleFixtures.video().bytes.copyOf()
        for (type in listOf("mvhd", "mdhd")) {
            val offset = (4 until video.size - 4).first { video.copyOfRange(it, it + 4).contentEquals(type.encodeToByteArray()) } + 4
            GoogleFixtures.u32(30_000u).copyInto(video, offset + 12)
        }
        // Source selection is rational; only the actual protocol field still requires integer microseconds.
        assertEquals(IssueCode("VALUE_NOT_REPRESENTABLE"), assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(SetKeyRequest(SourceSet.Single(source(GoogleFixtures.v1Photo(video))), CoverPosition.FrameIndex(1uL), output = exactOutput, context = context))).error.code)
        assertTrue(value(exactOutput.query()).assetIds.isEmpty())
        val plain = GoogleFixtures.v1Photo()
        val emptyExif = "Exif\u0000\u0000".encodeToByteArray() + byteArrayOf(77, 77, 0, 42) + GoogleFixtures.u32(8u) + ByteArray(6)
        val withExif = plain.copyOfRange(0, 2) + GoogleFixtures.segment(0xe1, emptyExif) + plain.copyOfRange(2, plain.size)
        val strictOutput = MemoryOutputTransaction(context, "key-strict")
        assertEquals(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(SetKeyRequest(SourceSet.Single(source(withExif)), CoverPosition.FrameIndex(1uL), policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = strictOutput, context = context))).error.code)
        assertEquals(TransactionState.Aborted, value(strictOutput.query()).state)
        assertTrue(strictOutput.committedAssets().isEmpty())
    }
    @Test fun subMicrosecondRequestCanSelectAnExactlyRepresentableProtocolPosition(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "key-rational-request")
        val position = CoverPosition.Timestamp(Time(1, 30_000u), Selection.Nearest, Time(1, 1000u))
        val result = core.setKeyPhotoPosition(SetKeyRequest(SourceSet.Single(source(GoogleFixtures.v1Photo())), position, output = output, context = context)).orThrow()
        assertEquals(Time(0, 1_000_000u), result.keyPhoto?.position)
        result.output.assets.forEach { it.readableSource?.close() }
    }
}
