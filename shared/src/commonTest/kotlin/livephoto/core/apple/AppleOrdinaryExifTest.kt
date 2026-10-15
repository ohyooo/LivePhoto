package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.exif.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.jpeg.*
import livephoto.core.memory.*
import livephoto.core.oplus.OplusFixtures
import kotlin.test.*

/** Independent ordinary EXIF + synthetic media; no captured-device or decoder claim. */
class AppleOrdinaryExifTest {
    private val context = Context(Limits(12_000_000uL, 12_000_000uL, maxMetadataBytes = 8_000_000uL))
    private val core = DefaultLivePhotoCore()
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    internal fun image(endian: Endian): ByteArray {
        fun u16(n: ULong) = unsignedBytes(n, 2, endian).toByteArray()
        fun u32(n: ULong) = unsignedBytes(n, 4, endian).toByteArray()
        fun entry(tag: ULong, type: ULong, count: ULong, field: ByteArray) = u16(tag) + u16(type) + u32(count) + field
        val description = "copyright\u0000".encodeToByteArray()
        val comment = "ASCII\u0000\u0000\u0000ordinary comment\u0000".encodeToByteArray()
        // TIFF IFD0=8 (4 entries), ExifIFD=62, GPSIFD=80, external values=98.
        val tiff = (if (endian == Endian.Big) byteArrayOf(77, 77) else byteArrayOf(73, 73)) + u16(42uL) + u32(8uL) + u16(4uL) +
            entry(0x010euL, 2uL, description.size.toULong(), u32(98uL)) +
            entry(0x0112uL, 3uL, 1uL, u16(6uL) + byteArrayOf(0, 0)) +
            entry(0x8769uL, 4uL, 1uL, u32(62uL)) + entry(0x8825uL, 4uL, 1uL, u32(80uL)) + u32(0uL) +
            u16(1uL) + entry(0x9286uL, 7uL, comment.size.toULong(), u32((98 + description.size).toULong())) + u32(0uL) +
            u16(1uL) + entry(1uL, 2uL, 2uL, byteArrayOf(78, 0, 0, 0)) + u32(0uL) + description + comment
        return GoogleFixtures.jpeg(GoogleFixtures.segment(0xe1, "Exif\u0000\u0000".encodeToByteArray() + tiff))
    }
    private suspend fun document(source: BinarySource): TiffDocument {
        val reader = BinaryReader(source, context)
        val payload = JpegParser.parse(reader).orThrow().segments.single { it.payloadKind == AppPayloadKind.Exif }.payload!!
        return TiffReader(reader).read(ByteRange(payload.offset + 6uL, payload.length - 6uL)).orThrow()
    }
    private fun ordinary(doc: TiffDocument): List<Triple<UShort, UShort, Bytes?>> = doc.ifds.flatMap { it.entries }
        .filter { it.tag !in setOf(0x8769u.toUShort(), 0x927cu.toUShort()) }
        .map { Triple(it.tag, it.type, it.value) }.sortedBy { it.first.toInt() }
    private fun request(bytes: ByteArray, output: OutputTransaction) = CreateRequest(source(bytes, "ordinary-exif-image"),
        source(GoogleFixtures.video().bytes, "ordinary-exif-video"), ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-mp4")),
        policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = output, context = context)

    @Test fun existingExifGpsOrientationAndUserCommentSurviveStrictCreate(): Unit = runImmediate {
        for (endian in Endian.entries) {
            val input = image(endian)
            val tx = MemoryOutputTransaction(context, "ordinary-exif-$endian")
            val req = request(input, tx)
            val before = document(req.image)
            core.plan(req).orThrow(); assertTrue(tx.query().orThrow().assetIds.isEmpty())
            val result = core.create(req).orThrow()
            val after = document(result.output.assets.first().readableSource!!)
            assertEquals(endian, after.endian); assertEquals(ordinary(before), ordinary(after))
            for (entry in before.ifds.flatMap { it.entries }.filter { it.valueRange!!.length > 4uL }) {
                val copied = after.ifds.flatMap { it.entries }.single { it.tag == entry.tag }
                assertEquals(entry.valueRange!!.offset - before.range.offset, copied.valueRange!!.offset - after.range.offset)
            }
            assertTrue(result.preservation.records.all { it.outcome in setOf(GuaranteeOutcome.Verified, GuaranteeOutcome.NotApplicable) })
            assertEquals(Verdict.Valid, result.validation.verdict)
            assertEquals(true, core.inspect(ReadRequest(SourceSet.Pair(result.output.assets[0].readableSource!!,
                result.output.assets[1].readableSource!!), context)).orThrow().pairing?.matches)
            assertEquals(Bytes(input), BinaryReader(req.image, context).readExactly(0uL, input.size.toUInt()).orThrow())
        }
    }

    @Test fun rootWithoutAnExifPointerGetsANewFormalDirectoryWithoutLosingFields(): Unit = runImmediate {
        for (little in listOf(false, true)) {
            val tx = MemoryOutputTransaction(context, "ordinary-root-$little")
            val req = request(GoogleFixtures.jpeg(OplusFixtures.exifSegment(null, little)), tx)
            val before = document(req.image)
            val result = core.create(req).orThrow()
            val after = document(result.output.assets.first().readableSource!!)
            assertEquals(before.endian, after.endian); assertEquals(ordinary(before), ordinary(after))
            assertTrue(after.firstIfdOffset > before.firstIfdOffset)
        }
    }

    @Test fun privateMakerNoteDuplicateExifAndNonzeroSlackNeverAuthorizeCreate(): Unit = runImmediate {
        val segment = OplusFixtures.exifSegment(null)
        val slack = GoogleFixtures.segment(0xe1, segment.copyOfRange(4, segment.size) + byteArrayOf(0, 7))
        for (input in listOf(AppleFixtures.image(ordinaryNote = true), GoogleFixtures.jpeg(slack), GoogleFixtures.jpeg(segment + segment))) {
            val tx = MemoryOutputTransaction(context, "ordinary-exif-blocked")
            val result = core.create(request(input, tx))
            assertIs<CoreResult.Failure>(result); assertTrue(tx.query().orThrow().assetIds.isEmpty())
        }
    }

    @Test fun modifiedOrdinaryExifBytesInStagingAbortTheWholePair(): Unit = runImmediate {
        val tx = MemoryOutputTransaction(context, "ordinary-exif-tamper")
        val output = object : OutputTransaction by tx {
            override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> {
                val view = tx.openStaged(id).orThrow()
                return CoreResult.Success(object : BinarySource by view {
                    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = attempt {
                        val bytes = view.readAt(offset, length).orThrow().toByteArray()
                        if (id.value == "asset-0" && offset <= 20uL && 20uL - offset < bytes.size.toULong()) {
                            val index = (20uL - offset).toInt()
                            bytes[index] = (bytes[index].toInt() xor 1).toByte()
                        }
                        Bytes(bytes)
                    }
                })
            }
        }
        val result = core.create(request(image(Endian.Big), output))
        assertIs<CoreResult.Failure>(result)
        assertEquals(TransactionState.Aborted, tx.query().orThrow().state)
        assertTrue(tx.committedAssets().isEmpty())
    }
}
