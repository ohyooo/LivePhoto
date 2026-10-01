package livephoto.core.apple

import livephoto.core.*
import livephoto.core.memory.*
import livephoto.core.binary.TestSource
import livephoto.core.legacy.LegacyFixtures
import kotlin.test.*

class ApplePairTest {
    private val context = Context(Limits(4_000_000uL, 4_000_000uL, maxMetadataBytes = 4_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun pair(image: ByteArray = AppleFixtures.image(), video: ByteArray = AppleFixtures.movie()) = SourceSet.Pair(source(image, "image"), source(video, "video"))
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value

    @Test fun pairUsesExactIdentifiersAndSamplePresentationTimeNotMarkerPayload(): Unit = runImmediate {
        for (payload in listOf<Byte>(-1, 0, 1)) {
            val result = value(core.inspect(ReadRequest(pair(video = AppleFixtures.movie(payload = payload)), context)))
            assertEquals(Disposition.Live, result.detection.disposition)
            assertEquals(ProtocolIds.Apple, result.detection.primaryProtocol?.protocol)
            assertEquals(true, result.pairing?.matches)
            assertEquals(Time(40, 1000u), result.keyPhoto.position)
            assertEquals(KeySource.TimedMetadataSample, result.keyPhoto.source)
            assertEquals(listOf(RelationshipKind.PairedWith), result.layout.relationships.map { it.kind })
        }
    }

    @Test fun rawExtractionAndSplitPublishBothCompleteCarriers(): Unit = runImmediate {
        val image = AppleFixtures.image(); val video = AppleFixtures.movie()
        for (split in listOf(false, true)) {
            val output = MemoryOutputTransaction(context, "apple-raw-$split")
            val result = if (split) value(core.split(SplitRequest(pair(image, video), SplitMode.Raw, output = output, context = context)))
                else value(core.extract(ExtractRequest(pair(image, video), emptyList(), output = output, context = context)))
            assertEquals(setOf(AssetRole.PrimaryImage, AssetRole.MotionVideo), result.output.assets.map { it.role }.toSet())
            assertEquals(setOf(Bytes(image), Bytes(video)), output.committedAssets().values.toSet())
        }
    }

    @Test fun missingMarkerDoesNotTurnAKeyDeclarationIntoTimeZero(): Unit = runImmediate {
        val result = value(core.inspect(ReadRequest(pair(video = AppleFixtures.movie(marker = false)), context)))
        assertNull(result.keyPhoto.position)
        assertEquals(KeySource.Unknown, result.keyPhoto.source)
    }

    @Test fun duplicateMarkersAreNotArbitrarilySelected(): Unit = runImmediate {
        assertEquals(IssueCode("CONFLICTING_METADATA"), assertIs<CoreResult.Failure>(core.inspect(ReadRequest(pair(video = AppleFixtures.movie(duplicateMarker = true)), context))).error.code)
    }

    @Test fun identifierMismatchAndAmbiguousCandidatesFail(): Unit = runImmediate {
        val other = "11112233-4455-6677-8899-aabbccddeeff"
        assertEquals(IssueCode("INVALID_PAIR_IDENTIFIER"), assertIs<CoreResult.Failure>(core.inspect(ReadRequest(pair(video = AppleFixtures.movie(other)), context))).error.code)
        val candidates = SourceSet.Candidates(listOf(source(AppleFixtures.image(), "i1"), source(AppleFixtures.image(), "i2"), source(AppleFixtures.movie(), "v")))
        assertEquals(IssueCode("AMBIGUOUS_PAIR"), assertIs<CoreResult.Failure>(core.inspect(ReadRequest(candidates, context))).error.code)
    }

    @Test fun tagSeventeenDecimalIsNotHexSeventeen(): Unit = runImmediate {
        assertEquals(IssueCode("PAIR_ASSET_MISSING"), assertIs<CoreResult.Failure>(core.inspect(ReadRequest(pair(image = AppleFixtures.image(tag = 0x17)), context))).error.code)
    }

    @Test fun cleanFailsBeforeStagingWhileSameTargetKeepsBothAssets(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "apple-clean")
        assertEquals(IssueCode("CAPABILITY_UNSUPPORTED"), assertIs<CoreResult.Failure>(core.split(SplitRequest(pair(), output = output, context = context))).error.code)
        assertTrue(value(output.query()).assetIds.isEmpty())
        val preserved = MemoryOutputTransaction(context, "apple-preserved")
        val result = value(core.convert(ConvertRequest(pair(), ProtocolSelector(ProtocolIds.Apple), output = preserved, context = context)))
        assertEquals(2, result.output.assets.size)
    }

    @Test fun singleIdentifiedCarrierIsOnlyACandidate(): Unit = runImmediate {
        for ((bytes, id) in listOf(AppleFixtures.image() to "image", AppleFixtures.movie() to "video")) {
            val result = value(core.inspect(ReadRequest(SourceSet.Single(source(bytes, id)), context)))
            assertEquals(Disposition.Candidate, result.detection.disposition)
            assertEquals(false, result.pairing?.matches)
            assertTrue(result.issues.any { it.code == IssueCode("PAIR_ASSET_MISSING") })
        }
    }

    @Test fun createPlannedDoesNotDisablePairReading(): Unit = runImmediate {
        val capabilities = core.getProtocolCapabilities(ProtocolSelector(ProtocolIds.Apple)).operations
        assertEquals(Implementation.Planned, capabilities.single { it.operation == Operation.Create }.implementation)
        assertEquals(Implementation.Experimental, capabilities.single { it.operation == Operation.Inspect }.implementation)
        assertEquals(Disposition.Live, value(core.detect(ReadRequest(pair(), context))).disposition)
    }

    @Test fun secondAssetFailureAbortsTheWholePair(): Unit = runImmediate {
        val transaction = MemoryOutputTransaction(context, "apple-second-failure")
        var created = 0
        val output = object : OutputTransaction by transaction {
            override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> {
                created++
                if (created == 2) return CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.Publish, "Synthetic second asset failure"))
                return transaction.create(spec)
            }
        }
        assertIs<CoreResult.Failure>(core.extract(ExtractRequest(pair(), emptyList(), output = output, context = context)))
        assertEquals(2, created)
        assertEquals(TransactionState.Aborted, value(transaction.query()).state)
        assertTrue(transaction.committedAssets().isEmpty())
    }

    @Test fun zeroPayloadAtOnePointTwoSecondsUsesTimescaleAndEditThenCts(): Unit = runImmediate {
        val plain = value(core.getKeyPhotoPosition(ReadRequest(pair(video = AppleFixtures.movie(payload = 0, timescale = 25u, delay = 30u)), context)))
        assertEquals(Time(30, 25u), plain.position)
        assertEquals(Value.Number("0"), plain.rawFields.single().rawValue)
        val composed = value(core.getKeyPhotoPosition(ReadRequest(pair(video = AppleFixtures.movie(payload = 0, timescale = 25u, delay = 30u, composition = 10u)), context)))
        assertEquals(Time(40, 25u), composed.position)
    }

    @Test fun candidateOrderCannotChooseBetweenAppleAndVivoPairs(): Unit = runImmediate {
        val legacy = LegacyFixtures.pair()
        val sources = listOf(source(AppleFixtures.image(), "apple-image"), source(AppleFixtures.movie(), "apple-video"), source(legacy.image, "vivo-image"), source(legacy.video, "vivo-video"))
        for (ordered in listOf(sources, sources.reversed())) {
            assertEquals(IssueCode("AMBIGUOUS_PAIR"), assertIs<CoreResult.Failure>(core.inspect(ReadRequest(SourceSet.Candidates(ordered), context))).error.code)
        }
        val onlyVivo = SourceSet.Candidates(sources.drop(1).filterNot { it === sources[1] } + sources[0])
        assertEquals(ProtocolIds.VivoLegacy, value(core.detect(ReadRequest(onlyVivo, context))).primaryProtocol?.protocol)
    }

    @Test fun allCandidateIdentitiesAreGuardedWithoutPublishingUnselectedAssets(): Unit = runImmediate {
        val unmatched = TestSource(AppleFixtures.image("11112233-4455-6677-8899-aabbccddeeff"))
        val input = SourceSet.Candidates(listOf(source(AppleFixtures.image(), "i"), source(AppleFixtures.movie(), "v"), unmatched))
        val inspection = value(core.inspect(ReadRequest(input, context)))
        assertEquals(3, inspection.snapshot.identities.size)
        val copied = MemoryOutputTransaction(context, "apple-selected-candidates")
        value(core.convert(ConvertRequest(input, ProtocolSelector(ProtocolIds.Apple), output = copied, context = context)))
        assertEquals(2, copied.committedAssets().size)
        val transaction = MemoryOutputTransaction(context, "apple-candidate-changed")
        val output = object : OutputTransaction by transaction {
            override suspend fun prepare(): CoreResult<Unit> {
                unmatched.currentIdentity = unmatched.currentIdentity.copy(generation = GenerationToken("changed"))
                return transaction.prepare()
            }
        }
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(core.extract(ExtractRequest(input, emptyList(), output = output, context = context))).error.code)
        assertTrue(transaction.committedAssets().isEmpty())
        assertEquals(TransactionState.Aborted, value(transaction.query()).state)
        assertFalse(unmatched.closed)
    }
}
