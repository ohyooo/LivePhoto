package livephoto.core.binary

import livephoto.core.*

internal enum class Endian { Big, Little }

/** Random reads over a borrowed source, consistently bound to its first observed identity. */
internal class BinaryReader(private val source: BinarySource, val context: Context) {
    private var frozenIdentity: SourceIdentity? = null

    suspend fun identity(): CoreResult<SourceIdentity> = attempt { checkIdentity() }
    suspend fun validateIdentity(): CoreResult<Unit> = attempt<Unit> { checkIdentity() }

    private suspend fun checkIdentity(): SourceIdentity {
        checkCancelled(context)
        if (context.limits.maxSources == 0u) fail("RESOURCE_LIMIT_EXCEEDED", "No source budget is available", Stage.Read)
        val current = source.identity().orThrow()
        val actualSize = source.size().orThrow()
        if (actualSize != current.size) fail("SOURCE_CHANGED", "Source size and identity disagree", Stage.Read)
        val expected = frozenIdentity
        if (expected == null) frozenIdentity = current
        else if (current != expected) fail("SOURCE_CHANGED", "Source identity changed during the operation", Stage.Read)
        return current
    }

    suspend fun readExactly(offset: ULong, length: UInt): CoreResult<Bytes> = readBounded(offset, length, context.limits.maxMetadataBytes)

    /** Temporary IO buffers have an independent fixed cap and are not retained metadata. */
    suspend fun readBuffer(offset: ULong, length: UInt): CoreResult<Bytes> = readBounded(offset, length, 64uL * 1024uL)

    private suspend fun readBounded(offset: ULong, length: UInt, allocationLimit: ULong): CoreResult<Bytes> = attempt {
        val identity = checkIdentity()
        checkedRange(offset, length.toULong(), identity.size)
        if (length.toULong() > allocationLimit) fail("RESOURCE_LIMIT_EXCEEDED", "Read buffer exceeds its allocation budget", Stage.Read)
        val targetSize = checkedInt(length.toULong())
        val bytes = ByteArray(targetSize)
        var completed = 0
        while (completed < targetSize) {
            checkCancelled(context)
            val requested = (targetSize - completed).toUInt()
            val part = source.readAt(offset + completed.toULong(), requested).orThrow()
            if (part.size == 0) fail("UNEXPECTED_EOF", "Source returned no bytes before the requested end", Stage.Read)
            if (part.size.toUInt() > requested) fail("IO_READ_FAILED", "Source returned more bytes than requested", Stage.Read)
            part.copyInto(bytes, completed)
            completed += part.size
        }
        checkIdentity()
        Bytes(bytes)
    }

    suspend fun readU16(offset: ULong, endian: Endian = Endian.Big): CoreResult<UShort> = attempt {
        readUnsigned(readExactly(offset, 2u).orThrow(), endian).toUShort()
    }
    suspend fun readU32(offset: ULong, endian: Endian = Endian.Big): CoreResult<UInt> = attempt {
        readUnsigned(readExactly(offset, 4u).orThrow(), endian).toUInt()
    }
    suspend fun readU64(offset: ULong, endian: Endian = Endian.Big): CoreResult<ULong> = attempt {
        readUnsigned(readExactly(offset, 8u).orThrow(), endian)
    }
}

/** Shared by every asset/range writer belonging to one mutation. */
internal class OutputBudget(private val context: Context) {
    private var written: ULong = 0uL
    fun checkCapacity(length: ULong): Unit {
        if (length > context.limits.maxOutputBytes - written) fail("RESOURCE_LIMIT_EXCEEDED", "Output byte budget exceeded", Stage.WriteProtocol)
    }
    fun consume(length: ULong): Unit { checkCapacity(length); written += length }
}

internal class BinaryWriter(private val sink: BinarySink, private val context: Context, val budget: OutputBudget = OutputBudget(context)) {

    suspend fun writeAll(bytes: Bytes): CoreResult<Unit> = attempt<Unit> {
        checkCancelled(context)
        budget.checkCapacity(bytes.size.toULong())
        var completed = 0
        while (completed < bytes.size) {
            checkCancelled(context)
            val count = sink.write(bytes.slice(completed)).orThrow()
            val remaining = (bytes.size - completed).toUInt()
            if (count == 0u || count > remaining) fail("IO_WRITE_FAILED", "Sink made no progress or reported an invalid write length", Stage.WriteProtocol)
            completed += count.toInt()
            budget.consume(count.toULong())
        }
    }

    suspend fun writeU16(value: UShort, endian: Endian = Endian.Big): CoreResult<Unit> = writeAll(unsignedBytes(value.toULong(), 2, endian))
    suspend fun writeU32(value: UInt, endian: Endian = Endian.Big): CoreResult<Unit> = writeAll(unsignedBytes(value.toULong(), 4, endian))
    suspend fun writeU64(value: ULong, endian: Endian = Endian.Big): CoreResult<Unit> = writeAll(unsignedBytes(value, 8, endian))
}

internal suspend fun copyRange(
    reader: BinaryReader,
    sink: BinarySink,
    range: ByteRange,
    context: Context,
    chunkBytes: UInt = 64u * 1024u,
): CoreResult<Unit> = copyRange(reader, BinaryWriter(sink, context), range, context, chunkBytes)

/** Compose copies and generated metadata through one writer (or writers sharing OutputBudget). */
internal suspend fun copyRange(
    reader: BinaryReader,
    writer: BinaryWriter,
    range: ByteRange,
    context: Context,
    chunkBytes: UInt = 64u * 1024u,
): CoreResult<Unit> = attempt {
    if (chunkBytes == 0u) fail("INVALID_ARGUMENT", "Copy chunk must be nonzero", Stage.Extract)
    if (range.length > context.limits.maxOutputBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Copy exceeds the output budget", Stage.Extract)
    checkedRange(range.offset, range.length, reader.identity().orThrow().size)
    writer.budget.checkCapacity(range.length)
    var completed = 0uL
    while (completed < range.length) {
        checkCancelled(context)
        val count = minOf(chunkBytes.toULong(), 64uL * 1024uL, range.length - completed).toUInt()
        writer.writeAll(reader.readBuffer(range.offset + completed, count).orThrow()).orThrow()
        completed += count.toULong()
    }
    reader.validateIdentity().orThrow()
}

internal fun readUnsigned(bytes: Bytes, endian: Endian): ULong {
    if (bytes.size !in 1..8) fail("INVALID_ARGUMENT", "Integer byte width must be in 1..8")
    var value = 0uL
    val indices = if (endian == Endian.Big) bytes.indices() else bytes.indices().reversed()
    for (index in indices) value = (value shl 8) or (bytes[index].toInt() and 0xff).toULong()
    return value
}

private fun Bytes.indices(): IntRange = 0 until size

internal fun unsignedBytes(value: ULong, width: Int, endian: Endian): Bytes {
    if (width !in 1..8) fail("INVALID_ARGUMENT", "Integer byte width must be in 1..8")
    if (width < 8 && value >= (1uL shl (width * 8))) fail("VALUE_NOT_REPRESENTABLE", "Integer does not fit its encoded width")
    val bytes = ByteArray(width)
    for (index in 0 until width) {
        val destination = if (endian == Endian.Big) width - index - 1 else index
        bytes[destination] = (value shr (index * 8)).toByte()
    }
    return Bytes(bytes)
}
