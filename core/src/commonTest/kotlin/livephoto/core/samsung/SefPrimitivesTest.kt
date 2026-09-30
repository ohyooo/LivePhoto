package livephoto.core.samsung

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class SefPrimitivesTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private fun reader(bytes: ByteArray, context: Context = this.context) = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("sef-primitive")), context)

    @Test
    fun canonicalGraphFieldsMatchIndependentAbsoluteOffsetsAndRawNames(): Unit = runImmediate {
        val fixture = SamsungFixtures.photo(ordinaryRecord = true)
        val parsed = requireNotNull(value(SefReader.parse(reader(fixture.bytes), fixture.jpegEnd.toULong())))
        assertEquals(ByteRange(fixture.tableStart.toULong(), 48uL), parsed.table)
        assertEquals(ByteRange((fixture.bytes.size - 8).toULong(), 8uL), parsed.footer)
        assertEquals(107u, parsed.version)
        assertEquals(3, parsed.records.size)
        assertFalse(parsed.legacyDialect)
        assertTrue(parsed.gaps.isEmpty())
        assertEquals(Bytes("MotionPhoto_Data".encodeToByteArray()), parsed.motionRecord?.name)
        assertEquals(ByteRange(fixture.videoStart.toULong(), GoogleFixtures.video().bytes.size.toULong()), parsed.pureVideoRange)
    }

    @Test
    fun legacyFlagIsBasedOnExactCompleteGraphAndAbsentFooterIsNotScanned(): Unit = runImmediate {
        val legacy = SamsungFixtures.photo(legacy = true)
        assertTrue(requireNotNull(value(SefReader.parse(reader(legacy.bytes), legacy.jpegEnd.toULong()))).legacyDialect)
        assertNull(value(SefReader.parse(reader(legacy.bytes + byteArrayOf(0)), legacy.jpegEnd.toULong())))
        assertNull(value(SefReader.parse(reader(GoogleFixtures.jpeg()))))
    }

    @Test
    fun boundedParentUsesItsOwnFooterAndRejectsRecordsPointingBeforeParent(): Unit = runImmediate {
        val suffix = SamsungFixtures.trailer(listOf(SamsungFixtures.Record(0x1234, "Private", byteArrayOf(1, 2, 3))))
        val prefix = GoogleFixtures.bytes(7, 8, 9, 10)
        val bytes = prefix + suffix + GoogleFixtures.bytes(0, 0, 0)
        val parent = ByteRange(prefix.size.toULong(), suffix.size.toULong())
        val directory = requireNotNull(value(SefReader.parseInRange(reader(bytes), parent)))
        assertEquals(parent, directory.parent)
        assertEquals(parent.offset, directory.records.single().range.offset)
        val escaped = bytes.copyOf()
        val table = prefix.size + suffix.size - 8 - 24
        SamsungFixtures.le32((directory.records.single().range.length + 1uL).toUInt()).copyInto(escaped, table + 16)
        assertEquals(IssueCode("SEF_DIRECTORY_INVALID"), assertIs<CoreResult.Failure>(SefReader.parseInRange(reader(escaped), parent)).error.code)
    }

    @Test
    fun mpv2PointerPayloadIsNeverReportedAsTwelveByteVideo(): Unit = runImmediate {
        val pointer = "mpv2".encodeToByteArray() + GoogleFixtures.u32(1234u) + GoogleFixtures.u32(5678u)
        val suffix = SamsungFixtures.trailer(listOf(SamsungFixtures.Record(0x0a30, "MotionPhoto_Data", pointer), SamsungFixtures.Record(0x0a31, "MotionPhoto_Version", "mpv3".encodeToByteArray())))
        val parsed = requireNotNull(value(SefReader.parseInRange(reader(suffix), ByteRange(0uL, suffix.size.toULong()))))
        assertNull(parsed.pureVideoRange)
        assertEquals(ByteRange(24uL, 12uL), parsed.motionRecord?.payloadRange)
    }

    @Test
    fun shortReadsBudgetsCancellationAndIdentityChangeRemainStructured(): Unit = runImmediate {
        val fixture = SamsungFixtures.photo()
        val short = TestSource(fixture.bytes, maxChunk = 1)
        assertNotNull(value(SefReader.parse(BinaryReader(short, context), fixture.jpegEnd.toULong())))
        assertFalse(short.closed)
        val low = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 8uL))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(SefReader.parse(reader(fixture.bytes, low), fixture.jpegEnd.toULong())).error.code)
        val cancelled = context.copy(cancellation = Cancellation { true })
        assertEquals(IssueCode("CANCELLED"), assertIs<CoreResult.Failure>(SefReader.parse(reader(fixture.bytes, cancelled))).error.code)
        val changed = TestSource(fixture.bytes)
        changed.onRead = { changed.currentIdentity = changed.currentIdentity.copy(generation = GenerationToken("different")) }
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(SefReader.parse(BinaryReader(changed, context))).error.code)
    }

    @Test
    fun writerAndCleanerPreserveOrdinaryRawRecordWithIndependentCanonicalOracle(): Unit = runImmediate {
        val fixture = SamsungFixtures.photo(ordinaryRecord = true)
        val original = reader(fixture.bytes)
        val directory = requireNotNull(value(SefReader.parse(original, fixture.jpegEnd.toULong())))
        val cleanedPlan = value(SefWriter.cleanPlan(original, directory))
        val cleaned = TestSink()
        value(SefWriter.write(cleanedPlan, BinaryWriter(cleaned, context), context))
        val remaining = SamsungFixtures.directory(cleaned.written.toByteArray())
        assertEquals(1, remaining.size)
        assertContentEquals(SamsungFixtures.ordinary.bytes, remaining.single().raw)
        val video = GoogleFixtures.video().bytes
        val plan = value(SefWriter.createPlan(reader(video), ByteRange(0uL, video.size.toULong()), ordinaryReader = original, ordinaryDirectory = directory))
        assertEquals(24uL, plan.videoOffset)
        val output = TestSink()
        value(SefWriter.write(plan, BinaryWriter(output, context), context))
        val created = SamsungFixtures.directory(output.written.toByteArray())
        assertContentEquals(video, created.single { it.type == 0x0a30 }.payload)
        assertContentEquals(SamsungFixtures.ordinary.bytes, created.single { it.type == 0x1234 }.raw)
        assertEquals(listOf(0x0a30, 0x0a31, 0x1234), created.map { it.type })
    }

    @Test
    fun unindexedGapsCanBeInspectedButCannotAuthorizeCleanerToDiscardBytes(): Unit = runImmediate {
        val fixture = SamsungFixtures.photo()
        val bytes = fixture.bytes.copyOfRange(0, fixture.jpegEnd) + byteArrayOf(77) + fixture.bytes.copyOfRange(fixture.jpegEnd, fixture.bytes.size)
        val original = reader(bytes)
        val directory = requireNotNull(value(SefReader.parse(original, fixture.jpegEnd.toULong())))
        assertEquals(listOf(ByteRange(fixture.jpegEnd.toULong(), 1uL)), directory.gaps)
        assertEquals(IssueCode("UNSAFE_METADATA_REWRITE"), assertIs<CoreResult.Failure>(SefWriter.cleanPlan(original, directory)).error.code)
    }

    @Test
    fun writePlanRechecksSourceSnapshotAndSharesTotalOutputBudget(): Unit = runImmediate {
        val video = TestSource(GoogleFixtures.video().bytes)
        val source = BinaryReader(video, context)
        val plan = value(SefWriter.createPlan(source, ByteRange(0uL, GoogleFixtures.video().bytes.size.toULong())))
        video.currentIdentity = video.currentIdentity.copy(generation = GenerationToken("changed-after-plan"))
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(SefWriter.write(plan, BinaryWriter(TestSink(), context), context)).error.code)
        val normal = value(SefWriter.createPlan(reader(GoogleFixtures.video().bytes), ByteRange(0uL, GoogleFixtures.video().bytes.size.toULong())))
        val limited = Context(Limits(2_000_000uL, normal.length - 1uL, maxMetadataBytes = 1_000_000uL))
        val sink = TestSink()
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(SefWriter.write(normal, BinaryWriter(sink, limited), limited)).error.code)
        assertTrue(sink.written.isEmpty())
    }

    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
