package livephoto.core.metadata

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.jpeg.JpegParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MetadataPreservationTest {
    private val context = Context(Limits(100_000uL, 100_000uL))

    @Test
    fun inventoryRetainsOpaqueExifUnknownAppAndTrailerWithoutInventingGuarantees(): Unit = runImmediate {
        val exif = app(0xe1, "Exif\u0000\u0000".encodeToByteArray() + ByteArray(8))
        val mpf = app(0xe2, "MPF\u0000".encodeToByteArray() + ByteArray(4))
        val unknownApp = app(0xe3, bytes(0x77, 0x88, 0x99))
        val comment = app(0xfe, "Motion ordinary comment".encodeToByteArray())
        val tail = bytes(0xff, 0xda, 0, 8, 1, 1, 0, 0, 0x3f, 0, 1, 0xff, 0xd9)
        val carrier = bytes(0xff, 0xd8) + exif + mpf + unknownApp + comment + tail
        val input = carrier + bytes(4, 5, 6)
        val structure = value(JpegParser.parse(BinaryReader(TestSource(input), context)))
        val inventory = value(MetadataPreservation.jpeg(structure, context))
        val exifBlock = inventory.blocks.single { it.kind == ResourceKind.Exif }
        assertEquals(ByteRange(2uL, exif.size.toULong()), exifBlock.range)
        assertEquals(MetadataRule.MustPreserve, exifBlock.rule)
        assertEquals(DependencySafety.OpaqueOffsets, exifBlock.dependency)
        assertEquals(DependencySafety.GraphDependent, inventory.blocks.single { it.kind == ResourceKind.VendorMetadata }.dependency)
        val unknownRange = ByteRange((2 + exif.size + mpf.size).toULong(), unknownApp.size.toULong())
        assertEquals(MetadataRule.MustPreserve, inventory.blocks.single { it.range == unknownRange }.rule)
        val trailer = inventory.blocks.single { it.range == ByteRange(carrier.size.toULong(), 3uL) }
        assertEquals(Ownership.Unknown, trailer.owner)
        assertEquals(MetadataRule.Unknown, trailer.rule)
    }

    @Test
    fun sourceOwnedBindingsAreDeletedOnlyByCleanConversionWhileRawNeverEdits() {
        fun field(owner: Ownership): MetadataEntry = MetadataEntry("namespace:field", owner = owner, location = Location(), origin = FactOrigin.Parsed)
        assertEquals(MetadataRule.MustPreserve, MetadataPreservation.fieldRule(field(Ownership.Ordinary), Operation.SplitClean))
        assertEquals(MetadataRule.Unknown, MetadataPreservation.fieldRule(field(Ownership.Unknown), Operation.SplitClean))
        assertEquals(MetadataRule.MustDelete, MetadataPreservation.fieldRule(field(Ownership.SourceProtocol), Operation.SplitClean))
        assertEquals(MetadataRule.MustDelete, MetadataPreservation.fieldRule(field(Ownership.TargetProtocol), Operation.SplitClean))
        assertEquals(MetadataRule.MayRewrite, MetadataPreservation.fieldRule(field(Ownership.SourceProtocol), Operation.SetKey))
        assertEquals(MetadataRule.MustPreserve, MetadataPreservation.fieldRule(field(Ownership.SourceProtocol), Operation.ReplaceCover))
        assertEquals(MetadataRule.MayRewrite, MetadataPreservation.fieldRule(field(Ownership.SourceProtocol), Operation.ReplaceCover, verifiedMutableSelectors = setOf("namespace:field")))
        assertEquals(MetadataRule.MustPreserve, MetadataPreservation.fieldRule(field(Ownership.SourceProtocol), Operation.ReplaceCover, verifiedMutableSelectors = setOf("another:field")))
        for (owner in Ownership.entries) {
            assertEquals(MetadataRule.MustPreserve, MetadataPreservation.fieldRule(field(owner), Operation.ExtractRaw))
            assertEquals(MetadataRule.MustPreserve, MetadataPreservation.fieldRule(field(owner), Operation.Inspect))
        }
    }

    @Test
    fun metadataInventoryChecksModelBudgetsIndependentlyOfPriorParsing(): Unit = runImmediate {
        val input = bytes(0xff, 0xd8) + app(0xe3, bytes(1)) + app(0xe4, bytes(2)) + bytes(0xff, 0xd9)
        val structure = value(JpegParser.parse(BinaryReader(TestSource(input), context)))
        val limited = context.copy(limits = Limits(100_000uL, 100_000uL, maxItems = 1uL))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(MetadataPreservation.jpeg(structure, limited)).error.code)
    }

    private fun app(marker: Int, payload: ByteArray): ByteArray = bytes(0xff, marker, (payload.size + 2) ushr 8, (payload.size + 2) and 255) + payload
    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
