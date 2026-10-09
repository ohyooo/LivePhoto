package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.memory.*
import kotlin.test.*

class AppleOrdinaryMovieTextCleanTest {
    private val context = Context(Limits(16_000_000uL, 16_000_000uL, maxMetadataBytes = 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private suspend fun pair(metadata: ByteArray = OrdinaryMovieTextFixtures.envelope()): SourceSet.Pair {
        val result = core.create(CreateRequest(
            MemoryBinarySource(Bytes(AppleOrdinaryExifTest().image(Endian.Little)), SourceId("text-clean-image")),
            MemoryBinarySource(Bytes(OrdinaryMovieTextFixtures.movie(metadata = metadata)), SourceId("text-clean-movie")),
            ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4")), policy = MutationPolicy(preservation = PreservationPolicy.Strict),
            output = MemoryOutputTransaction(context, "text-clean-create"), context = context)).orThrow()
        return SourceSet.Pair(result.output.assets[0].readableSource!!, result.output.assets[1].readableSource!!)
    }
    private suspend fun hash(source: BinarySource) = sha256Range(BinaryReader(source, context), ByteRange(0uL, source.size().orThrow())).orThrow()

    @Test fun cleanPreservesOrdinaryTextAndSamplesAndRepeatedNeutralCleanIsFileExact(): Unit = runImmediate {
        val input = pair(); val beforeImage = hash(input.image); val beforeMovie = hash(input.video)
        val beforeText = AppleOrdinaryMovieTextTest().envelope(input.video)
        val split = core.split(SplitRequest(input, output = MemoryOutputTransaction(context, "text-clean-split"), context = context)).orThrow()
        val movie = split.output.assets.single { it.role == AssetRole.MotionVideo }.readableSource!!
        assertEquals(beforeText, AppleOrdinaryMovieTextTest().envelope(movie))
        val detected = core.detect(ReadRequest(SourceSet.Single(movie), context)).orThrow()
        // Ordinary ISO-BMFF follows the existing Unknown/no-protocol disposition, not JPEG's NonLive.
        assertEquals(Disposition.Unknown, detected.disposition); assertTrue(detected.matches.isEmpty())
        assertNull(AppleVideoReader.read(BinaryReader(movie, context), ParseBudget(context)).orThrow())
        val before = BmffVideoProbe(BinaryReader(input.video, context), allowTimedMetadata = true)
            .probe(ByteRange(0uL, input.video.size().orThrow())).orThrow()
        val after = BmffVideoProbe(BinaryReader(movie, context), allowTimedMetadata = true)
            .probe(ByteRange(0uL, movie.size().orThrow())).orThrow()
        assertEquals(before.tracks.filter { it.handler != "meta" }, after.tracks)
        for (sample in after.tracks.flatMap { it.samples }) {
            assertEquals(sha256Range(BinaryReader(input.video, context), sample.range).orThrow(),
                sha256Range(BinaryReader(movie, context), sample.range).orThrow())
        }
        val repeated = core.split(SplitRequest(SourceSet.Single(movie), policy = MutationPolicy(preservation = PreservationPolicy.Strict),
            output = MemoryOutputTransaction(context, "text-clean-repeat"), context = context)).orThrow()
        assertEquals(hash(movie), hash(repeated.output.assets.single().readableSource!!))
        assertTrue(repeated.execution.none { it.remuxed || it.transcoded })
        assertEquals(beforeImage, hash(input.image)); assertEquals(beforeMovie, hash(input.video))
        assertTrue(split.preservation.records.any { it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
    }

    @Test fun firstCleanupDoesNotBorrowTheStrictGuaranteeOfLaterUnmodifiedCopies(): Unit = runImmediate {
        val input = pair(); val tx = MemoryOutputTransaction(context, "text-clean-strict")
        val result = core.split(SplitRequest(input, policy = MutationPolicy(preservation = PreservationPolicy.Strict),
            output = tx, context = context))
        assertEquals(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), assertIs<CoreResult.Failure>(result).error.code)
        assertTrue(tx.committedAssets().isEmpty())
    }

    @Test fun conversionToGoogleRetainsTheOrdinaryDirectoryWithoutEncodingOrHidingUnknownAssociations(): Unit = runImmediate {
        val input = pair(); val before = AppleOrdinaryMovieTextTest().envelope(input.video)
        val converted = core.convert(ConvertRequest(input, ProtocolSelector(ProtocolIds.GoogleV2),
            output = MemoryOutputTransaction(context, "text-clean-convert"), context = context)).orThrow()
        assertTrue(converted.execution.none { it.transcoded || it.remuxed })
        assertTrue(converted.preservation.records.any { it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
        val composite = converted.output.assets.single().readableSource!!
        assertEquals(ProtocolIds.GoogleV2, core.inspect(ReadRequest(SourceSet.Single(composite), context)).orThrow().detection.primaryProtocol?.protocol)
        val extracted = core.extract(ExtractRequest(SourceSet.Single(composite), emptyList(),
            output = MemoryOutputTransaction(context, "text-clean-convert-extract"), context = context)).orThrow()
        val video = extracted.output.assets.single { it.role == AssetRole.MotionVideo }.readableSource!!
        assertEquals(before, AppleOrdinaryMovieTextTest().envelope(video))
        assertNull(AppleVideoReader.read(BinaryReader(video, context), ParseBudget(context)).orThrow())
        val strict = MemoryOutputTransaction(context, "text-clean-convert-strict")
        assertEquals(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), assertIs<CoreResult.Failure>(core.convert(ConvertRequest(input,
            ProtocolSelector(ProtocolIds.GoogleV2), policy = MutationPolicy(preservation = PreservationPolicy.Strict),
            output = strict, context = context))).error.code)
        assertTrue(strict.committedAssets().isEmpty())
    }

    @Test fun corruptedStagedOrdinaryTextAbortsTheWholeCleanPair(): Unit = runImmediate {
        for (metadata in listOf(OrdinaryMovieTextFixtures.envelope(), OrdinaryMovieTextFixtures.indexedEnvelope())) {
        val input = pair(metadata); val tx = MemoryOutputTransaction(context, "text-clean-tamper")
        val output = object : OutputTransaction by tx {
            override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> {
                val view = tx.openStaged(id).orThrow()
                if (id.value != "asset-1") return CoreResult.Success(view)
                val reader = BinaryReader(view, context); val boxes = BmffReader(reader)
                val movie = boxes.readBoxes(ByteRange(0uL, view.size().orThrow())).orThrow().single { it.type == "moov" }
                val changed = boxes.readBoxes(movie.payload, 1u).orThrow().single { it.type == "udta" }.range.endExclusive - 1uL
                return CoreResult.Success(object : BinarySource by view {
                    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = attempt {
                        val bytes = view.readAt(offset, length).orThrow().toByteArray()
                        if (offset <= changed && changed - offset < bytes.size.toULong()) {
                            val index = (changed - offset).toInt(); bytes[index] = (bytes[index].toInt() xor 1).toByte()
                        }
                        Bytes(bytes)
                    }
                })
            }
        }
        assertEquals(IssueCode("POSTCONDITION_FAILED"), assertIs<CoreResult.Failure>(core.split(SplitRequest(input,
            output = output, context = context))).error.code)
        assertEquals(TransactionState.Aborted, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
        }
    }

    @Test fun indexedTextCleanAndConvertRetainOrdinaryKeysWithoutClaimingStrictFirstCleanup(): Unit = runImmediate {
        val input = pair(OrdinaryMovieTextFixtures.indexedEnvelope())
        val original = hash(input.video); val text = AppleOrdinaryMovieTextTest().envelope(input.video)
        val split = core.split(SplitRequest(input, output = MemoryOutputTransaction(context, "mdta-clean"), context = context)).orThrow()
        val movie = split.output.assets.single { it.role == AssetRole.MotionVideo }.readableSource!!
        assertEquals(text, AppleOrdinaryMovieTextTest().envelope(movie))
        assertNull(AppleVideoReader.read(BinaryReader(movie, context), ParseBudget(context)).orThrow())
        val repeated = core.split(SplitRequest(SourceSet.Single(movie), policy = MutationPolicy(preservation = PreservationPolicy.Strict),
            output = MemoryOutputTransaction(context, "mdta-repeat"), context = context)).orThrow()
        assertEquals(hash(movie), hash(repeated.output.assets.single().readableSource!!))
        val converted = core.convert(ConvertRequest(input, ProtocolSelector(ProtocolIds.GoogleV2),
            output = MemoryOutputTransaction(context, "mdta-convert"), context = context)).orThrow()
        val extracted = core.extract(ExtractRequest(SourceSet.Single(converted.output.assets.single().readableSource!!), emptyList(),
            output = MemoryOutputTransaction(context, "mdta-extract"), context = context)).orThrow()
        assertEquals(text, AppleOrdinaryMovieTextTest().envelope(extracted.output.assets.single { it.role == AssetRole.MotionVideo }.readableSource!!))
        assertTrue(converted.execution.none { it.remuxed || it.transcoded })
        assertTrue(split.preservation.records.any { it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
        val blocked = MemoryOutputTransaction(context, "mdta-clean-strict")
        assertEquals(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), assertIs<CoreResult.Failure>(core.split(SplitRequest(input,
            policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = blocked, context = context))).error.code)
        assertTrue(blocked.committedAssets().isEmpty()); assertEquals(original, hash(input.video))
    }
}
