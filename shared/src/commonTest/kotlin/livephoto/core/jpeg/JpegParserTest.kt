package livephoto.core.jpeg

import livephoto.core.*
import livephoto.core.binary.BinaryReader
import livephoto.core.binary.TestSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Handwritten marker streams test structural parsing, not decoder or device compatibility. */
class JpegParserTest {
    private val sos = bytes(0xff, 0xda, 0, 8, 1, 1, 0, 0, 0x3f, 0)
    private fun context(items: ULong = 1000uL): Context = Context(Limits(1024uL, 1024uL, maxItems = items))

    @Test
    fun eoiBytesInsideAppPayloadDoNotEndThePrimaryImage(): Unit = runImmediate {
        val jpeg = bytes(0xff, 0xd8, 0xff, 0xe1, 0, 6, 0x55, 0xff, 0xd9, 0x66) + sos +
            bytes(0x11, 0xff, 0, 0x22, 0xff, 0xd0, 0x33, 0xff, 0xd9)
        val reader = BinaryReader(TestSource(jpeg + bytes(0x77, 0x88)), context())
        val parsed = value(JpegParser.parse(reader))
        assertEquals(ByteRange(0uL, jpeg.size.toULong()), parsed.primary)
        assertEquals(ByteRange(jpeg.size.toULong(), 2uL), parsed.trailing)
        val app = parsed.segments.single { it.marker and 0xff == 0xe1 }
        assertEquals(ByteRange(6uL, 4uL), app.payload)
        assertEquals(Bytes(bytes(0x55, 0xff, 0xd9, 0x66)), value(reader.readExactly(6uL, 4u)))
        assertEquals(1, parsed.scans.size)
    }

    @Test
    fun stuffingRestartFillAndMultipleScansKeepCorrectPrimaryBoundary(): Unit = runImmediate {
        val firstScan = bytes(0x11, 0xff, 0, 0x22, 0xff, 0xd0, 0x33, 0xff, 0xff, 0xd1, 0x44)
        val secondScan = bytes(0x55, 0xff, 0, 0x66)
        val jpeg = bytes(0xff, 0xd8) + sos + firstScan + bytes(0xff, 0xc4, 0, 2) + sos + secondScan + bytes(0xff, 0xd9)
        val parsed = value(JpegParser.parse(BinaryReader(TestSource(jpeg), context())))
        assertEquals(ByteRange(0uL, jpeg.size.toULong()), parsed.primary)
        assertEquals(2, parsed.scans.size)
        assertEquals(ByteRange(12uL, firstScan.size.toULong()), parsed.scans[0])
        assertEquals(ByteRange((12 + firstScan.size + 4 + sos.size).toULong(), secondScan.size.toULong()), parsed.scans[1])
    }

    @Test
    fun multipleAppSegmentsRetainDistinctUnknownPayloads(): Unit = runImmediate {
        val jpeg = bytes(0xff, 0xd8, 0xff, 0xe3, 0, 5, 1, 2, 3, 0xff, 0xe3, 0, 4, 4, 5) + sos + bytes(0x11, 0xff, 0xd9)
        val reader = BinaryReader(TestSource(jpeg), context())
        val parsed = value(JpegParser.parse(reader))
        val apps = parsed.segments.filter { it.marker and 0xff == 0xe3 }
        assertEquals(2, apps.size)
        assertEquals(ByteRange(6uL, 3uL), apps[0].payload)
        assertEquals(ByteRange(13uL, 2uL), apps[1].payload)
        assertEquals(Bytes(bytes(1, 2, 3)), value(reader.readExactly(6uL, 3u)))
        assertEquals(Bytes(bytes(4, 5)), value(reader.readExactly(13uL, 2u)))
    }

    @Test
    fun invalidSegmentLengthsTruncationsAndMissingMarkersFailStructurally(): Unit = runImmediate {
        val malformed = listOf(
            bytes(0, 0xff, 0xd8, 0xff, 0xd9),
            bytes(0xff, 0xd8, 0xff, 0xe1, 0, 0, 0xff, 0xd9),
            bytes(0xff, 0xd8, 0xff, 0xe1, 0, 1, 0xff, 0xd9),
            bytes(0xff, 0xd8, 0xff, 0xe1, 0, 20, 0xff, 0xd9),
            bytes(0xff, 0xd8, 0xff, 0xe1, 0),
            bytes(0xff, 0xd8, 0xff, 2, 0, 2, 0xff, 0xd9),
            bytes(0xff, 0xd8) + sos + bytes(1, 2, 3),
            bytes(0xff, 0xd8) + sos + bytes(1, 0xff),
        )
        for (jpeg in malformed) {
            assertIs<CoreResult.Failure>(JpegParser.parse(BinaryReader(TestSource(jpeg), context())), "Malformed marker stream ${jpeg.size}")
        }
    }

    @Test
    fun markerItemBudgetPreventsUnboundedSegmentLists(): Unit = runImmediate {
        val jpeg = bytes(0xff, 0xd8, 0xff, 0xe1, 0, 2, 0xff, 0xe2, 0, 2, 0xff, 0xe3, 0, 2) + sos + bytes(0x11, 0xff, 0xd9)
        val result = assertIs<CoreResult.Failure>(JpegParser.parse(BinaryReader(TestSource(jpeg), context(items = 2uL))))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), result.error.code)
    }

    @Test
    fun boundedMutationsNeverEscapeAsUncheckedParserExceptions(): Unit = runImmediate {
        val golden = bytes(0xff, 0xd8, 0xff, 0xe3, 0, 4, 1, 2) + sos + bytes(0x11, 0xff, 0, 0x22, 0xff, 0xd9)
        for (mutated in boundedMutations(golden)) {
            when (val parsed = JpegParser.parse(BinaryReader(TestSource(mutated), context()))) {
                is CoreResult.Success -> {
                    kotlin.test.assertTrue(parsed.value.primary.fitsWithin(mutated.size.toULong()))
                    kotlin.test.assertTrue(parsed.value.scans.all { it.fitsWithin(parsed.value.primary.length) })
                }
                is CoreResult.Failure -> kotlin.test.assertTrue(parsed.error.code.value.isNotBlank())
            }
        }
    }

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
