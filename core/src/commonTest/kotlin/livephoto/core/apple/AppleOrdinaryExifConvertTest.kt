package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.jpeg.*
import livephoto.core.memory.*
import kotlin.test.*

/** Synthetic source-cleanup and preservation proofs, not device compatibility. */
class AppleOrdinaryExifConvertTest {
    private val context = Context(Limits(16_000_000uL, 16_000_000uL, maxMetadataBytes = 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4"))
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun live(image: ByteArray, v2: Boolean = true): ByteArray {
        val photo = if (v2) GoogleFixtures.v2Photo(timestamp = "0") else GoogleFixtures.v1Photo(timestamp = "0")
        // Insert one independent EXIF segment before the source XMP; video offset remains end-relative.
        val exifLength = ((image[4].toInt() and 255) shl 8) or (image[5].toInt() and 255)
        return photo.copyOfRange(0, 2) + image.copyOfRange(2, 4 + exifLength) + photo.copyOfRange(2, photo.size)
    }
    private suspend fun hash(source: BinarySource) = sha256Range(BinaryReader(source, context), ByteRange(0uL, source.size().orThrow())).orThrow()

    @Test fun bothGoogleSourcesAndTiffEndiansRetainOrdinaryValuesOffsetsCodingAndSourceKey(): Unit = runImmediate {
        for (v2 in listOf(false, true)) for (endian in Endian.entries) {
            val input = source(live(AppleOrdinaryExifTest().image(endian), v2), "exif-live-$v2-$endian")
            val session = SourceSession.open(SourceSet.Single(input), context, ParseBudget(context)).orThrow()
            val beforeHash = hash(input)
            val tx = MemoryOutputTransaction(context, "exif-convert")
            val req = ConvertRequest(SourceSet.Single(input), target, output = tx, context = context)
            core.plan(req).orThrow(); assertTrue(tx.query().orThrow().assetIds.isEmpty())
            val result = core.convert(req).orThrow()
            val image = result.output.assets[0].readableSource!!
            val imageSession = SourceSession.open(SourceSet.Single(image), context, ParseBudget(context)).orThrow()
            assertEquals(codingDigest(session), codingDigest(imageSession))
            val before = session.exifComments.single().document
            val after = imageSession.exifComments.single().document
            assertEquals(endian, after.endian)
            for (field in before.ifds.flatMap { it.entries }.filter { it.tag != 0x8769u.toUShort() }) {
                val copied = after.ifds.flatMap { it.entries }.single { it.tag == field.tag }
                assertEquals(field.type, copied.type); assertEquals(field.count, copied.count); assertEquals(field.value, copied.value)
                if (field.valueRange!!.length > 4uL) assertEquals(field.valueRange.offset - before.range.offset,
                    copied.valueRange!!.offset - after.range.offset)
            }
            val video = result.output.assets[1].readableSource!!; val outputReader = BinaryReader(video, context)
            val outputFacts = BmffVideoProbe(outputReader, allowTimedMetadata = true).probe(ByteRange(0uL, video.size().orThrow())).orThrow()
            val originalFacts = session.videos.values.single()
            RemuxVerification.verify(session.reader, originalFacts, outputReader, outputFacts.copy(tracks = outputFacts.tracks.filter { it.handler != "meta" }))
            assertEquals(Verdict.Valid, result.validation.verdict)
            assertEquals(0, result.keyPhoto?.position?.compareTo(Time.Zero))
            assertTrue(result.execution.none { it.remuxed || it.transcoded })
            assertTrue(result.preservation.records.any { it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
            assertEquals(beforeHash, hash(input))
        }
    }

    @Test fun originalUnknownAssociationOutcomeStillRefusesStrictBeforeAnyAssets(): Unit = runImmediate {
        val input = source(live(AppleOrdinaryExifTest().image(Endian.Big)), "exif-strict")
        val tx = MemoryOutputTransaction(context, "exif-strict-convert")
        assertEquals(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), assertIs<CoreResult.Failure>(core.convert(ConvertRequest(
            SourceSet.Single(input), target, policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = tx, context = context))).error.code)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
    }

    @Test fun changedCleanExifCannotBorrowTheOriginalSourceProof(): Unit = runImmediate {
        val input = source(live(AppleOrdinaryExifTest().image(Endian.Big)), "exif-original")
        val session = SourceSession.open(SourceSet.Single(input), context, ParseBudget(context)).orThrow()
        val tx = MemoryOutputTransaction(context, "exif-clean-tamper")
        val req = ConvertRequest(SourceSet.Single(input), target, output = tx, context = context)
        val clean = ConvertOperations.prepare(req, session, ParseBudget(context))!!
        val reader = BinaryReader(clean.first, context)
        val jpeg = JpegParser.parse(reader).orThrow()
        val originalTiff = session.exifComments.single().document
        val field = originalTiff.ifds.flatMap { it.entries }.single { it.tag == 0x0112u.toUShort() }
        val cleanTiff = jpeg.segments.single { it.payloadKind == AppPayloadKind.Exif }.payload!!.offset + 6uL
        val changed = cleanTiff + field.valueRange!!.offset - originalTiff.range.offset + 1uL
        val altered = FixedPatchSource.create(reader, listOf(FixedPatch(ByteRange(changed, 1uL), Bytes(byteArrayOf(1))))).orThrow()
        assertEquals(IssueCode("POSTCONDITION_FAILED"), assertIs<CoreResult.Failure>(AppleAssemblyOperations.plan(req, session, altered to clean.second)).error.code)
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
    }

    @Test fun privateNotesDuplicateExifAndUnclassifiedSlackRemainRejected(): Unit = runImmediate {
        val ordinary = AppleOrdinaryExifTest().image(Endian.Big)
        val length = ((ordinary[4].toInt() and 255) shl 8) or (ordinary[5].toInt() and 255)
        val segment = ordinary.copyOfRange(2, 4 + length)
        val slack = GoogleFixtures.jpeg(GoogleFixtures.segment(0xe1, segment.copyOfRange(4, segment.size) + byteArrayOf(7)))
        val duplicate = live(ordinary).let { it.copyOfRange(0, 2) + segment + it.copyOfRange(2, it.size) }
        for (bytes in listOf(live(AppleFixtures.image(tag = 1)), live(slack), duplicate)) {
            val tx = MemoryOutputTransaction(context, "exif-unsafe-convert")
            assertIs<CoreResult.Failure>(core.convert(ConvertRequest(SourceSet.Single(source(bytes, "exif-unsafe")), target, output = tx, context = context)))
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
    }

    @Test fun stagedOrdinaryExifMutationAbortsBothConversionAssets(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "exif-convert-tamper")
        val output = object : OutputTransaction by tx {
            override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> {
                val view = tx.openStaged(id).orThrow()
                if (id.value != "asset-0") return CoreResult.Success(view)
                val reader = BinaryReader(view, context)
                val payload = JpegParser.parse(reader).orThrow().segments.single { it.payloadKind == AppPayloadKind.Exif }.payload!!
                val changed = payload.offset + 6uL + 90uL // Independent fixture's inline GPS latitude reference N -> S.
                return CoreResult.Success(FixedPatchSource.create(reader, listOf(FixedPatch(ByteRange(changed, 1uL), Bytes(byteArrayOf(83))))).orThrow())
            }
        }
        assertIs<CoreResult.Failure>(core.convert(ConvertRequest(SourceSet.Single(source(live(AppleOrdinaryExifTest().image(Endian.Big)), "exif-tamper")),
            target, output = output, context = context)))
        assertEquals(TransactionState.Aborted, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
    }
}
