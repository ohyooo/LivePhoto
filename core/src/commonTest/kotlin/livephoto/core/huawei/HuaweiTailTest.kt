package livephoto.core.huawei

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class HuaweiTailTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private fun reader(bytes: ByteArray, context: Context = this.context) = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("huawei-tail")), context)

    @Test
    fun writerChecksAllThreeFixedFieldWidthsWithoutTruncation() {
        assertEquals(Bytes(HuaweiFixtures.tail("v6_f99", "999:9999", "LIVE_3020")), value(HuaweiTail.create(3000uL, "v6_f99", "999:9999", ParseBudget(context))))
        assertEquals(60, value(HuaweiTail.create(999_999_999_999_979uL, "v6_f0", "0:1", ParseBudget(context))).size)
        for ((length, prefix, history) in listOf(Triple(3000uL, "v6_f100", "0:1"), Triple(3000uL, "v6_f0", "9999:9999"), Triple(999_999_999_999_980uL, "v6_f0", "0:1"))) {
            assertEquals(IssueCode("VALUE_NOT_REPRESENTABLE"), assertIs<CoreResult.Failure>(HuaweiTail.create(length, prefix, history, ParseBudget(context))).error.code)
        }
        assertEquals(IssueCode("INTEGER_OVERFLOW"), assertIs<CoreResult.Failure>(HuaweiTail.create(ULong.MAX_VALUE, "v6_f0", "0:1", ParseBudget(context))).error.code)
        for ((prefix, history) in listOf("v6_f-1" to "0:1", "v6_f+1" to "0:1", "v6_f0" to "1:+2", "v6_f0" to "1:2:3")) {
            assertEquals(IssueCode("INVALID_ARGUMENT"), assertIs<CoreResult.Failure>(HuaweiTail.create(3000uL, prefix, history, ParseBudget(context))).error.code)
        }
    }

    @Test
    fun readRetainsUnknownHonorExtentAndDoesNotGuessVideoEnd(): Unit = runImmediate {
        val uuid = GoogleFixtures.box("uuid", ByteArray(16) { 0x11 } + "srcDstWh".encodeToByteArray())
        val plain = HuaweiFixtures.photo()
        val basic = value(HuaweiTail.read(reader(plain.bytes), plain.jpeg.size.toULong()))!!
        assertEquals(HuaweiTailVariant.Basic60, basic.variant)
        assertEquals((plain.video.size + 20).toULong(), basic.liveValue)
        assertEquals(ByteRange(plain.jpeg.size.toULong(), plain.video.size.toULong()), basic.videoRange)
        assertEquals(Bytes(plain.tail), basic.rawTail)
        assertNull(basic.key.position)
        for (fixture in listOf(HuaweiFixtures.photo(video = plain.video + uuid, prefix = "v2_f1"), HuaweiFixtures.photo(extra = uuid, prefix = "v1_f1"))) {
            val unknown = value(HuaweiTail.read(reader(fixture.bytes), fixture.jpeg.size.toULong()))!!
            assertEquals(HuaweiTailVariant.HonorExtended, unknown.variant)
            assertNull(unknown.videoRange)
            assertEquals(ByteRange(fixture.videoStart.toULong(), fixture.video.size.toULong()), unknown.candidateVideoRange)
            assertTrue(unknown.issues.any { it.code == IssueCode("UNKNOWN_PROTOCOL_VARIANT") })
            assertEquals(Bytes(fixture.tail), unknown.rawTail)
        }
    }

    @Test
    fun readerRequiresExactEofFieldAndHonorsCancellationAndAllocationBudget(): Unit = runImmediate {
        val fixture = HuaweiFixtures.photo()
        assertNull(value(HuaweiTail.read(reader(GoogleFixtures.jpeg(GoogleFixtures.segment(0xee, "LIVE_3020".encodeToByteArray()))))))
        assertNull(value(HuaweiTail.read(reader(fixture.bytes + byteArrayOf(0)))))
        val short = HuaweiFixtures.tail("v6_f0", "0:1", "LIVE_3020").takeLast(20).toByteArray()
        assertEquals(IssueCode("VENDOR_TRAILER_INVALID"), assertIs<CoreResult.Failure>(HuaweiTail.read(reader(short))).error.code)
        val low = context.copy(limits = context.limits.copy(maxMetadataBytes = 512uL))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(HuaweiTail.read(reader(fixture.bytes, low), fixture.jpeg.size.toULong())).error.code)
        val cancelled = context.copy(cancellation = Cancellation { true })
        assertEquals(IssueCode("CANCELLED"), assertIs<CoreResult.Failure>(HuaweiTail.read(reader(fixture.bytes, cancelled), fixture.jpeg.size.toULong())).error.code)
    }

    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
