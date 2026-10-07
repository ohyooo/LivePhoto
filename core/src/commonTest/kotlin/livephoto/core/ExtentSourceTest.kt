package livephoto.core

import livephoto.core.binary.*
import livephoto.core.implementation.ExtentSource
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class ExtentSourceTest {
    private val context = Context(Limits(1_000_000uL, 1_000_000uL))
    @Test fun everyCrossExtentReadKeepsDeclaredOrderAndShortReadsWithoutClosingBorrowedSource(): Unit = runImmediate {
        val bytes = ByteArray(16) { it.toByte() }
        val base = MemoryBinarySource(Bytes(bytes), SourceId("extent-base"))
        val short = object : BinarySource by base {
            override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = base.readAt(offset, minOf(length, 1u))
        }
        val view = ExtentSource.create(BinaryReader(short, context), listOf(ByteRange(8uL, 3uL), ByteRange(1uL, 2uL), ByteRange(8uL, 3uL))).orThrow()
        val expected = Bytes(byteArrayOf(8, 9, 10, 1, 2, 8, 9, 10))
        for (offset in 0..8) for (length in 0..(8 - offset)) assertEquals(expected.slice(offset, offset + length), view.readAt(offset.toULong(), length.toUInt()).orThrow())
        view.close()
        assertEquals(16uL, base.identity().orThrow().size)
        assertNotEquals(base.identity().orThrow().id, view.identity().orThrow().id)
    }
    @Test fun invalidRangesBuffersAndBudgetsFailBeforeUnboundedReads(): Unit = runImmediate {
        val base = MemoryBinarySource(Bytes(ByteArray(70_000)), SourceId("extent-budget"))
        val reader = BinaryReader(base, context)
        for (ranges in listOf(emptyList(), listOf(ByteRange(0uL, 0uL)), listOf(ByteRange(70_000uL, 1uL))))
            assertIs<CoreResult.Failure>(ExtentSource.create(reader, ranges))
        val view = ExtentSource.create(reader, listOf(ByteRange(0uL, 70_000uL))).orThrow()
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(view.readAt(0uL, 65_537u)).error.code)
        assertEquals(IssueCode("OFFSET_OUT_OF_BOUNDS"), assertIs<CoreResult.Failure>(view.readAt(70_000uL, 1u)).error.code)
        val limited = BinaryReader(base, context.copy(limits = context.limits.copy(maxMetadataBytes = 47uL)))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(ExtentSource.create(limited, listOf(ByteRange(0uL, 1uL)))).error.code)
    }
    @Test fun sourceGenerationAndCancellationAreRecheckedForVirtualIdentitiesAndBytes(): Unit = runImmediate {
        val base = MemoryBinarySource(Bytes(byteArrayOf(1, 2)), SourceId("extent-changing"))
        var changed = false
        val source = object : BinarySource by base {
            override suspend fun identity(): CoreResult<SourceIdentity> = CoreResult.Success(base.identity().orThrow().copy(generation = GenerationToken(if (changed) "v2" else "v1")))
        }
        val view = ExtentSource.create(BinaryReader(source, context), listOf(ByteRange(0uL, 1uL), ByteRange(1uL, 1uL))).orThrow()
        changed = true
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(view.identity()).error.code)
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(view.readAt(0uL, 1u)).error.code)
        var cancelled = false
        val other = ExtentSource.create(BinaryReader(base, context.copy(cancellation = Cancellation { cancelled })), listOf(ByteRange(0uL, 2uL))).orThrow()
        cancelled = true
        assertEquals(IssueCode("CANCELLED"), assertIs<CoreResult.Failure>(other.size()).error.code)
        assertEquals(IssueCode("CANCELLED"), assertIs<CoreResult.Failure>(other.readAt(0uL, 1u)).error.code)
    }
}
