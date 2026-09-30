package livephoto.core.exif

import livephoto.core.*
import livephoto.core.binary.BinaryReader
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class ExifUserCommentTest {
    private val context = Context(Limits(100_000uL, 100_000uL, maxMetadataBytes = 100_000uL))

    @Test
    fun exactAsciiMarkersFollowPrimaryExifPointerInBothByteOrders(): Unit = runImmediate {
        for (little in listOf(true, false)) for (marker in listOf("oplus_10485792", "oplus_8388608")) {
            val comment = value(read(tiff(ascii(marker), little))).comments.single()
            assertEquals(UserCommentEncoding.Ascii, comment.encoding)
            assertEquals(marker, comment.text)
            assertEquals(marker, comment.marker?.text)
            assertEquals(Bytes(ascii(marker)), comment.entry.value)
        }
    }

    @Test
    fun UnicodeRequiresExplicitBomAndPreservesSupplementaryOrdinaryText(): Unit = runImmediate {
        for (little in listOf(true, false)) {
            fun encoded(text: String): ByteArray = "UNICODE\u0000".encodeToByteArray() +
                (if (little) byteArrayOf(0xff.toByte(), 0xfe.toByte()) else byteArrayOf(0xfe.toByte(), 0xff.toByte())) +
                (text + "\u0000").flatMap { character -> if (little) listOf(character.code.toByte(), (character.code ushr 8).toByte()) else listOf((character.code ushr 8).toByte(), character.code.toByte()) }.toByteArray()
            val marker = value(read(tiff(encoded("oplus_10485792"), little))).comments.single()
            assertEquals("oplus_10485792", marker.marker?.text)
            val ordinary = value(read(tiff(encoded("ordinary \uD83D\uDCF7 comment"), little))).comments.single()
            assertEquals("ordinary \uD83D\uDCF7 comment", ordinary.text)
            assertNull(ordinary.marker)
        }
    }

    @Test
    fun mixedTextUnknownCharsetNonUndefinedTypeAndMalformedUnicodeNeverBecomeMarkers(): Unit = runImmediate {
        val cases = listOf(
            ascii("ordinary oplus_10485792 suffix") to 7,
            "UNKNOWN\u0000oplus_10485792".encodeToByteArray() to 7,
            ascii("oplus_10485792") to 2,
            "UNICODE\u0000".encodeToByteArray() + "oplus_10485792".encodeToByteArray() to 7,
            "UNICODE\u0000".encodeToByteArray() + byteArrayOf(0xfe.toByte(), 0xff.toByte(), 0xd8.toByte(), 0, 0, 0) to 7,
        )
        for ((bytes, type) in cases) {
            val comment = value(read(tiff(bytes, type = type))).comments.single()
            assertNull(comment.marker)
            assertEquals(Bytes(bytes), comment.entry.value)
        }
    }

    @Test
    fun duplicateExifPointersAndDuplicateCommentsAreConflictsRatherThanFirstWins(): Unit = runImmediate {
        for (bytes in listOf(tiff(ascii("oplus_10485792"), duplicatePointer = true), tiff(ascii("oplus_10485792"), duplicateComment = true))) {
            assertEquals(IssueCode("CONFLICTING_METADATA"), assertIs<CoreResult.Failure>(read(bytes)).error.code)
        }
    }

    @Test
    fun userCommentPlacedInIfdZeroHasNoStandardizedMarkerAuthority(): Unit = runImmediate {
        val bytes = tiff(ascii("oplus_10485792"))
        // A correctly encoded comment in IFD0 is not the standardized ExifIFD.UserComment.
        bytes[10] = 0x86.toByte(); bytes[11] = 0x92.toByte()
        bytes[12] = 7
        bytes[14] = ascii("oplus_10485792").size.toByte()
        bytes[18] = 44
        assertTrue(value(read(bytes)).comments.isEmpty())
    }

    @Test
    fun commentParsingHonorsSharedMetadataBudgetAndCancellation(): Unit = runImmediate {
        val bytes = tiff(ascii("oplus_10485792"))
        val low = Context(Limits(100_000uL, 100_000uL, maxMetadataBytes = 8uL))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(read(bytes, low)).error.code)
        val cancelled = context.copy(cancellation = Cancellation { true })
        assertEquals(IssueCode("CANCELLED"), assertIs<CoreResult.Failure>(read(bytes, cancelled)).error.code)
    }

    private suspend fun read(bytes: ByteArray, context: Context = this.context): CoreResult<ExifCommentFacts> =
        ExifUserCommentReader(BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("standalone-user-comment")), context)).read(ByteRange(0uL, bytes.size.toULong()))
    private fun ascii(text: String) = ("ASCII\u0000\u0000\u0000" + text + "\u0000").encodeToByteArray()
    private fun tiff(comment: ByteArray, little: Boolean = true, type: Int = 7, duplicatePointer: Boolean = false, duplicateComment: Boolean = false): ByteArray {
        val roots = if (duplicatePointer) 2 else 1
        val comments = if (duplicateComment) 2 else 1
        val child = 8 + 2 + roots * 12 + 4
        val payload = child + 2 + comments * 12 + 4
        val bytes = ByteArray(payload + comment.size)
        fun number(offset: Int, value: Int, width: Int) { repeat(width) { index -> bytes[offset + index] = (value ushr (if (little) index * 8 else (width - index - 1) * 8)).toByte() } }
        fun entry(offset: Int, tag: Int, fieldType: Int, count: Int, value: Int) { number(offset, tag, 2); number(offset + 2, fieldType, 2); number(offset + 4, count, 4); number(offset + 8, value, 4) }
        bytes[0] = (if (little) 0x49 else 0x4d).toByte(); bytes[1] = bytes[0]
        number(2, 42, 2); number(4, 8, 4); number(8, roots, 2)
        repeat(roots) { entry(10 + it * 12, 0x8769, 4, 1, child) }
        number(child, comments, 2)
        repeat(comments) { entry(child + 2 + it * 12, 0x9286, type, comment.size, payload) }
        comment.copyInto(bytes, payload)
        return bytes
    }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
