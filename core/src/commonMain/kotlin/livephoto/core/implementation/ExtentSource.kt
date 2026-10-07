package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*

/** Read-only concatenation in declared extent order; never owns or closes the underlying source. */
internal class ExtentSource private constructor(
    private val reader: BinaryReader, private val ranges: List<ByteRange>, private val starts: List<ULong>, private val view: SourceIdentity,
) : BinarySource {
    override suspend fun identity(): CoreResult<SourceIdentity> = attempt { reader.validateIdentity().orThrow(); view }
    override suspend fun size(): CoreResult<ULong> = attempt { reader.validateIdentity().orThrow(); view.size }
    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = attempt {
        reader.validateIdentity().orThrow()
        checkedRange(offset, length.toULong(), view.size)
        if (length > 65_536u) fail("RESOURCE_LIMIT_EXCEEDED", "Extent views require bounded read buffers")
        val bytes = ByteArray(length.toInt())
        var done = 0
        while (done < bytes.size) {
            checkCancelled(reader.context)
            val logical = offset + done.toULong()
            var low = 0
            var high = starts.size
            while (low + 1 < high) {
                val middle = low + (high - low) / 2
                if (starts[middle] <= logical) low = middle else high = middle
            }
            val extent = ranges[low]
            val inside = logical - starts[low]
            val count = minOf(extent.length - inside, (bytes.size - done).toULong()).toUInt()
            reader.readBuffer(checkedAdd(extent.offset, inside), count).orThrow().copyInto(bytes, done)
            done += count.toInt()
        }
        reader.validateIdentity().orThrow()
        Bytes(bytes)
    }
    override suspend fun close(): Unit = Unit

    companion object {
        suspend fun create(reader: BinaryReader, ranges: List<ByteRange>, budget: ParseBudget = ParseBudget(reader.context)): CoreResult<ExtentSource> = attempt {
            val identity = reader.identity().orThrow()
            if (ranges.isEmpty()) fail("INVALID_ARGUMENT", "Extent view requires at least one range")
            val hash = Sha256()
            val starts = mutableListOf<ULong>()
            var size = 0uL
            for (range in ranges) {
                budget.item(); budget.retain(48uL)
                checkedRange(range.offset, range.length, identity.size)
                if (range.length == 0uL) fail("INVALID_ARGUMENT", "Extent view has an empty range")
                starts.add(size); size = checkedAdd(size, range.length)
                hash.update(unsignedBytes(range.offset, 8, Endian.Big)); hash.update(unsignedBytes(range.length, 8, Endian.Big))
            }
            reader.validateIdentity().orThrow()
            ExtentSource(reader, frozenList(ranges), frozenList(starts), identity.copy(
                id = SourceId("${identity.id.value}:extents:${hash.finish().value}"), size = size, digest = null))
        }
    }
}
