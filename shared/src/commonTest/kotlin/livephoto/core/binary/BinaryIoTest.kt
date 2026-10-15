package livephoto.core.binary

import livephoto.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BinaryIoTest {
    private fun context(metadata: ULong = 1024uL, output: ULong = 1024uL, cancellation: Cancellation? = null): Context =
        Context(Limits(1024uL, output, maxMetadataBytes = metadata), cancellation = cancellation)

    @Test
    fun checkedArithmeticHandlesUInt64LimitsAndPlatformNarrowing() {
        assertEquals(ULong.MAX_VALUE, checkedAdd(ULong.MAX_VALUE - 1uL, 1uL))
        assertEquals(ULong.MAX_VALUE, checkedMultiply(ULong.MAX_VALUE, 1uL))
        assertEquals(0uL, checkedMultiply(ULong.MAX_VALUE, 0uL))
        assertFailure("INTEGER_OVERFLOW", attemptNow { checkedAdd(ULong.MAX_VALUE, 1uL) })
        assertFailure("INTEGER_OVERFLOW", attemptNow { checkedMultiply(ULong.MAX_VALUE, 2uL) })
        assertEquals(Int.MAX_VALUE, checkedInt(Int.MAX_VALUE.toULong()))
        assertFailure("RESOURCE_LIMIT_EXCEEDED", attemptNow { checkedInt(Int.MAX_VALUE.toULong() + 1uL) })
    }

    @Test
    fun exactReadLoopsOverShortReadsAndNeverClosesBorrowedSource(): Unit = runImmediate {
        val source = TestSource(byteArrayOf(1, 2, 3, 4, 5), maxChunk = 1)
        val reader = BinaryReader(source, context())
        assertEquals(Bytes(byteArrayOf(2, 3, 4)), value(reader.readExactly(1uL, 3u)))
        assertFalse(source.closed)
        assertTrue(source.readCalls > 1)
    }

    @Test
    fun shortReadsThatStopBeforeRequestedLengthAreNotSuccess(): Unit = runImmediate {
        val source = TestSource(byteArrayOf(1, 2, 3), declaredSize = 4uL, maxChunk = 1)
        assertFailure("UNEXPECTED_EOF", BinaryReader(source, context()).readExactly(0uL, 4u))
        assertFalse(source.closed)
    }

    @Test
    fun zeroProgressAndOversizedRepliesTerminateWithStructuredErrors(): Unit = runImmediate {
        val zero = TestSource(byteArrayOf(1, 2), overrideRead = { _, _ -> CoreResult.Success(Bytes(byteArrayOf())) })
        assertFailure("UNEXPECTED_EOF", BinaryReader(zero, context()).readExactly(0uL, 1u))
        assertEquals(1, zero.readCalls)
        val oversized = TestSource(byteArrayOf(1, 2), overrideRead = { _, _ -> CoreResult.Success(Bytes(byteArrayOf(1, 2))) })
        assertIs<CoreResult.Failure>(BinaryReader(oversized, context()).readExactly(0uL, 1u))
        assertEquals(1, oversized.readCalls)
    }

    @Test
    fun uint64EndOverflowAndUnaddressableAllocationsFailBeforeReading(): Unit = runImmediate {
        val source = TestSource(byteArrayOf(), declaredSize = ULong.MAX_VALUE)
        val reader = BinaryReader(source, context())
        assertFailure("INTEGER_OVERFLOW", reader.readExactly(ULong.MAX_VALUE, 1u))
        assertFailure("RESOURCE_LIMIT_EXCEEDED", BinaryReader(source, context(metadata = ULong.MAX_VALUE)).readExactly(0uL, UInt.MAX_VALUE))
        assertEquals(0, source.readCalls)
    }

    @Test
    fun metadataBudgetIsInclusiveAndRejectsOneByteOver(): Unit = runImmediate {
        val source = TestSource(byteArrayOf(1, 2, 3, 4, 5))
        val reader = BinaryReader(source, context(metadata = 4uL))
        assertEquals(Bytes(byteArrayOf(1, 2, 3, 4)), value(reader.readExactly(0uL, 4u)))
        val before = source.readCalls
        assertFailure("RESOURCE_LIMIT_EXCEEDED", reader.readExactly(0uL, 5u))
        assertEquals(before, source.readCalls)
    }

    @Test
    fun changingSourceIdGenerationOrSizeInvalidatesReaderSnapshot(): Unit = runImmediate {
        for (change in listOf<(SourceIdentity) -> SourceIdentity>(
            { it.copy(id = SourceId("different")) },
            { it.copy(generation = GenerationToken("different")) },
            { it.copy(size = it.size + 1uL) },
        )) {
            val source = TestSource(byteArrayOf(1, 2, 3))
            val reader = BinaryReader(source, context())
            value(reader.identity())
            source.currentIdentity = change(source.currentIdentity)
            assertFailure("SOURCE_CHANGED", reader.validateIdentity())
            assertFalse(source.closed)
        }
    }

    @Test
    fun sourceChangingDuringAReadFailsInsteadOfReturningStaleBytes(): Unit = runImmediate {
        val source = TestSource(byteArrayOf(1, 2, 3))
        source.onRead = { source.currentIdentity = source.currentIdentity.copy(generation = GenerationToken("changed")) }
        assertFailure("SOURCE_CHANGED", BinaryReader(source, context()).readExactly(0uL, 3u))
    }

    @Test
    fun cancellationBeforeAndBetweenShortReadsDoesNotReadToCompletion(): Unit = runImmediate {
        val before = TestSource(byteArrayOf(1, 2, 3))
        assertFailure("CANCELLED", BinaryReader(before, context(cancellation = Cancellation { true })).readExactly(0uL, 3u))
        assertEquals(0, before.readCalls)
        var cancelled = false
        val source = TestSource(byteArrayOf(1, 2, 3), maxChunk = 1)
        source.onRead = { cancelled = true }
        assertFailure("CANCELLED", BinaryReader(source, context(cancellation = Cancellation { cancelled })).readExactly(0uL, 3u))
        assertEquals(1, source.readCalls)
    }

    @Test
    fun integerReadersInterpretIndependentEndianBytes(): Unit = runImmediate {
        val source = TestSource(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val reader = BinaryReader(source, context())
        assertEquals(0x0102u.toUShort(), value(reader.readU16(0uL, Endian.Big)))
        assertEquals(0x0201u.toUShort(), value(reader.readU16(0uL, Endian.Little)))
        assertEquals(0x01020304u, value(reader.readU32(0uL, Endian.Big)))
        assertEquals(0x04030201u, value(reader.readU32(0uL, Endian.Little)))
        assertEquals(0x0102030405060708uL, value(reader.readU64(0uL, Endian.Big)))
        assertEquals(0x0807060504030201uL, value(reader.readU64(0uL, Endian.Little)))
    }

    @Test
    fun emptyReadAtEndNeedsNoProviderReadAndOutOfBoundsNeverReachesProvider(): Unit = runImmediate {
        val source = TestSource(byteArrayOf(1, 2, 3))
        val reader = BinaryReader(source, context())
        assertEquals(Bytes(byteArrayOf()), value(reader.readExactly(3uL, 0u)))
        assertFailure("OFFSET_OUT_OF_BOUNDS", reader.readExactly(4uL, 0u))
        assertFailure("OFFSET_OUT_OF_BOUNDS", reader.readExactly(2uL, 2u))
        assertEquals(0, source.readCalls)
    }

    @Test
    fun integerWritersMatchGoldenEndianBytesWithoutCallingReader(): Unit = runImmediate {
        val sink = TestSink()
        val writer = BinaryWriter(sink, context())
        value(writer.writeU16(0x0102u.toUShort(), Endian.Big))
        value(writer.writeU32(0x01020304u, Endian.Little))
        value(writer.writeU64(ULong.MAX_VALUE, Endian.Big))
        assertEquals(listOf<Byte>(1, 2, 4, 3, 2, 1, -1, -1, -1, -1, -1, -1, -1, -1), sink.written)
    }

    @Test
    fun writerHandlesShortWritesWithoutDuplicatingBytes(): Unit = runImmediate {
        val sink = TestSink(maxChunk = 1)
        assertIs<CoreResult.Success<Unit>>(BinaryWriter(sink, context()).writeAll(Bytes(byteArrayOf(1, 2, 3))))
        assertEquals(listOf<Byte>(1, 2, 3), sink.written)
        assertFalse(sink.closed)
    }

    @Test
    fun oneByteWritesPreserveAModerateBufferAndRespectExactBudget(): Unit = runImmediate {
        val input = ByteArray(1024) { (it % 251).toByte() }
        val sink = TestSink(maxChunk = 1)
        value(BinaryWriter(sink, context(output = 1024uL)).writeAll(Bytes(input)))
        assertEquals(input.toList(), sink.written)
        assertEquals(1024, sink.calls)
        assertFalse(sink.closed)
    }

    @Test
    fun zeroAndImpossibleWriteCountsFailWithoutInfiniteRetry(): Unit = runImmediate {
        val zero = TestSink(maxChunk = 0)
        assertFailure("IO_WRITE_FAILED", BinaryWriter(zero, context()).writeAll(Bytes(byteArrayOf(1))))
        assertEquals(1, zero.calls)
        val oversized = TestSink(reportedCount = 2u)
        assertFailure("IO_WRITE_FAILED", BinaryWriter(oversized, context()).writeAll(Bytes(byteArrayOf(1))))
        assertEquals(1, oversized.calls)
    }

    @Test
    fun outputBudgetIsCheckedAcrossSuccessiveWrites(): Unit = runImmediate {
        val sink = TestSink()
        val writer = BinaryWriter(sink, context(output = 3uL))
        value(writer.writeAll(Bytes(byteArrayOf(1, 2))))
        value(writer.writeAll(Bytes(byteArrayOf(3))))
        assertFailure("RESOURCE_LIMIT_EXCEEDED", writer.writeAll(Bytes(byteArrayOf(4))))
        assertEquals(listOf<Byte>(1, 2, 3), sink.written)
    }

    @Test
    fun rangeCopyPreservesBytesAcrossShortIoAndDoesNotCloseBorrowedHandles(): Unit = runImmediate {
        val source = TestSource(byteArrayOf(10, 20, 30, 40, 50), maxChunk = 1)
        val sink = TestSink(maxChunk = 1)
        val context = context()
        value(copyRange(BinaryReader(source, context), sink, ByteRange(1uL, 3uL), context, chunkBytes = 2u))
        assertEquals(listOf<Byte>(20, 30, 40), sink.written)
        assertFalse(source.closed)
        assertFalse(sink.closed)
    }

    @Test
    fun copyRejectsZeroChunkAndInsufficientBudgetBeforeWriting(): Unit = runImmediate {
        val source = TestSource(byteArrayOf(1, 2, 3, 4))
        val sink = TestSink()
        val context = context(output = 3uL)
        assertFailure("INVALID_ARGUMENT", copyRange(BinaryReader(source, context), sink, ByteRange(0uL, 3uL), context, chunkBytes = 0u))
        assertFailure("RESOURCE_LIMIT_EXCEEDED", copyRange(BinaryReader(source, context), sink, ByteRange(0uL, 4uL), context))
        assertEquals(0, source.readCalls)
        assertEquals(0, sink.calls)
    }

    @Test
    fun composedCopiesAndMultipleAssetsShareOneOutputBudget(): Unit = runImmediate {
        val source = TestSource(byteArrayOf(1, 2, 3, 4))
        val sink = TestSink()
        val context = context(output = 3uL)
        val budget = OutputBudget(context)
        val writer = BinaryWriter(sink, context, budget)
        val reader = BinaryReader(source, context)
        value(copyRange(reader, writer, ByteRange(0uL, 2uL), context))
        assertFailure("RESOURCE_LIMIT_EXCEEDED", copyRange(reader, writer, ByteRange(2uL, 2uL), context))
        assertEquals(listOf<Byte>(1, 2), sink.written)
        val secondSink = TestSink()
        val secondAssetWriter = BinaryWriter(secondSink, context, budget)
        value(secondAssetWriter.writeAll(Bytes(byteArrayOf(3))))
        assertFailure("RESOURCE_LIMIT_EXCEEDED", secondAssetWriter.writeAll(Bytes(byteArrayOf(4))))
        assertEquals(listOf<Byte>(3), secondSink.written)
    }

    @Test
    fun streamingDoesNotChargeMediaBytesToRetainedMetadataBudget(): Unit = runImmediate {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        val source = TestSource(bytes)
        val sink = TestSink()
        val context = context(metadata = 8uL, output = 70_000uL)
        value(copyRange(BinaryReader(source, context), sink, ByteRange(0uL, 70_000uL), context))
        assertEquals(bytes.toList(), sink.written)
        assertTrue(source.readCalls > 1)
    }

    @Test
    fun aSourceChangingDuringStreamingCannotWriteThatUnverifiedChunk(): Unit = runImmediate {
        val source = TestSource(byteArrayOf(1, 2, 3, 4))
        source.onRead = { source.currentIdentity = source.currentIdentity.copy(generation = GenerationToken("changed")) }
        val sink = TestSink()
        val context = context()
        assertFailure("SOURCE_CHANGED", copyRange(BinaryReader(source, context), sink, ByteRange(0uL, 4uL), context, chunkBytes = 2u))
        assertEquals(0, sink.calls)
        assertFalse(source.closed)
        assertFalse(sink.closed)
    }

    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
    private fun assertFailure(code: String, result: CoreResult<*>): CoreError {
        val error = assertIs<CoreResult.Failure>(result).error
        assertEquals(IssueCode(code), error.code)
        return error
    }
}

internal class TestSource(
    private val bytes: ByteArray,
    private val declaredSize: ULong = bytes.size.toULong(),
    private val maxChunk: Int = Int.MAX_VALUE,
    private val overrideRead: ((ULong, UInt) -> CoreResult<Bytes>)? = null,
) : BinarySource {
    var currentIdentity: SourceIdentity = SourceIdentity(SourceId("test-source"), GenerationToken("initial"), declaredSize)
    var readCalls: Int = 0
    var closed: Boolean = false
    var onRead: (() -> Unit)? = null
    override suspend fun identity(): CoreResult<SourceIdentity> = CoreResult.Success(currentIdentity)
    override suspend fun size(): CoreResult<ULong> = CoreResult.Success(declaredSize)
    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> {
        readCalls++
        check(offset <= declaredSize && length.toULong() <= declaredSize - offset) { "Reader issued an out-of-bounds read" }
        val result = overrideRead?.invoke(offset, length) ?: if (offset >= bytes.size.toULong()) {
            CoreResult.Success(Bytes(byteArrayOf()))
        } else {
            val start = offset.toInt()
            val count = minOf(length.toLong(), maxChunk.toLong(), (bytes.size - start).toLong()).toInt()
            CoreResult.Success(Bytes(bytes.copyOfRange(start, start + count)))
        }
        onRead?.invoke()
        return result
    }
    override suspend fun close() { closed = true }
}

internal class TestSink(
    private val maxChunk: Int = Int.MAX_VALUE,
    private val reportedCount: UInt? = null,
) : BinarySink {
    val written: MutableList<Byte> = mutableListOf()
    var calls: Int = 0
    var closed: Boolean = false
    override suspend fun write(bytes: Bytes): CoreResult<UInt> {
        calls++
        val count = minOf(bytes.size, maxChunk)
        written.addAll(bytes.toByteArray().take(count))
        return CoreResult.Success(reportedCount ?: count.toUInt())
    }
    override suspend fun seek(offset: ULong): CoreResult<Unit> = error("Sequential test sink does not support seek")
    override suspend fun truncate(length: ULong): CoreResult<Unit> = error("Sequential test sink does not support truncate")
    override suspend fun flush(): CoreResult<Unit> = CoreResult.Success(Unit)
    override suspend fun close(): CoreResult<Unit> { closed = true; return CoreResult.Success(Unit) }
}
