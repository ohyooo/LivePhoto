package livephoto.core.oplus

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.fractionalAudioVideo
import livephoto.core.exif.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.jpeg.*
import livephoto.core.memory.*
import kotlin.test.*

class OplusAuthorizedCommentTest {
    private val context = Context(Limits(2_000_000u, 2_000_000u))
    private val core = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail"))
    private val permission = MutationPolicy(loss = LossPolicy.AllowListed, allowedLosses = listOf(MetadataLoss.OplusCameraUserComment))
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun <T> success(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result, result.toString()).value

    @Test fun authorizedReplacementKeepsExifPositionAllOtherBytesTailAndOriginalMovie(): Unit = runImmediate {
        for (little in listOf(true, false)) for (terminated in listOf(true, false)) {
            val tiff = tiff(little, terminated)
            val suffix = ByteArray(435) { ((it * 37 + 11) and 255).toByte() }
            val imageBytes = image(tiff) + suffix
            val movie = fractionalAudioVideo(videoVariance = true)
            val image = source(imageBytes, "camera-image")
            val video = source(movie, "camera-video")
            val output = MemoryOutputTransaction(context, "camera-comment")
            var created: OperationResult? = null
            var extracted: OperationResult? = null
            try {
                assertEquals(Disposition.NonLive, success(core.detect(ReadRequest(SourceSet.Single(image), context))).disposition)
                created = success(core.create(CreateRequest(image, video, target, policy = permission, output = output, context = context)))
                val bytes = output.committedAssets().values.single().toByteArray()
                val reader = BinaryReader(assertNotNull(created.output.assets.single().readableSource), context)
                val jpeg = success(JpegParser.parse(reader))
                val exif = jpeg.segments.single { it.payloadKind == AppPayloadKind.Exif }
                assertEquals(2uL, exif.range.offset)
                assertEquals((tiff.size + 10).toULong(), exif.range.length)
                val range = ByteRange(assertNotNull(exif.payload).offset + 6u, tiff.size.toULong())
                val actual = success(reader.readExactly(range.offset, tiff.size.toUInt())).toByteArray()
                for (i in tiff.indices) if (i !in 32..35 && i !in 80..101) assertEquals(tiff[i], actual[i], "Unrequested TIFF byte $i changed")
                val comment = success(ExifUserCommentReader(reader).read(range)).comments.single()
                assertEquals(OplusCommentMarker.Modern, comment.marker)
                assertEquals(22u, comment.entry.count)
                assertContentEquals(suffix, bytes.copyOfRange(bytes.size - movie.size - suffix.size, bytes.size - movie.size))
                assertContentEquals(movie, bytes.takeLast(movie.size).toByteArray())
                val change = created.preservation.changes.single { it.selector == "exif:UserComment" }
                assertEquals(Value.Text("oplus_1048576"), change.before)
                assertEquals(Value.Text("oplus_10485792"), change.after)
                assertTrue(change.requested)
                assertEquals(GuaranteeOutcome.Changed, created.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }.outcome)
                assertTrue(created.execution.none { it.transcoded || it.remuxed })
                val input = SourceSet.Single(reader.source)
                assertEquals(ProtocolIds.Oplus, success(core.detect(ReadRequest(input, context))).primaryProtocol?.protocol)
                val validation = success(core.validate(ValidationRequest(input, layers = listOf(Layer.Structure, Layer.Protocol), context = context)))
                assertEquals(Coverage.Complete, validation.coverage)
                assertEquals(Verdict.Warning, validation.verdict)
                val extraction = MemoryOutputTransaction(context, "camera-extract")
                extracted = success(core.extract(ExtractRequest(input, emptyList(), output = extraction, context = context)))
                assertEquals(Bytes(movie), extraction.committedAssets().values.single())
                assertEquals(Bytes(imageBytes), success(image.readAt(0u, imageBytes.size.toUInt())))
                assertEquals(Bytes(movie), success(video.readAt(0u, movie.size.toUInt())))
            } finally {
                extracted?.output?.assets?.forEach { it.readableSource?.close() }
                created?.output?.assets?.forEach { it.readableSource?.close() }
                image.close(); video.close()
            }
        }
    }

    @Test fun defaultAndUnrelatedPermissionsDoNotAuthorizeOrdinaryCommentLoss(): Unit = runImmediate {
        for (policy in listOf(MutationPolicy(), MutationPolicy(loss = LossPolicy.AllowListed, allowedLosses = listOf("exif:UserComment"))))
            rejected(image(tiff()), policy, "CONFLICTING_METADATA")
        val other = tiff().also { "oplus_1048577".encodeToByteArray().copyInto(it, 88) }
        rejected(image(other), permission, "CONFLICTING_METADATA")
    }

    @Test fun nonzeroOrMissingAlignmentCannotBeConsumed(): Unit = runImmediate {
        rejected(image(tiff().also { it[101] = 7 }), permission, "UNSAFE_METADATA_REWRITE")
        // A recognized value can still have no space in the TIFF allocation.
        val tight = OplusFixtures.exifSegment("oplus_1048576").let { segment ->
            segment.copyOf(segment.size - 1).also { bytes ->
                val size = bytes.size - 2
                bytes[2] = (size ushr 8).toByte(); bytes[3] = size.toByte()
                // Little-endian UserComment count in the final ExifIFD entry.
                bytes[10 + 62 + 2 + 4] = 21
            }
        }
        rejected(GoogleFixtures.jpeg(tight), permission, "UNSAFE_METADATA_REWRITE")
    }

    @Test fun aliasesAndUnknownAllocationsStillPreventReplacement(): Unit = runImmediate {
        for (offset in listOf(80, 32, 101)) {
            val bytes = tiff().also { put(it, 48, offset, 4, true) }
            rejected(image(bytes), permission, "UNSAFE_METADATA_REWRITE")
        }
        rejected(image(tiff().also { put(it, 42, 99, 2, true) }), permission, "UNSAFE_METADATA_REWRITE")
    }

    @Test fun explicitReplacementCannotClaimStrictMetadataPreservation(): Unit = runImmediate {
        for (policy in listOf(
            MutationPolicy(preservation = PreservationPolicy.Strict, loss = LossPolicy.AllowListed, allowedLosses = listOf(MetadataLoss.OplusCameraUserComment)),
            MutationPolicy(requiredGuarantees = listOf(Guarantee.MetadataPreserving), loss = LossPolicy.AllowListed, allowedLosses = listOf(MetadataLoss.OplusCameraUserComment)),
        )) rejected(image(tiff()), policy, "PRESERVATION_REQUIREMENT_FAILED")
    }

    @Test fun resizingEarlierXmpCannotRelocateThePinnedExif(): Unit = runImmediate {
        val xmp = GoogleFixtures.xmpSegment("<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about=''/></rdf:RDF>")
        val bytes = image(tiff())
        rejected(bytes.copyOfRange(0, 2) + xmp + bytes.copyOfRange(2, bytes.size), permission, "UNSAFE_METADATA_REWRITE")
    }

    private suspend fun rejected(bytes: ByteArray, policy: MutationPolicy, code: String) {
        val image = source(bytes, "rejected-image")
        val video = source(GoogleFixtures.video().bytes, "rejected-video")
        val output = MemoryOutputTransaction(context, "rejected-comment")
        try {
            val failure = assertIs<CoreResult.Failure>(core.create(CreateRequest(image, video, target, policy = policy, output = output, context = context)))
            assertEquals(code, failure.error.code.value, failure.toString())
            assertTrue(output.committedAssets().isEmpty())
            assertEquals(TransactionState.Open, success(output.query()).state)
            assertEquals(Bytes(bytes), success(image.readAt(0u, bytes.size.toUInt())))
        } finally { image.close(); video.close() }
    }

    private fun image(tiff: ByteArray) = GoogleFixtures.jpeg(GoogleFixtures.segment(0xe1, "Exif\u0000\u0000".encodeToByteArray() + tiff))
    /** Synthetic opaque MakerNote + interoperability directory, with one byte of alignment. */
    private fun tiff(little: Boolean = true, terminated: Boolean = false): ByteArray {
        val b = ByteArray(160)
        b[0] = if (little) 0x49 else 0x4d; b[1] = b[0]
        put(b, 2, 42, 2, little); put(b, 4, 8, 4, little); put(b, 8, 1, 2, little)
        fun entry(at: Int, tag: Int, type: Int, count: Int, value: Int) {
            put(b, at, tag, 2, little); put(b, at + 2, type, 2, little); put(b, at + 4, count, 4, little); put(b, at + 8, value, 4, little)
        }
        entry(10, 0x8769, 4, 1, 26); put(b, 26, 3, 2, little)
        entry(28, 0x9286, 7, if (terminated) 22 else 21, 80)
        entry(40, 0x927c, 7, 8, 104); entry(52, 0xa005, 4, 1, 120)
        "ASCII\u0000\u0000\u0000oplus_1048576".encodeToByteArray().copyInto(b, 80)
        byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).copyInto(b, 104)
        put(b, 120, 1, 2, little); entry(122, 1, 2, 4, 0)
        "R98\u0000".encodeToByteArray().copyInto(b, 130)
        return b
    }
    private fun put(b: ByteArray, at: Int, value: Int, width: Int, little: Boolean) {
        repeat(width) { b[at + it] = (value ushr (if (little) it * 8 else (width - it - 1) * 8)).toByte() }
    }
}
