package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import kotlin.test.*

/** HEIF primary is independent framing; the movie is explicitly a finite Core-assembled fixture. */
internal object AppleHeifKeyFixtures {
    suspend fun pair(context: Context, mov: Boolean = false, idat: Boolean = false, hevc: Boolean = false, audio: Boolean = false): SourceSet.Pair {
        fun source(bytes: ByteArray, name: String) = MemoryBinarySource(Bytes(bytes), SourceId(name))
        val video = GoogleFixtures.video(hevc = hevc, aac = audio).bytes
        if (mov) {
            val parser = BmffReader(BinaryReader(source(video, "mov-layout"), context))
            val roots = parser.readBoxes(ByteRange(0uL, video.size.toULong())).orThrow()
            val movie = roots.single { it.type == "moov" }
            for (track in parser.readBoxes(movie.payload).orThrow().filter { it.type == "trak" }) {
                val mdia = parser.readBoxes(track.payload).orThrow().single { it.type == "mdia" }
                val minf = parser.readBoxes(mdia.payload).orThrow().single { it.type == "minf" }
                val stbl = parser.readBoxes(minf.payload).orThrow().single { it.type == "stbl" }
                val stsd = parser.readBoxes(stbl.payload).orThrow().single { it.type == "stsd" }
                val entry = parser.readBoxes(ByteRange(stsd.payload.offset + 8uL, stsd.payload.length - 8uL)).orThrow().single()
                if (entry.type in setOf("avc1", "hvc1")) ("FFMP".encodeToByteArray() + GoogleFixtures.u32(512u) + GoogleFixtures.u32(512u))
                    .copyInto(video, entry.payload.offset.toInt() + 12)
            }
            "qt  ".encodeToByteArray().copyInto(video, 8)
        }
        val core = DefaultLivePhotoCore()
        val created = core.create(CreateRequest(source(GoogleFixtures.jpeg(), "ordinary-primary"), source(video, "ordinary-movie"),
            ProtocolSelector(ProtocolIds.Apple, ProfileId(if (mov) "jpeg-mov" else "jpeg-mp4")),
            edits = EditSpec(keyPosition = CoverPosition.FrameIndex(0uL)), output = MemoryOutputTransaction(context, "heic-key-fixture"), context = context)).orThrow()
        val movie = created.output.assets[1].readableSource!!
        try {
            val id = core.inspect(ReadRequest(SourceSet.Pair(created.output.assets[0].readableSource!!, movie), context)).orThrow().pairing!!.imageIdentifier!!
            return SourceSet.Pair(source(AppleHeifFixtures.image(id, idat = idat), "heic-primary"), movie)
        } finally { created.output.assets[0].readableSource!!.close() }
    }
}

class AppleHeifKeyTest {
    private val context = Context(Limits(12_000_000uL, 12_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val strict = MutationPolicy(preservation = PreservationPolicy.Strict)
    private suspend fun bytes(source: BinarySource) = BinaryReader(source, context).readExactly(0uL, source.size().orThrow().toUInt()).orThrow()

    @Test fun zeroNonzeroZeroKeepsWholePrimaryPairAndEveryUnrequestedMovieByte(): Unit = runImmediate {
        for (mov in listOf(false, true)) for (idat in listOf(false, true)) for (hevc in listOf(false, true)) for (audio in listOf(false, true)) {
            val input = AppleHeifKeyFixtures.pair(context, mov, idat, hevc, audio)
            val imageBefore = bytes(input.image); val movieBefore = bytes(input.video)
            val before = core.inspect(ReadRequest(input, context)).orThrow()
            val tx = MemoryOutputTransaction(context, "heif-set-key")
            val request = SetKeyRequest(input, CoverPosition.FrameIndex(1uL), strict, tx, context)
            val plan = core.plan(request).orThrow()
            assertEquals(before.detection.primaryProtocol, plan.target)
            assertEquals(Implementation.Experimental, plan.capabilities.operations.single().implementation)
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
            val changed = core.setKeyPhotoPosition(request).orThrow()
            val edited = SourceSet.Pair(changed.output.assets[0].readableSource!!, changed.output.assets[1].readableSource!!)
            val after = core.inspect(ReadRequest(edited, context)).orThrow()
            assertEquals(before.pairing, after.pairing); assertEquals(before.detection.primaryProtocol, after.detection.primaryProtocol)
            assertEquals(0, after.keyPhoto.position!!.compareTo(Time(40, 1000u)))
            assertEquals(imageBefore, bytes(edited.image)); assertEquals("image/heic", changed.output.assets[0].mime)
            assertEquals(ImageFormat.Heic, changed.output.assets[0].imageFormat)
            assertEquals(Coverage.Partial, changed.validation.coverage)
            assertTrue(changed.preservation.records.all { it.outcome in setOf(GuaranteeOutcome.Verified, GuaranteeOutcome.NotApplicable) })
            assertTrue(changed.execution.none { it.transcoded || it.remuxed || it.stage in setOf(Stage.DecodeFrame, Stage.EncodeImage) })
            val originalReader = BinaryReader(input.video, context); val editedReader = BinaryReader(edited.video, context)
            val original = BmffVideoProbe(originalReader, allowTimedMetadata = true).probe(ByteRange(0uL, movieBefore.size.toULong())).orThrow()
            val patched = BmffVideoProbe(editedReader, allowTimedMetadata = true).probe(ByteRange(0uL, movieBefore.size.toULong())).orThrow()
            assertEquals(original.tracks.filter { it.handler != "meta" }, patched.tracks.filter { it.handler != "meta" })
            for ((left, right) in original.tracks.flatMap { it.samples }.zip(patched.tracks.flatMap { it.samples })) {
                assertEquals(left.range, right.range); assertEquals(sha256Range(originalReader, left.range).orThrow(), sha256Range(editedReader, right.range).orThrow())
            }
            val restored = core.setKeyPhotoPosition(SetKeyRequest(edited, CoverPosition.FrameIndex(0uL), strict,
                MemoryOutputTransaction(context, "heif-key-restore"), context)).orThrow()
            assertEquals(imageBefore, bytes(restored.output.assets[0].readableSource!!)); assertEquals(movieBefore, bytes(restored.output.assets[1].readableSource!!))
            assertEquals(imageBefore, bytes(input.image)); assertEquals(movieBefore, bytes(input.video))
            restored.output.assets.forEach { it.readableSource?.close() }; changed.output.assets.forEach { it.readableSource?.close() }
            input.image.close(); input.video.close()
        }
    }

    @Test fun missingFixedEnvelopeInvalidPositionExactAndNonAtomicRequirementsNeverStage(): Unit = runImmediate {
        val input = AppleHeifKeyFixtures.pair(context)
        val tx = MemoryOutputTransaction(context, "heif-key-gates")
        assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(SetKeyRequest(input, CoverPosition.FrameIndex(2uL), output = tx, context = context)))
        assertEquals("PRESERVATION_REQUIREMENT_FAILED", assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(SetKeyRequest(input,
            CoverPosition.FrameIndex(1uL), MutationPolicy(preservation = PreservationPolicy.Strict, requiredGuarantees = listOf(Guarantee.ExactExtraction)), tx, context))).error.code.value)
        val weak = object : OutputTransaction by tx {
            override fun capabilities() = tx.capabilities().copy(assetSetAtomic = false)
        }
        assertEquals("ATOMIC_PUBLICATION_UNAVAILABLE", assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(SetKeyRequest(input, CoverPosition.FrameIndex(1uL), output = weak, context = context))).error.code.value)
        val oldMovie = SourceSet.Pair(MemoryBinarySource(Bytes(AppleHeifFixtures.image()), SourceId("heic")), MemoryBinarySource(Bytes(AppleFixtures.movie()), SourceId("old-movie")))
        assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(SetKeyRequest(oldMovie, CoverPosition.FrameIndex(0uL), output = tx, context = context)))
        assertTrue(tx.query().orThrow().assetIds.isEmpty())
    }

    @Test fun secondAssetFailureRollsBackKeyPairWithoutClosingOrChangingInput(): Unit = runImmediate {
        val input = AppleHeifKeyFixtures.pair(context, mov = true, idat = true)
        val beforeImage = bytes(input.image); val beforeMovie = bytes(input.video)
        val tx = MemoryOutputTransaction(context, "heif-key-second-failure")
        var creates = 0
        val output = object : OutputTransaction by tx {
            override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> {
                if (++creates == 2) return CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), Stage.WriteProtocol, "Synthetic second asset failure"))
                return tx.create(spec)
            }
        }
        assertEquals("IO_WRITE_FAILED", assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(SetKeyRequest(input, CoverPosition.FrameIndex(1uL), strict, output, context))).error.code.value)
        assertEquals(2, creates); assertEquals(TransactionState.Aborted, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
        assertEquals(beforeImage, bytes(input.image)); assertEquals(beforeMovie, bytes(input.video))
    }
}
