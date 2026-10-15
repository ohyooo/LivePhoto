package livephoto.core.exif

import livephoto.core.*
import livephoto.core.binary.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TiffReaderTest {
    private fun context(): Context = Context(Limits(100_000uL, 100_000uL))

    @Test
    fun littleEndianOffsetsAreRelativeToTiffEvenInsideALargerSource(): Unit = runImmediate {
        val comment = "ASCII\u0000\u0000\u0000Motion ordinary text".encodeToByteArray()
        val tiff = littleTiff(0x9286, 7, comment.size.toUInt(), little32(26u)) + comment
        val prefix = ByteArray(12) { 0x55 }
        val reader = BinaryReader(TestSource(prefix + tiff + byteArrayOf(0x66)), context())
        val document = value(TiffReader(reader).read(ByteRange(12uL, tiff.size.toULong())))
        assertEquals(Endian.Little, document.endian)
        val entry = document.ifds.single().entries.single()
        assertEquals(0x9286u.toUShort(), entry.tag)
        assertEquals(ByteRange(38uL, comment.size.toULong()), entry.valueRange)
        assertEquals(Bytes(comment), entry.value)
        assertEquals(Bytes(little32(26u)), entry.rawValueField)
    }

    @Test
    fun bigEndianInlineAsciiRetainsItsExactBytes(): Unit = runImmediate {
        val tiff = bytes(0x4d, 0x4d, 0, 42, 0, 0, 0, 8, 0, 1, 1, 0x0e, 0, 2, 0, 0, 0, 4, 0x61, 0x62, 0x63, 0, 0, 0, 0, 0)
        val document = value(TiffReader(BinaryReader(TestSource(tiff), context())).read(ByteRange(0uL, tiff.size.toULong())))
        assertEquals(Endian.Big, document.endian)
        assertEquals(ByteRange(18uL, 4uL), document.ifds.single().entries.single().valueRange)
        assertEquals(Bytes(bytes(0x61, 0x62, 0x63, 0)), document.ifds.single().entries.single().value)
    }

    @Test
    fun unknownTypesKeepRawFieldsWithoutInterpretingTheirHugeCountOrOffset(): Unit = runImmediate {
        val field = bytes(0xff, 0xff, 0xff, 0xff)
        val tiff = littleTiff(0x1234, 0xffff, UInt.MAX_VALUE, field)
        val entry = value(TiffReader(BinaryReader(TestSource(tiff), context())).read(ByteRange(0uL, tiff.size.toULong()))).ifds.single().entries.single()
        assertEquals(UInt.MAX_VALUE, entry.count)
        assertEquals(Bytes(field), entry.rawValueField)
        assertEquals(null, entry.value)
        assertEquals(null, entry.valueRange)
    }

    @Test
    fun makerNotePrivateOffsetsRemainOpaqueBytes(): Unit = runImmediate {
        val note = bytes(0x49, 0x49, 0xff, 0xff, 0xff, 0xff, 0x99, 0x77)
        val tiff = littleTiff(0x927c, 7, note.size.toUInt(), little32(26u)) + note
        val entry = value(TiffReader(BinaryReader(TestSource(tiff), context())).read(ByteRange(0uL, tiff.size.toULong()))).ifds.single().entries.single()
        assertTrue(entry.isOpaqueMakerNote)
        assertEquals(Bytes(note), entry.value)
    }

    @Test
    fun ifdCyclesInvalidPointersAndValuesOutsideTheTiffParentAreRejected(): Unit = runImmediate {
        val cyclicNext = bytes(0x49, 0x49, 42, 0, 8, 0, 0, 0, 0, 0, 8, 0, 0, 0)
        val pointerToSelf = littleTiff(0x8769, 4, 1u, little32(8u))
        val oversizedValue = littleTiff(0x1234, 12, UInt.MAX_VALUE, little32(26u))
        val corruptFirst = bytes(0x49, 0x49, 42, 0, 0xff, 0xff, 0xff, 0x7f)
        for (input in listOf(cyclicNext, pointerToSelf, oversizedValue, corruptFirst, bytes(0x49, 0x49, 42))) {
            assertIs<CoreResult.Failure>(TiffReader(BinaryReader(TestSource(input), context())).read(ByteRange(0uL, input.size.toULong())))
        }
        val base = littleTiff(0x1234, 7, 8u, little32(26u))
        // Provider bytes exist after the TIFF parent, but the value cannot borrow them.
        assertIs<CoreResult.Failure>(TiffReader(BinaryReader(TestSource(base + ByteArray(8)), context())).read(ByteRange(0uL, base.size.toULong())))
    }

    @Test
    fun retainedMetadataAndItemBudgetsAlsoApplyToIfdModels(): Unit = runImmediate {
        val tiff = littleTiff(0x1234, 1, 1u, bytes(7, 0, 0, 0))
        val limited = context().copy(limits = Limits(100_000uL, 100_000uL, maxMetadataBytes = 16uL))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(TiffReader(BinaryReader(TestSource(tiff), limited)).read(ByteRange(0uL, tiff.size.toULong()))).error.code)
        val oneItem = context().copy(limits = Limits(100_000uL, 100_000uL, maxItems = 1uL))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(TiffReader(BinaryReader(TestSource(tiff), oneItem)).read(ByteRange(0uL, tiff.size.toULong()))).error.code)
    }

    @Test
    fun bigTiffIsExplicitlyUnsupportedWhileAnInvalidMagicIsCorrupted(): Unit = runImmediate {
        val bigTiff = bytes(0x49, 0x49, 43, 0, 8, 0, 0, 0)
        assertEquals(IssueCode("UNSUPPORTED_CONTAINER"), assertIs<CoreResult.Failure>(TiffReader(BinaryReader(TestSource(bigTiff), context())).read(ByteRange(0uL, 8uL))).error.code)
        val invalid = bytes(0x49, 0x49, 41, 0, 8, 0, 0, 0)
        assertEquals(IssueCode("CORRUPTED_CONTAINER"), assertIs<CoreResult.Failure>(TiffReader(BinaryReader(TestSource(invalid), context())).read(ByteRange(0uL, 8uL))).error.code)
    }

    @Test
    fun boundedMutationInputsReturnResultsInsteadOfUncheckedExceptions(): Unit = runImmediate {
        val golden = littleTiff(0x1234, 1, 1u, bytes(7, 0, 0, 0))
        for (input in boundedMutations(golden)) {
            when (val parsed = TiffReader(BinaryReader(TestSource(input), context())).read(ByteRange(0uL, input.size.toULong()))) {
                is CoreResult.Success -> assertTrue(parsed.value.range.fitsWithin(input.size.toULong()))
                is CoreResult.Failure -> assertTrue(parsed.error.code.value.isNotBlank())
            }
        }
    }

    private fun littleTiff(tag: Int, type: Int, count: UInt, raw: ByteArray): ByteArray =
        bytes(0x49, 0x49, 42, 0, 8, 0, 0, 0, 1, 0, tag and 255, tag ushr 8, type and 255, type ushr 8) +
            little32(count) + raw + ByteArray(4)
    private fun little32(number: UInt): ByteArray = ByteArray(4) { (number shr (it * 8)).toByte() }
    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
