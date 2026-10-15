package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.memory.*
import livephoto.core.google.GoogleFixtures
import kotlin.test.*

class AppleCleanTest {
    private val context = Context(Limits(4_000_000uL, 4_000_000uL, maxMetadataBytes = 4_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun pair(video: ByteArray = AppleFixtures.movie(ordinaryKey = false)) = SourceSet.Pair(source(AppleFixtures.image(), "image"), source(video, "video"))
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value

    @Test fun cleanRetiresOnlyDedicatedMetadataAndPreservesVideoSamplesAndOffsets(): Unit = runImmediate {
        val movie = AppleFixtures.movie(ordinaryKey = false)
        val output = MemoryOutputTransaction(context, "apple-clean-safe")
        val result = value(core.split(SplitRequest(pair(movie), output = output, context = context)))
        assertEquals(2, result.output.assets.size)
        val image = result.output.assets.single { it.role == AssetRole.PrimaryImage }.readableSource!!
        assertEquals(Disposition.NonLive, value(core.detect(ReadRequest(SourceSet.Single(image), context))).disposition)
        val video = result.output.assets.single { it.role == AssetRole.MotionVideo }.readableSource!!
        val originalReader = BinaryReader(source(movie, "original"), context)
        val cleanReader = BinaryReader(video, context)
        val original = value(BmffVideoProbe(originalReader, allowTimedMetadata = true).probe(ByteRange(0uL, movie.size.toULong())))
        val clean = value(BmffVideoProbe(cleanReader, allowTimedMetadata = true).probe(ByteRange(0uL, value(video.size()))))
        assertEquals(original.tracks.filter { it.handler != "meta" }, clean.tracks)
        for (sample in clean.tracks.flatMap { it.samples }) {
            assertEquals(value(originalReader.readExactly(sample.range.offset, sample.range.length.toUInt())), value(cleanReader.readExactly(sample.range.offset, sample.range.length.toUInt())))
        }
        assertNull(value(AppleVideoReader.read(cleanReader, ParseBudget(context))))
        assertTrue(result.preservation.records.any { it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
    }

    @Test fun conversionPreservesKeyAndDoesNotTranscode(): Unit = runImmediate {
        val input = pair(AppleFixtures.movie(ordinaryKey = false, singleMdat = true, payload = 0, timescale = 25u, delay = 30u))
        val output = MemoryOutputTransaction(context, "apple-convert-google")
        val result = value(core.convert(ConvertRequest(input, ProtocolSelector(ProtocolIds.GoogleV2), output = output, context = context)))
        val converted = result.output.assets.single().readableSource!!
        val inspection = value(core.inspect(ReadRequest(SourceSet.Single(converted), context)))
        assertEquals(ProtocolIds.GoogleV2, inspection.detection.primaryProtocol?.protocol)
        assertEquals(Time(1_200_000, 1_000_000u), inspection.keyPhoto.position)
        assertTrue(result.execution.none { it.transcoded })
    }

    @Test fun ordinaryTimedMetadataCannotBeDiscardedAndStrictRejectsUnknownAssociations(): Unit = runImmediate {
        for (strict in listOf(false, true)) {
            val output = MemoryOutputTransaction(context, "apple-clean-gate-$strict")
            val request = SplitRequest(pair(AppleFixtures.movie(ordinaryKey = !strict)),
                policy = MutationPolicy(preservation = if (strict) PreservationPolicy.Strict else PreservationPolicy.BestEffortWithReport), output = output, context = context)
            val result = assertIs<CoreResult.Failure>(core.split(request))
            assertEquals(IssueCode(if (strict) "PRESERVATION_REQUIREMENT_FAILED" else "UNSAFE_METADATA_REWRITE"), result.error.code)
            assertTrue(output.committedAssets().isEmpty())
        }
    }

    @Test fun cleanPlanIsReadOnlyAndKeepsOriginalSnapshot(): Unit = runImmediate {
        val input = pair()
        val output = MemoryOutputTransaction(context, "apple-clean-plan")
        val inspected = value(core.inspect(ReadRequest(input, context)))
        val plan = value(core.plan(SplitRequest(input, output = output, context = context)))
        assertEquals(inspected.snapshot, plan.snapshot)
        assertEquals(TransactionState.Open, value(output.query()).state)
        assertTrue(value(output.query()).assetIds.isEmpty())
    }

    @Test fun ordinaryMakerNoteFieldsStayReadableButBlockCleanup(): Unit = runImmediate {
        val input = SourceSet.Pair(source(AppleFixtures.image(ordinaryNote = true), "image"), source(AppleFixtures.movie(ordinaryKey = false), "video"))
        assertEquals(Disposition.Live, value(core.detect(ReadRequest(input, context))).disposition)
        val output = MemoryOutputTransaction(context, "apple-ordinary-note")
        assertEquals(IssueCode("UNSAFE_METADATA_REWRITE"), assertIs<CoreResult.Failure>(core.split(SplitRequest(input, output = output, context = context))).error.code)
        assertTrue(value(output.query()).assetIds.isEmpty())
    }

    @Test fun conversionRechecksOriginalInputsAndDoesNotPublishIntermediateCleanAssets(): Unit = runImmediate {
        val originalVideo = TestSource(AppleFixtures.movie(ordinaryKey = false, singleMdat = true))
        val input = SourceSet.Pair(source(AppleFixtures.image(), "image"), originalVideo)
        val transaction = MemoryOutputTransaction(context, "apple-convert-source-change")
        var creates = 0
        val output = object : OutputTransaction by transaction {
            override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> {
                creates++
                return transaction.create(spec)
            }
            override suspend fun prepare(): CoreResult<Unit> {
                originalVideo.currentIdentity = originalVideo.currentIdentity.copy(generation = GenerationToken("changed"))
                return transaction.prepare()
            }
        }
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(core.convert(ConvertRequest(input, ProtocolSelector(ProtocolIds.GoogleV2), output = output, context = context))).error.code)
        assertEquals(1, creates)
        assertEquals(TransactionState.Aborted, value(transaction.query()).state)
        assertTrue(transaction.committedAssets().isEmpty())
        assertFalse(originalVideo.closed)
    }

    @Test fun unknownNestedTrackMetadataCannotBeSilentlyRetired(): Unit = runImmediate {
        val input = pair(AppleFixtures.movie(ordinaryKey = false, extraMetadataMedia = GoogleFixtures.box("udta", GoogleFixtures.box("name", "ordinary-calibration".encodeToByteArray()))))
        assertEquals(Disposition.Live, value(core.detect(ReadRequest(input, context))).disposition)
        val output = MemoryOutputTransaction(context, "apple-nested-metadata")
        assertEquals(IssueCode("UNSAFE_METADATA_REWRITE"), assertIs<CoreResult.Failure>(core.split(SplitRequest(input, output = output, context = context))).error.code)
        assertTrue(value(output.query()).assetIds.isEmpty())
    }
}
