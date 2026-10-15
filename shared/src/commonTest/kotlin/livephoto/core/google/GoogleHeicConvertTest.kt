package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.heif.*
import livephoto.core.memory.*
import kotlin.test.*

class GoogleHeicConvertTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("heic"))
    private fun source(bytes: Bytes, id: String) = MemoryBinarySource(bytes, SourceId(id))
    private suspend fun photo(key: Long? = 0L, idat: Boolean = false): Bytes {
        val video = GoogleFixtures.video(aac = true).bytes
        // Independent requested packet and streaming item proof, not using public Convert as a fixture oracle.
        val xml = GoogleDirectoryWriter.heic(video.size.toULong(), key ?: -1L, context)
        val image = BinaryReader(source(Bytes(HeifFixtures.plain(idat = idat, multiple = true)), "normalization-image"), context)
        val plan = HeifXmpAppender.prepare(image, xml).orThrow()
        val tx = MemoryOutputTransaction(context, "normalize-fixture-$key-$idat")
        val handle = tx.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/heic")).orThrow()
        plan.write(image, BinaryWriter(handle.sink, context)).orThrow(); handle.sink.close().orThrow(); tx.prepare().orThrow()
        val staged = tx.openStaged(handle.id).orThrow()
        val reader = BinaryReader(staged, context)
        plan.verify(image, reader).orThrow()
        val prefix = reader.readExactly(0uL, staged.size().orThrow().toUInt()).orThrow()
        staged.close(); tx.abort().orThrow()
        return Bytes(prefix.toByteArray() + GoogleFixtures.box("mpvd", video))
    }
    @Test fun preserveAsIsCopiesEveryByteWithoutNormalizingMetadataOrChangingKey(): Unit = runImmediate {
        val bytes = photo(40_000L)
        val input = SourceSet.Single(source(bytes, "heic-convert-exact"))
        val tx = MemoryOutputTransaction(context, "heic-as-is")
        val req = ConvertRequest(input, target, policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = tx, context = context)
        val planned = core.plan(req).orThrow()
        assertEquals(listOf(input.source.identity().orThrow()), planned.snapshot.identities)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
        val result = core.convert(req).orThrow()
        assertEquals(bytes, tx.committedAssets().values.single())
        assertEquals("image/heic", result.output.assets.single().mime)
        assertEquals(ImageFormat.Heic, result.output.assets.single().imageFormat)
        assertTrue(result.preservation.changes.isEmpty())
        assertTrue(result.preservation.records.any { it.guarantee == Guarantee.ExactExtraction && it.outcome == GuaranteeOutcome.Verified })
    }
    @Test fun normalizePreservesKnownAndUnspecifiedKeysAndAllMediaWithoutPublishingIntermediateAssets(): Unit = runImmediate {
        for (idat in listOf(false, true)) for (key in listOf(0L, 40_000L, null)) {
            val bytes = photo(key, idat)
            val input = SourceSet.Single(source(bytes, "heic-normalize-input"))
            val tx = MemoryOutputTransaction(context, "heic-normalize-$key-$idat")
            val req = ConvertRequest(input, target, sameTarget = SameTargetPolicy.Normalize, policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = tx, context = context)
            core.plan(req).orThrow()
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
            val result = core.convert(req).orThrow()
            assertEquals(1, result.output.assets.size)
            assertEquals(TransactionState.Committed, result.output.receipt.state)
            val normalized = tx.committedAssets().values.single()
            val read = SourceSet.Single(source(normalized, "heic-normalized"))
            val inspected = core.inspect(ReadRequest(read, context)).orThrow()
            assertEquals(target, inspected.detection.primaryProtocol)
            assertEquals(key?.let { Time(it, 1_000_000u) }, inspected.keyPhoto.position)
            assertEquals(if (key == null) Value.Text("-1") else Value.Text(key.toString()), inspected.keyPhoto.rawFields.single().rawValue)
            val raw = MemoryOutputTransaction(context, "heic-normalized-raw-$key-$idat")
            core.extract(ExtractRequest(read, emptyList(), output = raw, context = context)).orThrow()
            assertEquals(Bytes(GoogleFixtures.video(aac = true).bytes), raw.committedAssets().values.single())
            assertEquals(bytes, BinaryReader(input.source, context).readExactly(0uL, bytes.size.toUInt()).orThrow())
            assertTrue(result.preservation.records.none { it.outcome == GuaranteeOutcome.Unknown || it.outcome == GuaranteeOutcome.Changed })
            assertTrue(result.execution.none { it.transcoded || it.remuxed })
        }
    }
    @Test fun explicitKeyEditRequiresNormalizeAndDoesNotAlterVideoOrSilentlyDeriveAnUnspecifiedKey(): Unit = runImmediate {
        val bytes = photo(null)
        val input = SourceSet.Single(source(bytes, "heic-no-key-source"))
        val asIs = MemoryOutputTransaction(context, "heic-no-as-is-edits")
        val edits = EditSpec(keyPosition = CoverPosition.FrameIndex(1uL))
        assertEquals(IssueCode("INVALID_ARGUMENT"), assertIs<CoreResult.Failure>(core.convert(ConvertRequest(input, target, edits = edits, output = asIs, context = context))).error.code)
        assertTrue(asIs.query().orThrow().assetIds.isEmpty())
        val output = MemoryOutputTransaction(context, "heic-normalize-explicit-key")
        core.convert(ConvertRequest(input, target, edits = edits, sameTarget = SameTargetPolicy.Normalize, output = output, context = context)).orThrow()
        val result = SourceSet.Single(source(output.committedAssets().values.single(), "heic-new-key"))
        assertEquals(Time(40_000, 1_000_000u), core.getKeyPhotoPosition(ReadRequest(result, context)).orThrow().position)
        val raw = MemoryOutputTransaction(context, "heic-edited-key-video")
        core.extract(ExtractRequest(result, emptyList(), output = raw, context = context)).orThrow()
        assertEquals(Bytes(GoogleFixtures.video(aac = true).bytes), raw.committedAssets().values.single())
    }
    @Test fun normalizedPublicationRejectsTheOriginalInputIdentityNotOnlyDerivedViews(): Unit = runImmediate {
        val bytes = photo()
        val original = source(bytes, "heic-original-alias")
        val base = MemoryOutputTransaction(context, "heic-normalize-original-alias")
        val output = object : OutputTransaction by base {
            override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> = CoreResult.Success(original)
        }
        val result = core.convert(ConvertRequest(SourceSet.Single(original), target, sameTarget = SameTargetPolicy.Normalize, output = output, context = context))
        assertEquals(IssueCode("OUTPUT_ALIASES_INPUT"), assertIs<CoreResult.Failure>(result).error.code)
        assertEquals(TransactionState.Aborted, base.query().orThrow().state)
        assertTrue(base.committedAssets().isEmpty())
        assertEquals(bytes.size.toULong(), original.size().orThrow())
    }
}
