package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.memory.*
import kotlin.test.*

class AppleHeifCleanTest {
    private val context = Context(Limits(12_000_000uL, 12_000_000uL))
    private val core = DefaultLivePhotoCore()
    private fun source(bytes: ByteArray, name: String) = MemoryBinarySource(Bytes(bytes), SourceId(name))
    private suspend fun bytes(source: BinarySource) = BinaryReader(source, context).readExactly(0uL, source.size().orThrow().toUInt()).orThrow()

    @Test fun cleanRetiresOnlyOwnedBindingsKeepsCodedItemsAndRepeatsByteExact(): Unit = runImmediate {
        for (mov in listOf(false, true)) for (idat in listOf(false, true)) {
            val input = AppleHeifKeyFixtures.pair(context, mov = mov, idat = idat, audio = true)
            val originalImage = bytes(input.image); val originalMovie = bytes(input.video)
            val before = core.inspect(ReadRequest(input, context)).orThrow()
            val result = core.split(SplitRequest(input, output = MemoryOutputTransaction(context, "heif-clean-$mov-$idat"), context = context)).orThrow()
            assertEquals(listOf(AssetRole.PrimaryImage, AssetRole.MotionVideo), result.output.assets.map { it.role })
            assertEquals("image/heic", result.output.assets[0].mime); assertEquals(ImageFormat.Heic, result.output.assets[0].imageFormat)
            assertTrue(result.preservation.records.any { it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
            assertTrue(result.execution.none { it.transcoded || it.remuxed })
            val image = result.output.assets[0].readableSource!!; val movie = result.output.assets[1].readableSource!!
            val after = core.inspect(ReadRequest(SourceSet.Single(image), context)).orThrow()
            // Ordinary opaque Exif associations remain unknown; absence of an Apple binding is proved separately.
            assertEquals(Disposition.Unknown, after.detection.disposition); assertTrue(after.detection.matches.isEmpty())
            assertTrue(after.metadata.none { it.owner == Ownership.SourceProtocol })
            AppleHeifClean.validateRetired(BinaryReader(image, context), ParseBudget(context)).orThrow()
            assertEquals(2, after.layout.resources.count { !it.standalone })
            val primary = before.layout.resources.single { it.id == ResourceId("heif:item:1") }
            for (extent in primary.extents) assertEquals(BinaryReader(input.image, context).readExactly(extent.range.offset, extent.range.length.toUInt()).orThrow(),
                BinaryReader(image, context).readExactly(extent.range.offset, extent.range.length.toUInt()).orThrow())
            val originalReader = BinaryReader(input.video, context); val cleanReader = BinaryReader(movie, context)
            val original = BmffVideoProbe(originalReader, allowTimedMetadata = true).probe(ByteRange(0uL, originalMovie.size.toULong())).orThrow()
            val clean = BmffVideoProbe(cleanReader, allowTimedMetadata = true).probe(ByteRange(0uL, movie.size().orThrow())).orThrow()
            assertEquals(original.tracks.filter { it.handler != "meta" }, clean.tracks)
            for (sample in clean.tracks.flatMap { it.samples }) assertEquals(sha256Range(originalReader, sample.range).orThrow(), sha256Range(cleanReader, sample.range).orThrow())
            assertNull(AppleVideoReader.read(cleanReader, ParseBudget(context)).orThrow())
            for ((index, asset) in result.output.assets.withIndex()) {
                val ordinary = asset.readableSource!!
                val repeated = core.split(SplitRequest(SourceSet.Single(ordinary), policy = MutationPolicy(preservation = PreservationPolicy.Strict),
                    output = MemoryOutputTransaction(context, "heif-clean-repeat-$index"), context = context)).orThrow()
                assertEquals(bytes(ordinary), bytes(repeated.output.assets.single().readableSource!!))
                assertTrue(repeated.preservation.changes.isEmpty())
                assertTrue(repeated.preservation.records.all { it.outcome in setOf(GuaranteeOutcome.Verified, GuaranteeOutcome.NotApplicable) })
                repeated.output.assets.forEach { it.readableSource?.close() }
            }
            assertEquals(originalImage, bytes(input.image)); assertEquals(originalMovie, bytes(input.video))
            result.output.assets.forEach { it.readableSource?.close() }; input.image.close(); input.video.close()
        }
    }

    @Test fun ordinaryMakerNoteMovieKeysAndStrictAssociationsNeverAuthorizeCleanup(): Unit = runImmediate {
        for ((image, movie) in listOf(AppleHeifFixtures.image(ordinaryNote = true) to AppleFixtures.movie(ordinaryKey = false),
            AppleHeifFixtures.image() to AppleFixtures.movie(ordinaryKey = true))) {
            val input = SourceSet.Pair(source(image, "heic"), source(movie, "movie"))
            assertEquals(Disposition.Live, core.detect(ReadRequest(input, context)).orThrow().disposition)
            val tx = MemoryOutputTransaction(context, "heif-clean-ordinary-gate")
            assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(core.split(SplitRequest(input, output = tx, context = context))).error.code.value)
            assertTrue(tx.query().orThrow().assetIds.isEmpty()); assertEquals(Bytes(image), bytes(input.image))
        }
        val input = AppleHeifKeyFixtures.pair(context)
        val tx = MemoryOutputTransaction(context, "heif-clean-strict")
        assertEquals("PRESERVATION_REQUIREMENT_FAILED", assertIs<CoreResult.Failure>(core.split(SplitRequest(input, policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = tx, context = context))).error.code.value)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
    }

    @Test fun secondAssetFailureAndInputGenerationChangeAbortAllCleanOutputs(): Unit = runImmediate {
        for (change in listOf(false, true)) {
            val image = TestSource(AppleHeifFixtures.image())
            val movie = source(AppleFixtures.movie(ordinaryKey = false), "movie")
            val input = SourceSet.Pair(image, movie); val originalImage = bytes(image); val originalMovie = bytes(movie)
            val tx = MemoryOutputTransaction(context, "heif-clean-failure-$change")
            var creates = 0
            val output = object : OutputTransaction by tx {
                override suspend fun prepare(): CoreResult<Unit> {
                    if (change) image.currentIdentity = image.currentIdentity.copy(generation = GenerationToken("changed"))
                    return tx.prepare()
                }
                override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> {
                    if (++creates == 2 && !change) return CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.WriteProtocol, "Synthetic second asset failure"))
                    return tx.create(spec)
                }
            }
            assertEquals(if (change) "SOURCE_CHANGED" else "IO_WRITE_FAILED", assertIs<CoreResult.Failure>(core.split(SplitRequest(input, output = output, context = context))).error.code.value)
            assertEquals(TransactionState.Aborted, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty()); assertFalse(image.closed)
            assertEquals(originalImage, bytes(image)); assertEquals(originalMovie, bytes(movie))
        }
    }
}
