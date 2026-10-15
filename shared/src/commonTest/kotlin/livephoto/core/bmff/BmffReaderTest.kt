package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BmffReaderTest {
    private fun context(): Context = Context(Limits(100_000uL, 100_000uL))
    private fun parser(input: ByteArray, context: Context = context()): BmffReader = BmffReader(BinaryReader(TestSource(input), context))

    @Test
    fun extendedSizeAndUuidHeadersHaveIndependentGoldenBoundaries(): Unit = runImmediate {
        val extended = bytes(0, 0, 0, 1) + "free".encodeToByteArray() + bytes(0, 0, 0, 0, 0, 0, 0, 20) + bytes(1, 2, 3, 4)
        val uuid = bytes(0, 0, 0, 26) + "uuid".encodeToByteArray() + ByteArray(16) { it.toByte() } + bytes(5, 6)
        val boxes = value(parser(extended + uuid).readBoxes(ByteRange(0uL, 46uL)))
        assertEquals(ByteRange(0uL, 20uL), boxes[0].range)
        assertEquals(16uL, boxes[0].headerLength)
        assertEquals(ByteRange(16uL, 4uL), boxes[0].payload)
        assertEquals(ByteRange(20uL, 26uL), boxes[1].range)
        assertEquals(24uL, boxes[1].headerLength)
        assertEquals(ByteRange(44uL, 2uL), boxes[1].payload)
        assertEquals(Bytes(ByteArray(16) { it.toByte() }), boxes[1].userType)
    }

    @Test
    fun zeroSizeChildStopsAtItsParentInsteadOfBorrowingWholeSourceTail(): Unit = runImmediate {
        val parent = box("moov", bytes(0, 0, 0, 0) + "free".encodeToByteArray() + bytes(1, 2, 3, 4))
        val input = parent + box("mdat", bytes(5, 6))
        val parser = parser(input)
        val top = value(parser.readBoxes(ByteRange(0uL, input.size.toULong())))
        val child = value(parser.readBoxes(top[0].payload, depth = 1u)).single()
        assertEquals(ByteRange(8uL, 12uL), child.range)
        assertEquals(ByteRange(16uL, 4uL), child.payload)
        assertTrue(child.extendsToParentEnd)
    }

    @Test
    fun unknownLeafPayloadIsNotAutomaticallyParsedAsNestedBoxes(): Unit = runImmediate {
        val invalidChildHeader = bytes(0xff, 0xff, 0xff, 0xff) + "moov".encodeToByteArray()
        val input = box("zzzz", invalidChildHeader)
        val box = value(parser(input).readBoxes(ByteRange(0uL, input.size.toULong()))).single()
        assertEquals("zzzz", box.type)
        assertEquals(ByteRange(8uL, 8uL), box.payload)
    }

    @Test
    fun quickTimeMetaPrefixIsChosenByCallerWithoutAutomaticFullBoxSkipping(): Unit = runImmediate {
        val input = box("meta", box("free", byteArrayOf()))
        val parser = parser(input)
        val parent = value(parser.readBoxes(ByteRange(0uL, input.size.toULong()))).single()
        val children = value(parser.readBoxes(parent.payload, depth = 1u))
        assertEquals("free", children.single().type)
        assertEquals(ByteRange(8uL, 8uL), children.single().range)
    }

    @Test
    fun malformedHeadersSizesAndParentOverrunsHaveNoUncheckedRead(): Unit = runImmediate {
        val malformed = listOf(
            bytes(0, 0, 0, 7) + "free".encodeToByteArray(),
            bytes(0, 0, 0, 1) + "free".encodeToByteArray(),
            bytes(0, 0, 0, 1) + "free".encodeToByteArray() + bytes(0, 0, 0, 0, 0, 0, 0, 15),
            bytes(0, 0, 0, 1) + "free".encodeToByteArray() + ByteArray(8) { (-1).toByte() },
            bytes(0, 0, 0, 8) + "uuid".encodeToByteArray(),
            bytes(0, 0, 0, 16) + "free".encodeToByteArray(),
            box("free", byteArrayOf()) + bytes(1, 2, 3),
        )
        for (input in malformed) assertIs<CoreResult.Failure>(parser(input).readBoxes(ByteRange(0uL, input.size.toULong())))
        val realBytesOutsideParent = bytes(0, 0, 0, 16) + "free".encodeToByteArray() + ByteArray(8)
        assertIs<CoreResult.Failure>(parser(realBytesOutsideParent).readBoxes(ByteRange(0uL, 8uL)))
    }

    @Test
    fun hugeOpaquePayloadIsRepresentedAsARangeWithoutAllocation(): Unit = runImmediate {
        val header = bytes(0, 0, 0, 1) + "free".encodeToByteArray() + ByteArray(8) { (-1).toByte() }
        val source = TestSource(header, declaredSize = ULong.MAX_VALUE)
        val box = value(BmffReader(BinaryReader(source, context())).readBoxes(ByteRange(0uL, ULong.MAX_VALUE))).single()
        assertEquals(ByteRange(16uL, ULong.MAX_VALUE - 16uL), box.payload)
        assertTrue(source.readCalls <= 2)
    }

    @Test
    fun boxDepthAndItemBudgetsAreCheckedBeforeBuildingUnboundedLists(): Unit = runImmediate {
        val two = box("free", byteArrayOf()) + box("skip", byteArrayOf())
        val oneItem = context().copy(limits = Limits(100_000uL, 100_000uL, maxItems = 1uL))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(parser(two, oneItem).readBoxes(ByteRange(0uL, two.size.toULong()))).error.code)
        val shallow = context().copy(limits = Limits(100_000uL, 100_000uL, maxDepth = 1u))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(parser(two, shallow).readBoxes(ByteRange(0uL, two.size.toULong()), depth = 2u)).error.code)
    }

    @Test
    fun fileTypeFieldsMatchGoldenBytesAndRemainOnlyContentHints(): Unit = runImmediate {
        val input = box("ftyp", "heic".encodeToByteArray() + bytes(0, 0, 0, 7) + "mif1isomqt  ".encodeToByteArray())
        val reader = BinaryReader(TestSource(input), context())
        val parser = BmffReader(reader)
        val type = value(parser.readFileType(value(parser.readBoxes(ByteRange(0uL, input.size.toULong()))).single()))
        assertEquals("heic", type.majorBrand)
        assertEquals(7u, type.minorVersion)
        assertEquals(listOf("mif1", "isom", "qt  "), type.compatibleBrands)
        val detected = value(detectContent(reader))
        assertEquals(ContentKind.IsoBmff, detected.kind)
        assertEquals(setOf(BmffBrandHint.Heic, BmffBrandHint.Heif, BmffBrandHint.Mp4, BmffBrandHint.QuickTime), detected.brandHints)
    }

    @Test
    fun contentDetectionUsesBytesAndTruncatedKnownBmffIsAnErrorNotNonLive(): Unit = runImmediate {
        assertEquals(ContentKind.Jpeg, value(detectContent(BinaryReader(TestSource(bytes(0xff, 0xd8)), context()))).kind)
        assertEquals(ContentKind.Unknown, value(detectContent(BinaryReader(TestSource("fake.mp4".encodeToByteArray()), context()))).kind)
        assertIs<CoreResult.Failure>(detectContent(BinaryReader(TestSource(bytes(0, 0, 0, 24) + "ftyp".encodeToByteArray()), context())))
        val duplicated = box("ftyp", "isom".encodeToByteArray() + ByteArray(4))
        assertIs<CoreResult.Failure>(detectContent(BinaryReader(TestSource(duplicated + duplicated), context())))
    }

    @Test
    fun boundedMutationsCannotEscapeAsUncheckedExceptions(): Unit = runImmediate {
        val golden = box("ftyp", "isom".encodeToByteArray() + ByteArray(4) + "mp42".encodeToByteArray()) + box("free", bytes(1, 2, 3))
        for (input in boundedMutations(golden)) {
            when (val result = parser(input).readBoxes(ByteRange(0uL, input.size.toULong()))) {
                is CoreResult.Success -> assertTrue(result.value.all { it.range.fitsWithin(input.size.toULong()) })
                is CoreResult.Failure -> assertTrue(result.error.code.value.isNotBlank())
            }
        }
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        return bytes(size ushr 24, size ushr 16, size ushr 8, size) + type.encodeToByteArray() + payload
    }
    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
