package livephoto.core

import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.huawei.HuaweiFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import livephoto.core.oplus.OplusFixtures
import livephoto.core.samsung.SamsungFixtures
import livephoto.core.vivo.VivoFixtures
import kotlin.test.*

/** Deterministic synthetic L1/L3 properties; not a gallery/device compatibility certificate. */
class TransformationConformanceTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private suspend fun session(source: BinarySource): SourceSession = SourceSession.open(SourceSet.Single(source), context, ParseBudget(context)).orThrow()
    @Test fun modernSourcesConvertToBothGoogleTargetsWithoutConflictingSourceBindings(): Unit = runImmediate {
        val fixtures = listOf(GoogleFixtures.v1Photo(timestamp = "40000"), GoogleFixtures.v2Photo(timestamp = "40000"),
            OplusFixtures.photo(googleTimestamp = "40000", vendorTimestamp = "40000"), VivoFixtures.photo(timestamp = "40000").bytes,
            SamsungFixtures.photo().bytes, HuaweiFixtures.photo().bytes)
        for ((index, bytes) in fixtures.withIndex()) for (target in listOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2)) {
            val original = source(bytes, "matrix-source-$index-$target"); val before = session(original)
            val tx = MemoryOutputTransaction(context, "matrix-target-$index-$target")
            val result = core.convert(ConvertRequest(SourceSet.Single(original), ProtocolSelector(target), sameTarget = SameTargetPolicy.Normalize, output = tx, context = context)).orThrow()
            try {
                val after = session(result.output.assets.single().readableSource!!)
                assertEquals(target, after.inspection.detection.primaryProtocol?.protocol)
                assertEquals(listOf(target), after.bindings.map { it.protocol })
                assertEquals(Bytes(GoogleFixtures.video().bytes), after.reader.readExactly(after.bindings.single().video!!.offset, GoogleFixtures.video().bytes.size.toUInt()).orThrow())
                assertEquals(codingDigest(before), codingDigest(after))
                if (before.inspection.keyPhoto.position != null && before.inspection.keyPhoto.source == KeySource.ProtocolField)
                    assertEquals(0, before.inspection.keyPhoto.position.compareTo(after.inspection.keyPhoto.position!!))
                assertTrue(result.execution.none { it.transcoded || it.stage in setOf(Stage.DecodeFrame, Stage.EncodeImage) })
                assertEquals(Bytes(bytes), original.readAt(0uL, bytes.size.toUInt()).orThrow())
                val validation = core.validate(ValidationRequest(SourceSet.Single(after.reader.source), layers = listOf(Layer.Structure, Layer.Protocol), context = context)).orThrow()
                assertNotEquals(Verdict.Invalid, validation.verdict)
            } finally { result.output.assets.forEach { it.readableSource?.close() }; original.close() }
        }
    }
    @Test fun generatedGoogleCyclesPreserveMediaKeyAndOrdinaryPayloadWithoutRequiringCarrierIdentity(): Unit = runImmediate {
        var seed = 0x90e1af33u
        repeat(24) { index ->
            seed = seed * 1_664_525u + 1_013_904_223u
            val payload = ByteArray(5 + index % 7) { position -> ((seed shr (position % 4 * 8)) and 0xffu).toByte() }
            val segment = GoogleFixtures.segment(0xee, payload)
            val image = source(GoogleFixtures.jpeg(segment).also { it[it.size - 3] = (1 + index).toByte() }, "cycle-image-$index")
            val video = source(GoogleFixtures.video().bytes, "cycle-video-$index")
            val original = session(image); val key = CoverPosition.FrameIndex((index % 2).toULong())
            val first = core.create(CreateRequest(image, video, ProtocolSelector(ProtocolIds.GoogleV1), edits = EditSpec(keyPosition = key), output = MemoryOutputTransaction(context, "cycle-first-$index"), context = context)).orThrow()
            try {
                val second = core.convert(ConvertRequest(SourceSet.Single(first.output.assets.single().readableSource!!), ProtocolSelector(ProtocolIds.GoogleV2), output = MemoryOutputTransaction(context, "cycle-second-$index"), context = context)).orThrow()
                try {
                    val third = core.convert(ConvertRequest(SourceSet.Single(second.output.assets.single().readableSource!!), ProtocolSelector(ProtocolIds.GoogleV1), output = MemoryOutputTransaction(context, "cycle-third-$index"), context = context)).orThrow()
                    try {
                        val after = session(third.output.assets.single().readableSource!!)
                        assertEquals(codingDigest(original), codingDigest(after)); assertEquals(ordinaryDigest(original), ordinaryDigest(after))
                        assertEquals(0, third.keyPhoto!!.position!!.compareTo(Time((index % 2 * 40).toLong(), 1000u)))
                        val embedded = after.bindings.single().video!!
                        assertEquals(Bytes(GoogleFixtures.video().bytes), after.reader.readExactly(embedded.offset, embedded.length.toUInt()).orThrow())
                        assertTrue(third.execution.none { it.transcoded }); assertEquals(ProtocolIds.GoogleV1, after.inspection.detection.primaryProtocol?.protocol)
                    } finally { third.output.assets.forEach { it.readableSource?.close() } }
                } finally { second.output.assets.forEach { it.readableSource?.close() } }
            } finally { first.output.assets.forEach { it.readableSource?.close() }; image.close(); video.close() }
        }
    }
    @Test fun cleanIsIdempotentAcrossEveryFiniteModernSourceProfile(): Unit = runImmediate {
        val fixtures = listOf(GoogleFixtures.v1Photo(), GoogleFixtures.v2Photo(), OplusFixtures.photo(), VivoFixtures.photo().bytes, SamsungFixtures.photo().bytes, HuaweiFixtures.photo().bytes)
        for ((index, bytes) in fixtures.withIndex()) {
            val input = source(bytes, "clean-matrix-input-$index")
            val first = core.split(SplitRequest(SourceSet.Single(input), output = MemoryOutputTransaction(context, "clean-matrix-first-$index"), context = context)).orThrow()
            try {
                val image = first.output.assets.single { it.role == AssetRole.PrimaryImage }.readableSource!!
                val reader = BinaryReader(image, context); val size = reader.identity().orThrow().size
                val expected = reader.readExactly(0uL, size.toUInt()).orThrow()
                val second = core.split(SplitRequest(SourceSet.Single(image), output = MemoryOutputTransaction(context, "clean-matrix-second-$index"), context = context)).orThrow()
                try {
                    assertEquals(1, second.output.assets.size)
                    val actual = second.output.assets.single().readableSource!!
                    assertEquals(expected, actual.readAt(0uL, size.toUInt()).orThrow())
                    assertEquals(Disposition.NonLive, core.detect(ReadRequest(SourceSet.Single(actual), context)).orThrow().disposition)
                } finally { second.output.assets.forEach { it.readableSource?.close() } }
            } finally { first.output.assets.forEach { it.readableSource?.close() }; input.close() }
        }
    }
    @Test fun malformedMediaPreflightFailureNeverStartsAnOutputTransaction(): Unit = runImmediate {
        val invalid = (0 until 32).map { GoogleFixtures.video().bytes.copyOf(it) }
        for ((index, bytes) in invalid.withIndex()) {
            val tx = MemoryOutputTransaction(context, "invalid-create-$index")
            val result = core.create(CreateRequest(source(GoogleFixtures.jpeg(), "invalid-create-image-$index"), source(bytes, "invalid-create-video-$index"), ProtocolSelector(ProtocolIds.GoogleV2), output = tx, context = context))
            assertIs<CoreResult.Failure>(result)
            val state = tx.query().orThrow()
            assertEquals(TransactionState.Open, state.state); assertTrue(state.assetIds.isEmpty()); assertTrue(tx.committedAssets().isEmpty())
        }
    }
    @Test fun boundedProtocolMutationsReturnStructuredResultsWithoutChangingBorrowedInput(): Unit = runImmediate {
        val fixtures = listOf(GoogleFixtures.v1Photo(), GoogleFixtures.v2Photo(), OplusFixtures.photo(), VivoFixtures.photo().bytes, SamsungFixtures.photo().bytes, HuaweiFixtures.photo().bytes)
        for ((fixture, bytes) in fixtures.withIndex()) for ((mutation, changed) in boundedMutations(bytes).withIndex()) {
            val input = source(changed, "mutation-$fixture-$mutation")
            // Any uncaught parser failure fails this test; a Core Failure remains a legitimate structured result.
            core.detect(ReadRequest(SourceSet.Single(input), context))
            core.inspect(ReadRequest(SourceSet.Single(input), context))
            core.validate(ValidationRequest(SourceSet.Single(input), context = context))
            assertEquals(Bytes(changed), input.readAt(0uL, changed.size.toUInt()).orThrow())
            input.close()
        }
    }
}
