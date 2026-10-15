package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*

/** Borrowed bounded view; closing the view never closes its input. */
internal class RangeSource(private val reader: BinaryReader, private val range: ByteRange) : BinarySource {
    override suspend fun identity(): CoreResult<SourceIdentity> = attempt {
        val original = reader.identity().orThrow()
        checkedRange(range.offset, range.length, original.size)
        original.copy(id = SourceId("${original.id.value}:range:${range.offset}:${range.length}"), size = range.length, digest = null)
    }
    override suspend fun size(): CoreResult<ULong> = attempt { reader.validateIdentity().orThrow(); range.length }
    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = attempt {
        checkedRange(offset, length.toULong(), range.length)
        reader.readBuffer(checkedAdd(range.offset, offset), length).orThrow()
    }
    override suspend fun close(): Unit = Unit
}
