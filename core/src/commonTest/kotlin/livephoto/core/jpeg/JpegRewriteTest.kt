package livephoto.core.jpeg

import livephoto.core.*
import livephoto.core.binary.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JpegRewriteTest {
    private val context = Context(Limits(1_000_000uL, 1_000_000uL))
    private val soi = bytes(0xff, 0xd8)
    private val imageTail = bytes(0xff, 0xda, 0, 8, 1, 1, 0, 0, 0x3f, 0, 0x11, 0xff, 0, 0x22, 0xff, 0xd9)

    @Test
    fun appEncodingMatchesHandwrittenLengthAndPayloadBytes() {
        assertEquals(Bytes(bytes(0xff, 0xe1, 0, 5, 1, 2, 3)), value(JpegRewrite.appSegment(0xe1, Bytes(bytes(1, 2, 3)))))
        assertIs<CoreResult.Failure>(JpegRewrite.appSegment(0xda, Bytes(byteArrayOf())))
        assertIs<CoreResult.Failure>(JpegRewrite.appSegment(0xe1, Bytes(ByteArray(65_534))))
    }

    @Test
    fun safeAppRewritePreservesUnknownSegmentsAndEntropyBytesExactly(): Unit = runImmediate {
        val changedApp = bytes(0xff, 0xe3, 0, 4, 1, 2)
        val untouchedApp = bytes(0xff, 0xe4, 0, 5, 0x77, 0x88, 0x99)
        val original = soi + changedApp + untouchedApp + imageTail
        val originalCopy = original.copyOf()
        val replacement = bytes(0xff, 0xe1, 0, 5, 3, 4, 5)
        val reader = BinaryReader(TestSource(original), context)
        val structure = value(JpegParser.parse(reader))
        val plan = value(JpegRewrite.plan(structure, listOf(JpegPatch(ByteRange(2uL, 6uL), Bytes(replacement)))))
        val sink = TestSink(maxChunk = 1)
        value(JpegRewrite.write(reader, BinaryWriter(sink, context), structure, plan, context))
        assertEquals((soi + replacement + untouchedApp + imageTail).toList(), sink.written)
        assertEquals(originalCopy.toList(), original.toList())
    }

    @Test
    fun invalidPatchRangesOverlapAndReplacementLengthsAreRejected(): Unit = runImmediate {
        val jpeg = soi + bytes(0xff, 0xe3, 0, 4, 1, 2) + imageTail
        val structure = value(JpegParser.parse(BinaryReader(TestSource(jpeg), context)))
        val remove = JpegPatch(ByteRange(2uL, 6uL), Bytes(byteArrayOf()))
        assertIs<CoreResult.Failure>(JpegRewrite.plan(structure, listOf(remove, remove)))
        assertIs<CoreResult.Failure>(JpegRewrite.plan(structure, listOf(JpegPatch(ByteRange(3uL, 3uL), Bytes(byteArrayOf())))))
        assertIs<CoreResult.Failure>(JpegRewrite.plan(structure, listOf(JpegPatch(ByteRange(2uL, 6uL), Bytes(bytes(0xff, 0xe1, 0, 9, 1))))))
        assertIs<CoreResult.Failure>(JpegRewrite.plan(structure, listOf(JpegPatch(ByteRange(0uL, 2uL), Bytes(byteArrayOf())))))
    }

    @Test
    fun tamperedPlanCannotWriteBeforeItsLengthIsRechecked(): Unit = runImmediate {
        val jpeg = soi + imageTail
        val reader = BinaryReader(TestSource(jpeg), context)
        val structure = value(JpegParser.parse(reader))
        val plan = value(JpegRewrite.plan(structure, emptyList()))
        val sink = TestSink()
        assertIs<CoreResult.Failure>(JpegRewrite.write(reader, BinaryWriter(sink, context), structure, plan.copy(outputLength = plan.outputLength + 1uL), context))
        assertEquals(0, sink.calls)
    }

    @Test
    fun resizingWithMpfDependenciesIsRejectedUntilRelocationExists(): Unit = runImmediate {
        val jpeg = soi + app(0xe2, "MPF\u0000".encodeToByteArray() + ByteArray(4)) + imageTail
        val structure = value(JpegParser.parse(BinaryReader(TestSource(jpeg), context)))
        val failure = assertIs<CoreResult.Failure>(JpegRewrite.plan(structure, listOf(JpegPatch(ByteRange(2uL, 0uL), Bytes(bytes(0xff, 0xe3, 0, 2))))))
        assertEquals(IssueCode("GAINMAP_PRESERVATION_UNAVAILABLE"), failure.error.code)
    }

    @Test
    fun compensatingAppResizesStillMoveMpfAndRequireVerifiedRelocation(): Unit = runImmediate {
        val before = app(0xe3, bytes(1, 2))
        val mpf = app(0xe2, "MPF\u0000".encodeToByteArray() + ByteArray(4))
        val after = app(0xe4, bytes(3, 4))
        val structure = value(JpegParser.parse(BinaryReader(TestSource(soi + before + mpf + after + imageTail), context)))
        val growBySix = app(0xe3, bytes(1, 2, 3, 4, 5, 6, 7, 8))
        val patches = listOf(
            JpegPatch(ByteRange(2uL, before.size.toULong()), Bytes(growBySix)),
            JpegPatch(ByteRange((2 + before.size + mpf.size).toULong(), after.size.toULong()), Bytes(byteArrayOf())),
        )
        // +6 before MPF and -6 after it leaves total length unchanged but shifts MPF's base.
        assertEquals(before.size + after.size, growBySix.size)
        assertIs<CoreResult.Failure>(JpegRewrite.plan(structure, patches))
    }

    @Test
    fun equalLengthReplacementCannotRemoveProtectedMpfOrUnparsedExifBindings(): Unit = runImmediate {
        for (protected in listOf(
            app(0xe2, "MPF\u0000".encodeToByteArray() + ByteArray(4)),
            app(0xe1, "Exif\u0000\u0000".encodeToByteArray() + ByteArray(8)),
        )) {
            val structure = value(JpegParser.parse(BinaryReader(TestSource(soi + protected + imageTail), context)))
            val ordinary = app(0xe3, ByteArray(protected.size - 4))
            assertEquals(protected.size, ordinary.size)
            assertIs<CoreResult.Failure>(JpegRewrite.plan(structure, listOf(JpegPatch(ByteRange(2uL, protected.size.toULong()), Bytes(ordinary)))))
        }
    }

    @Test
    fun unreassembledExtendedXmpBlocksStandardPacketRewrite(): Unit = runImmediate {
        val standard = app(0xe1, "http://ns.adobe.com/xap/1.0/\u0000<x/>".encodeToByteArray())
        val extended = app(0xe1, "http://ns.adobe.com/xmp/extension/\u0000unknown".encodeToByteArray())
        val structure = value(JpegParser.parse(BinaryReader(TestSource(soi + standard + extended + imageTail), context)))
        val failure = assertIs<CoreResult.Failure>(JpegRewrite.plan(structure, listOf(JpegPatch(ByteRange(2uL, standard.size.toULong()), Bytes(byteArrayOf())))))
        assertEquals(IssueCode("UNSAFE_METADATA_REWRITE"), failure.error.code)
    }

    @Test
    fun unverifiedExifIsNotRelocatedByEarlierMetadataResizing(): Unit = runImmediate {
        val unknown = bytes(0xff, 0xe3, 0, 4, 1, 2)
        val exif = app(0xe1, "Exif\u0000\u0000".encodeToByteArray() + ByteArray(8))
        val structure = value(JpegParser.parse(BinaryReader(TestSource(soi + unknown + exif + imageTail), context)))
        val failure = assertIs<CoreResult.Failure>(JpegRewrite.plan(structure, listOf(JpegPatch(ByteRange(2uL, unknown.size.toULong()), Bytes(byteArrayOf())))))
        assertEquals(IssueCode("UNSAFE_METADATA_REWRITE"), failure.error.code)
    }

    private fun app(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return bytes(0xff, marker, length ushr 8, length and 255) + payload
    }
    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
