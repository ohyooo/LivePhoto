package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

/** Synthetic protocol/metadata proof, not a device import claim. */
internal object OrdinaryMovieTextFixtures {
    fun atom(type: String, payload: ByteArray): ByteArray = GoogleFixtures.u32((payload.size + 8).toUInt()) +
        type.map { it.code.toByte() }.toByteArray() + payload // FourCC is four bytes, not UTF-8.
    fun item(type: String, text: ByteArray, kind: UInt = 1u, locale: UInt = 0u): ByteArray =
        atom(type, atom("data", GoogleFixtures.u32(kind) + GoogleFixtures.u32(locale) + text))
    fun envelope(items: ByteArray = item("\u00a9nam", "普通标题".encodeToByteArray()) +
        item("\u00a9ART", "artist".encodeToByteArray()) + item("\u00a9cmt", "ordinary comment".encodeToByteArray()) +
        item("\u00a9too", "encoder".encodeToByteArray()) + item("cprt", "copyright".encodeToByteArray()),
        extra: ByteArray = byteArrayOf()): ByteArray = atom("udta", GoogleFixtures.fullBox("meta",
            atom("hdlr", ByteArray(8) + "mdirappl".encodeToByteArray() + ByteArray(9)) + atom("ilst", items) + extra))

    /** Retire old moov without moving any sample, append its unchanged children plus the test envelope. */
    fun movie(base: ByteArray = GoogleFixtures.video().bytes, metadata: ByteArray = envelope()): ByteArray {
        fun parts(bytes: ByteArray): List<ByteArray> {
            val result = mutableListOf<ByteArray>(); var cursor = 0
            while (cursor < bytes.size) {
                var size = 0; repeat(4) { size = (size shl 8) or (bytes[cursor + it].toInt() and 255) }
                require(size >= 8 && size <= bytes.size - cursor)
                result += bytes.copyOfRange(cursor, cursor + size); cursor += size
            }
            return result
        }
        fun type(bytes: ByteArray) = bytes.copyOfRange(4, 8).decodeToString()
        val roots = parts(base); val old = roots.single { type(it) == "moov" }
        val retained = parts(old.copyOfRange(8, old.size)).filter { type(it) != "udta" }
            .fold(byteArrayOf()) { bytes, child -> bytes + child }
        return roots.fold(byteArrayOf()) { bytes, root -> bytes + if (root === old) atom("free", ByteArray(old.size - 8)) else root } +
            atom("moov", retained + metadata)
    }
}

class AppleOrdinaryMovieTextTest {
    private val context = Context(Limits(16_000_000uL, 16_000_000uL, maxMetadataBytes = 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun request(movie: ByteArray, output: OutputTransaction) = CreateRequest(source(GoogleFixtures.jpeg(), "text-image"),
        source(movie, "text-movie"), ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4")),
        policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = output, context = context)
    internal suspend fun envelope(source: BinarySource): Bytes {
        val reader = BinaryReader(source, context); val boxes = BmffReader(reader)
        val moov = boxes.readBoxes(ByteRange(0uL, source.size().orThrow())).orThrow().single { it.type == "moov" }
        val udta = boxes.readBoxes(moov.payload, 1u).orThrow().single { it.type == "udta" }
        assertTrue(OrdinaryMovieText.matches(reader, boxes, udta, 1u, ParseBudget(context)))
        return reader.readExactly(udta.range.offset, udta.range.length.toUInt()).orThrow()
    }

    @Test fun strictCreateRetainsOrdinaryDirectoryAndEveryOriginalMediaSample(): Unit = runImmediate {
        val bytes = OrdinaryMovieTextFixtures.movie(); val tx = MemoryOutputTransaction(context, "text-create")
        val req = request(bytes, tx)
        core.plan(req).orThrow(); assertTrue(tx.query().orThrow().assetIds.isEmpty())
        val result = core.create(req).orThrow()
        val movie = result.output.assets[1].readableSource!!
        assertEquals(envelope(req.video), envelope(movie))
        assertEquals(Verdict.Valid, result.validation.verdict)
        assertTrue(result.preservation.records.all { it.outcome in setOf(GuaranteeOutcome.Verified, GuaranteeOutcome.NotApplicable) })
        val before = BinaryReader(req.video, context); val after = BinaryReader(movie, context)
        val a = BmffVideoProbe(before).probe(ByteRange(0uL, bytes.size.toULong())).orThrow()
        val b = BmffVideoProbe(after, allowTimedMetadata = true).probe(ByteRange(0uL, movie.size().orThrow())).orThrow()
        RemuxVerification.verify(before, a, after, b.copy(tracks = b.tracks.filter { it.handler != "meta" }))
        assertEquals(Bytes(bytes), before.readExactly(0uL, bytes.size.toUInt()).orThrow())
        // Reading this ordinary directory grants no cleanup/unknown-association write permission.
        val split = MemoryOutputTransaction(context, "text-split-blocked")
        assertEquals(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), assertIs<CoreResult.Failure>(core.split(
            SplitRequest(SourceSet.Pair(result.output.assets[0].readableSource!!, movie),
                policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = split, context = context))).error.code)
        assertTrue(split.committedAssets().isEmpty())
    }

    @Test fun secondaryAuthoritiesDuplicatesNonUtf8AndUnexpectedDataCannotAuthorizeCreate(): Unit = runImmediate {
        val item = OrdinaryMovieTextFixtures.item("\u00a9too", "encoder".encodeToByteArray())
        val badItems = listOf(item + item, OrdinaryMovieTextFixtures.item("----", "private".encodeToByteArray()),
            OrdinaryMovieTextFixtures.item("\u00a9too", byteArrayOf(0xc0.toByte(), 0x80.toByte())),
            OrdinaryMovieTextFixtures.item("\u00a9too", byteArrayOf(65, 0, 66)),
            OrdinaryMovieTextFixtures.item("\u00a9too", byteArrayOf(65), kind = 21u),
            OrdinaryMovieTextFixtures.item("\u00a9too", byteArrayOf(65), locale = 1u),
            OrdinaryMovieTextFixtures.atom("\u00a9too", item + item))
        val bad = badItems.map { OrdinaryMovieTextFixtures.envelope(it) } + listOf(
            OrdinaryMovieTextFixtures.envelope(item, GoogleFixtures.fullBox("keys", GoogleFixtures.u32(0u))),
            OrdinaryMovieTextFixtures.envelope(item) + OrdinaryMovieTextFixtures.envelope(item))
        for ((index, metadata) in bad.withIndex()) {
            val tx = MemoryOutputTransaction(context, "text-bad-$index")
            assertIs<CoreResult.Failure>(core.create(request(OrdinaryMovieTextFixtures.movie(metadata = metadata), tx)))
            assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
    }

    @Test fun rawRetentionClassificationDoesNotRelaxRemuxTrimOrTranscodeMetadataGates(): Unit = runImmediate {
        val bytes = OrdinaryMovieTextFixtures.movie(); val reader = BinaryReader(source(bytes, "text-remux-gate"), context)
        val video = BmffVideoProbe(reader).probe(ByteRange(0uL, bytes.size.toULong())).orThrow()
        for ((trim, transcode) in listOf(false to false, true to false, true to true)) {
            val result = attempt { RemuxVerification.metadata(reader, video, trim, transcode) }
            assertEquals(IssueCode("UNSAFE_METADATA_REWRITE"), assertIs<CoreResult.Failure>(result).error.code)
        }
    }

    @Test fun changedStagedOrdinaryTextAbortsBothAssets(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "text-tamper")
        val output = object : OutputTransaction by tx {
            override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> {
                val view = tx.openStaged(id).orThrow()
                if (id.value != "asset-1") return CoreResult.Success(view)
                val reader = BinaryReader(view, context); val boxes = BmffReader(reader)
                val moov = boxes.readBoxes(ByteRange(0uL, view.size().orThrow())).orThrow().single { it.type == "moov" }
                val udta = boxes.readBoxes(moov.payload, 1u).orThrow().single { it.type == "udta" }
                val changed = udta.range.endExclusive - 1uL
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
        assertIs<CoreResult.Failure>(core.create(request(OrdinaryMovieTextFixtures.movie(), output)))
        assertEquals(TransactionState.Aborted, tx.query().orThrow().state); assertTrue(tx.committedAssets().isEmpty())
    }
}
