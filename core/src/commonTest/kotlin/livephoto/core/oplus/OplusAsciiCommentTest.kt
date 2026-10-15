package livephoto.core.oplus

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.fractionalAudioVideo
import livephoto.core.exif.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.jpeg.*
import livephoto.core.memory.*
import kotlin.test.*

/** Artificial TIFF tables, opaque bytes and JPEG framing; no device image or private metadata. */
class OplusAsciiCommentTest {
    private val context = Context(Limits(2_000_000u, 2_000_000u, maxMetadataBytes = 8_000_000u))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail"))
    private val permission = MutationPolicy(loss = LossPolicy.AllowListed, allowedLosses = listOf(MetadataLoss.OplusAsciiCameraUserComment))
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun <T> success(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result, result.toString()).value

    @Test fun asciiReplacementAppendsOnlyOneValueAndPreservesOpaqueOffsetsBothEndian(): Unit = runImmediate {
        for (little in listOf(true, false)) roundTrip(tiff(little), little)
    }

    @Test fun defaultOldValueAndSimilarTextDoNotAuthorizeThisReplacement(): Unit = runImmediate {
        rejected(tiff(), MutationPolicy(), "CONFLICTING_METADATA")
        rejected(tiff(), MutationPolicy(loss = LossPolicy.AllowListed, allowedLosses = listOf(MetadataLoss.OplusCameraUserComment)), "CONFLICTING_METADATA")
        for (text in listOf("oplus_1056769", "ordinary text", "oplus_10485792")) {
            val b = tiff().also { it.fill(0, 112, 126); (text + "\u0000").encodeToByteArray().copyInto(it, 112) }
            // The longer live-looking text remains ordinary TIFF ASCII, not marker authority.
            if (text.length == 14) put(b, 50, 15, 4, true)
            rejected(b, permission, "CONFLICTING_METADATA")
        }
    }

    @Test fun overlappingValuesEntryAliasesAndUnknownAllocationsCannotBeRewritten(): Unit = runImmediate {
        for (offset in listOf(112, 48)) rejected(tiff().also { put(it, 66, offset, 4, true) }, permission, "UNSAFE_METADATA_REWRITE")
        rejected(tiff().also { put(it, 60, 99, 2, true) }, permission, "UNSAFE_METADATA_REWRITE")
    }

    @Test fun appendHonorsTheExifAppLimitIncludingAlignment(): Unit = runImmediate {
        roundTrip(tiff().copyOf(65504), true)
        for (length in listOf(65505, 65506, 65520)) rejected(tiff().copyOf(length), permission, "UNSAFE_METADATA_REWRITE")
    }

    @Test fun replacingAsciiMetadataCannotSatisfyStrictOrRequiredMetadataGuarantee(): Unit = runImmediate {
        for (policy in listOf(
            MutationPolicy(preservation = PreservationPolicy.Strict, loss = LossPolicy.AllowListed, allowedLosses = permission.allowedLosses),
            MutationPolicy(requiredGuarantees = listOf(Guarantee.MetadataPreserving), loss = LossPolicy.AllowListed, allowedLosses = permission.allowedLosses),
        )) rejected(tiff(), policy, "PRESERVATION_REQUIREMENT_FAILED")
    }

    @Test fun anEarlierXmpResizeStillCannotMovePinnedExif(): Unit = runImmediate {
        val xmp = GoogleFixtures.xmpSegment("<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about=''/></rdf:RDF>")
        val output = MemoryOutputTransaction(context, "ascii-pinned")
        val image = source(GoogleFixtures.jpeg(xmp + segment(tiff())), "ascii-before-xmp")
        val video = source(GoogleFixtures.video().bytes, "ascii-video")
        try {
            assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(core.create(CreateRequest(image, video, target, policy = permission, output = output, context = context))).error.code.value)
            assertTrue(output.committedAssets().isEmpty())
        } finally { image.close(); video.close() }
    }

    private suspend fun roundTrip(tiff: ByteArray, little: Boolean) {
        val suffix = byteArrayOf(3, 5, 7, 11)
        val input = GoogleFixtures.jpeg(segment(tiff)) + suffix
        val movie = fractionalAudioVideo(videoVariance = true)
        val image = source(input, "ascii-image")
        val video = source(movie, "ascii-video")
        val output = MemoryOutputTransaction(context, "ascii-output")
        var created: OperationResult? = null
        var extracted: OperationResult? = null
        try {
            assertEquals(Disposition.NonLive, success(core.detect(ReadRequest(SourceSet.Single(image), context))).disposition)
            created = success(core.create(CreateRequest(image, video, target, policy = permission, output = output, context = context)))
            val reader = BinaryReader(assertNotNull(created.output.assets.single().readableSource), context)
            val jpeg = success(JpegParser.parse(reader))
            val app = jpeg.segments.single { it.payloadKind == AppPayloadKind.Exif }
            assertEquals(2uL, app.range.offset)
            assertEquals((tiff.size + 22 + 10).toULong(), app.range.length)
            val range = ByteRange(assertNotNull(app.payload).offset + 6u, (tiff.size + 22).toULong())
            val actual = success(reader.readExactly(range.offset, range.length.toUInt())).toByteArray()
            for (i in tiff.indices) if (i !in 48..57 && i !in 112..125) assertEquals(tiff[i], actual[i], "Unrequested TIFF byte $i changed")
            assertTrue(actual.copyOfRange(112, 126).all { it == 0.toByte() })
            val comment = success(ExifUserCommentReader(reader).read(range)).comments.single()
            assertEquals(OplusCommentMarker.Modern, comment.marker)
            assertEquals(7u.toUShort(), comment.entry.type)
            assertEquals(22u, comment.entry.count)
            assertEquals(tiff.size.toULong(), readUnsigned(comment.entry.rawValueField, if (little) Endian.Little else Endian.Big))
            assertEquals(Value.Text("oplus_1056768"), created.preservation.changes.single { it.selector == "exif:UserComment" }.before)
            assertEquals(GuaranteeOutcome.Changed, created.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }.outcome)
            assertTrue(created.execution.none { it.transcoded || it.remuxed })
            val bytes = output.committedAssets().values.single().toByteArray()
            assertContentEquals(suffix, bytes.copyOfRange(bytes.size - movie.size - suffix.size, bytes.size - movie.size))
            val source = SourceSet.Single(reader.source)
            val validation = success(core.validate(ValidationRequest(source, layers = listOf(Layer.Structure, Layer.Protocol), context = context)))
            assertEquals(Coverage.Complete, validation.coverage)
            assertNotEquals(Verdict.Invalid, validation.verdict)
            val extraction = MemoryOutputTransaction(context, "ascii-extract")
            extracted = success(core.extract(ExtractRequest(source, emptyList(), output = extraction, context = context)))
            assertEquals(Bytes(movie), extraction.committedAssets().values.single())
            assertEquals(Bytes(input), success(image.readAt(0u, input.size.toUInt())))
            assertEquals(Bytes(movie), success(video.readAt(0u, movie.size.toUInt())))
        } finally {
            extracted?.output?.assets?.forEach { it.readableSource?.close() }
            created?.output?.assets?.forEach { it.readableSource?.close() }
            image.close(); video.close()
        }
    }

    private suspend fun rejected(tiff: ByteArray, policy: MutationPolicy, code: String) {
        val input = GoogleFixtures.jpeg(segment(tiff))
        val image = source(input, "rejected-ascii-image")
        val video = source(GoogleFixtures.video().bytes, "rejected-ascii-video")
        val output = MemoryOutputTransaction(context, "rejected-ascii")
        try {
            assertEquals(code, assertIs<CoreResult.Failure>(core.create(CreateRequest(image, video, target, policy = policy, output = output, context = context))).error.code.value)
            assertTrue(output.committedAssets().isEmpty())
            assertEquals(TransactionState.Open, success(output.query()).state)
            assertEquals(Bytes(input), success(image.readAt(0u, input.size.toUInt())))
        } finally { image.close(); video.close() }
    }

    private fun segment(tiff: ByteArray) = GoogleFixtures.segment(0xe1, "Exif\u0000\u0000".encodeToByteArray() + tiff)
    private fun tiff(little: Boolean = true): ByteArray {
        val b = ByteArray(192)
        b[0] = if (little) 0x49 else 0x4d; b[1] = b[0]
        fun entry(at: Int, tag: Int, type: Int, count: Int, value: Int) {
            put(b, at, tag, 2, little); put(b, at + 2, type, 2, little); put(b, at + 4, count, 4, little); put(b, at + 8, value, 4, little)
        }
        put(b, 2, 42, 2, little); put(b, 4, 8, 4, little); put(b, 8, 2, 2, little)
        entry(10, 0x010e, 2, 8, 180); entry(22, 0x8769, 4, 1, 44)
        put(b, 44, 3, 2, little); entry(46, 0x9286, 2, 14, 112)
        entry(58, 0x927c, 7, 8, 144); entry(70, 0xa005, 4, 1, 160)
        "oplus_1056768\u0000".encodeToByteArray().copyInto(b, 112)
        byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).copyInto(b, 144)
        put(b, 160, 1, 2, little); entry(162, 1, 2, 4, 0)
        "R98\u0000".encodeToByteArray().copyInto(b, 170)
        "keep-me\u0000".encodeToByteArray().copyInto(b, 180)
        return b
    }
    private fun put(b: ByteArray, at: Int, value: Int, width: Int, little: Boolean) {
        repeat(width) { b[at + it] = (value ushr (if (little) it * 8 else (width - it - 1) * 8)).toByte() }
    }
}
