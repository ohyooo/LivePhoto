package livephoto.core.heif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class HeifItemLocationsTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private data class Entry(val id: UInt, val method: UInt = 0u, val ranges: List<Pair<ULong, ULong>> = listOf(0uL to 4uL))
    private data class Fixture(val bytes: ByteArray, val payload: ByteRange, val inIdat: Boolean)
    private fun uint(value: ULong, width: Int): ByteArray = if (width == 0) byteArrayOf() else unsignedBytes(value, width, Endian.Big).toByteArray()
    private fun fixture(version: Int = 0, width: Int = 4, baseWidth: Int = 0, indexWidth: Int = 0,
                        entries: List<Entry> = listOf(Entry(1u)), idat: Boolean = false): Fixture {
        fun iloc(dataOffset: ULong): ByteArray {
            var payload = byteArrayOf(version.toByte(), 0, 0, 0, ((width shl 4) or width).toByte(), ((baseWidth shl 4) or indexWidth).toByte()) + uint(entries.size.toULong(), if (version == 2) 4 else 2)
            for (entry in entries) {
                val base = if (baseWidth != 0 && entry.method == 0u) dataOffset else 0uL
                payload += uint(entry.id.toULong(), if (version == 2) 4 else 2)
                if (version != 0) payload += uint(entry.method.toULong(), 2)
                payload += uint(0uL, 2) + uint(base, baseWidth) + uint(entry.ranges.size.toULong(), 2)
                for ((offset, length) in entry.ranges) payload += uint(0uL, indexWidth) + uint(if (entry.method == 0u) dataOffset + offset - base else offset, width) + uint(length, width)
            }
            return GoogleFixtures.box("iloc", payload)
        }
        val dataOffset = iloc(0uL).size.toULong() + 8uL
        val data = ByteArray(16) { (it + 1).toByte() }
        return Fixture(iloc(dataOffset) + GoogleFixtures.box(if (idat) "idat" else "mdat", data), ByteRange(dataOffset, data.size.toULong()), idat)
    }
    private suspend fun parse(fixture: Fixture, ctx: Context = context): Pair<BinaryReader, HeifItemLocations> {
        val reader = BinaryReader(MemoryBinarySource(Bytes(fixture.bytes), SourceId("heif-locations")), ctx)
        val boxes = BmffReader(reader).readBoxes(ByteRange(0uL, fixture.bytes.size.toULong())).orThrow()
        return reader to HeifItemLocations.read(reader, boxes.first(), if (fixture.inIdat) emptyList() else listOf(fixture.payload), fixture.payload.takeIf { fixture.inIdat }).orThrow()
    }

    @Test fun versionsWidthsMultipleExtentsAndSharedDataRemainExplicit(): Unit = runImmediate {
        for (version in 0..2) for (width in listOf(4, 8)) for (base in listOf(0, 4, 8)) {
            val id = if (version == 2) 70_001u else 1u
            val fixture = fixture(version, width, base, entries = listOf(Entry(id, ranges = listOf(0uL to 4uL, 8uL to 4uL)), Entry(id + 1u)))
            val (_, locations) = parse(fixture)
            assertEquals(listOf(id, id + 1u), locations.items.map { it.id })
            assertEquals(listOf(ByteRange(fixture.payload.offset, 4uL), ByteRange(fixture.payload.offset + 8uL, 4uL)), locations.items.first().extents.map { it.data })
            assertEquals(locations.items.first().extents.first().data, locations.items.last().extents.single().data)
            assertEquals(width.toULong(), locations.items.first().extents.first().offset.field.length)
            assertEquals(base.toULong(), locations.items.first().base.field.length)
        }
    }

    @Test fun idatUsesPayloadRelativeConstructionAndRetainsRelativeOffsets(): Unit = runImmediate {
        for (version in 1..2) {
            val fixture = fixture(version, 8, entries = listOf(Entry(1u, 1u, listOf(2uL to 4uL, 10uL to 4uL))), idat = true)
            val (reader, locations) = parse(fixture)
            assertEquals(fixture.payload.offset + 2uL, locations.items.single().extents.first().data.offset)
            assertTrue(locations.relocation(reader, listOf(HeifMovedRange(ByteRange(0uL, fixture.bytes.size.toULong()), 100uL)), fixture.bytes.size.toULong() + 100uL).orThrow().isEmpty())
        }
    }

    @Test fun expansionRelocatesOnlyEncodedFileOffsetsAndReparsesExactly(): Unit = runImmediate {
        for (width in listOf(4, 8)) for (base in listOf(0, 4, 8)) {
            val original = fixture(2, width, base, entries = listOf(Entry(70_001u, ranges = listOf(0uL to 4uL, 8uL to 4uL)), Entry(70_002u)))
            val (reader, locations) = parse(original)
            val headerLength = original.payload.offset - 8uL
            val moves = listOf(HeifMovedRange(ByteRange(0uL, headerLength), 0uL), HeifMovedRange(ByteRange(headerLength, 24uL), headerLength + 16uL))
            val patches = locations.relocation(reader, moves, original.bytes.size.toULong() + 16uL).orThrow()
            val result = original.bytes.copyOfRange(0, headerLength.toInt()) + GoogleFixtures.box("free", ByteArray(8)) + original.bytes.copyOfRange(headerLength.toInt(), original.bytes.size)
            for (patch in patches) {
                assertEquals(patch.before, Bytes(original.bytes.copyOfRange(patch.original.offset.toInt(), patch.original.endExclusive.toInt())))
                patch.after.copyInto(result, patch.destination.offset.toInt())
            }
            val (_, after) = parse(Fixture(result, original.payload.copy(offset = original.payload.offset + 16uL), false))
            assertEquals(locations.items.map { it.id }, after.items.map { it.id })
            for ((beforeItem, afterItem) in locations.items.zip(after.items)) for ((beforeExtent, afterExtent) in beforeItem.extents.zip(afterItem.extents)) {
                assertEquals(beforeExtent.data.copy(offset = beforeExtent.data.offset + 16uL), afterExtent.data)
                assertEquals(Bytes(original.bytes.copyOfRange(beforeExtent.data.offset.toInt(), beforeExtent.data.endExclusive.toInt())), Bytes(result.copyOfRange(afterExtent.data.offset.toInt(), afterExtent.data.endExclusive.toInt())))
            }
        }
    }

    @Test fun unknownConstructionExternalReferencesAndAmbiguousFieldsCannotBecomeProof(): Unit = runImmediate {
        suspend fun code(fixture: Fixture): IssueCode = try { parse(fixture); fail("Expected an unsafe iloc to fail") } catch (fault: CoreFault) { fault.error.code }
        val original = fixture(1)
        val unsupported = listOf(
            original.bytes.copyOf().also { it[8] = 3 }, // full-box version
            original.bytes.copyOf().also { it[12] = 0x34 }, // integer width
            original.bytes.copyOf().also { it[12] = 0x40 }, // implicit length
            original.bytes.copyOf().also { it[19] = 2 }, // construction method
            original.bytes.copyOf().also { it[21] = 1 }, // external reference
            original.bytes.copyOf().also { it.fill(0, 28, 32) }, // zero extent length
        )
        for (bytes in unsupported) assertEquals(IssueCode("CAPABILITY_UNSUPPORTED"), code(original.copy(bytes = bytes)))
        assertEquals(IssueCode("CORRUPTED_CONTAINER"), code(original.copy(bytes = original.bytes.copyOf().also { it[18] = 1 })))
        assertEquals(IssueCode("CORRUPTED_CONTAINER"), code(fixture(1, entries = listOf(Entry(1u), Entry(1u)))))
        assertEquals(IssueCode("OFFSET_OUT_OF_BOUNDS"), code(fixture(1, entries = listOf(Entry(1u, ranges = listOf(15uL to 4uL))))))
        assertEquals(IssueCode("CORRUPTED_CONTAINER"), code(fixture(1, entries = listOf(Entry(1u, 1u)))))
    }

    @Test fun relocationRejectsMissingOverlappingRegionsAndWidthOverflowWithoutOutputs(): Unit = runImmediate {
        val fixture = fixture()
        val (reader, locations) = parse(fixture)
        fun failure(result: CoreResult<List<HeifOffsetPatch>>, code: String) = assertEquals(IssueCode(code), assertIs<CoreResult.Failure>(result).error.code)
        failure(locations.relocation(reader, emptyList(), 100uL), "CAPABILITY_UNSUPPORTED")
        val full = ByteRange(0uL, fixture.bytes.size.toULong())
        failure(locations.relocation(reader, listOf(HeifMovedRange(full, 0uL), HeifMovedRange(full, 100uL)), 200uL), "AMBIGUOUS_LAYOUT")
        val hugeContext = Context(Limits(ULong.MAX_VALUE, ULong.MAX_VALUE))
        val (hugeReader, hugeLocations) = parse(fixture, hugeContext)
        failure(hugeLocations.relocation(hugeReader, listOf(HeifMovedRange(full, UInt.MAX_VALUE.toULong())), UInt.MAX_VALUE.toULong() + full.length), "VALUE_NOT_REPRESENTABLE")
    }

    @Test fun truncatedTablesAndResourceLimitsFailBeforeUnboundedAllocation(): Unit = runImmediate {
        val original = fixture(2)
        suspend fun read(bytes: ByteArray, ctx: Context): CoreResult<HeifItemLocations> {
            val reader = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("heif-budget")), ctx)
            val ilocSize = readUnsigned(Bytes(original.bytes.copyOfRange(0, 4)), Endian.Big)
            return HeifItemLocations.read(reader, BmffBox("iloc", ByteRange(0uL, ilocSize), 8uL, ByteRange(8uL, ilocSize - 8uL), false), listOf(original.payload))
        }
        for (length in 0 until original.bytes.size) assertIs<CoreResult.Failure>(read(original.bytes.copyOf(length), context))
        val excessive = original.bytes.copyOf().also { uint(UInt.MAX_VALUE.toULong(), 4).copyInto(it, 14) }
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(read(excessive, context.copy(limits = context.limits.copy(maxItems = 1uL)))).error.code)
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(read(original.bytes, context.copy(limits = context.limits.copy(maxMetadataBytes = 127uL)))).error.code)
    }

    @Test fun sparse64BitLocationsRemainBoundedAndPlansCannotCrossSourceIdentity(): Unit = runImmediate {
        val original = fixture(2, 8)
        val high = 1uL shl 40
        val metadata = original.bytes.copyOfRange(0, original.payload.offset.toInt() - 8).also { uint(high, 8).copyInto(it, 28) }
        val sparseIdentity = SourceIdentity(SourceId("heif-sparse"), GenerationToken("v1"), high + 16uL)
        val source = object : BinarySource {
            override suspend fun identity(): CoreResult<SourceIdentity> = CoreResult.Success(sparseIdentity)
            override suspend fun size(): CoreResult<ULong> = CoreResult.Success(sparseIdentity.size)
            override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> {
                assertTrue(length <= 8u, "Parsing must not allocate or read the 1-TB gap")
                return CoreResult.Success(Bytes(metadata.copyOfRange(offset.toInt(), offset.toInt() + length.toInt())))
            }
            override suspend fun close(): Unit = Unit
        }
        val reader = BinaryReader(source, Context(Limits(ULong.MAX_VALUE, ULong.MAX_VALUE)))
        val box = BmffBox("iloc", ByteRange(0uL, metadata.size.toULong()), 8uL, ByteRange(8uL, metadata.size.toULong() - 8uL), false)
        val locations = HeifItemLocations.read(reader, box, listOf(ByteRange(high, 16uL))).orThrow()
        assertEquals(ByteRange(high, 4uL), locations.items.single().extents.single().data)
        val patches = locations.relocation(reader, listOf(HeifMovedRange(box.range, 128uL), HeifMovedRange(ByteRange(high, 16uL), high + 4096uL)), high + 4112uL).orThrow()
        assertEquals(1, patches.size)
        assertEquals(ByteRange(156uL, 8uL), patches.single().destination)
        assertEquals(high + 4096uL, readUnsigned(patches.single().after, Endian.Big))
        val changed = BinaryReader(MemoryBinarySource(Bytes(original.bytes), SourceId("heif-sparse"), GenerationToken("v2")), context)
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(locations.relocation(changed, emptyList(), 100uL)).error.code)
        val overflowing = fixture(2, 8, 8)
        val bytes = overflowing.bytes.copyOf().also { uint(ULong.MAX_VALUE, 8).copyInto(it, 26); uint(1uL, 8).copyInto(it, 36) }
        try { parse(overflowing.copy(bytes = bytes)); fail("Expected UInt64 arithmetic overflow") }
        catch (fault: CoreFault) { assertEquals(IssueCode("INTEGER_OVERFLOW"), fault.error.code) }
    }
}
