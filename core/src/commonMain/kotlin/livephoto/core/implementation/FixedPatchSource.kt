package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*

/** Fixed-width replacements never relocate any retained byte. A null value is a streamed zero fill. */
internal data class FixedPatch(val range: ByteRange, val value: Bytes? = null)
internal class FixedPatchSource private constructor(
    private val reader: BinaryReader, private val patches: List<FixedPatch>, private val view: SourceIdentity,
) : BinarySource {
    override suspend fun identity(): CoreResult<SourceIdentity> = attempt { reader.validateIdentity().orThrow(); view }
    override suspend fun size(): CoreResult<ULong> = attempt { reader.validateIdentity().orThrow(); view.size }
    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = attempt {
        checkedRange(offset, length.toULong(), view.size)
        if (length > 65_536u) fail("RESOURCE_LIMIT_EXCEEDED", "Patched views require bounded IO buffers")
        val bytes = reader.readBuffer(offset, length).orThrow().toByteArray()
        val end = offset + length.toULong()
        for (patch in patches) {
            checkCancelled(reader.context)
            val start = maxOf(offset, patch.range.offset)
            val stop = minOf(end, patch.range.endExclusive)
            if (start >= stop) continue
            if (patch.value == null) bytes.fill(0, checkedInt(start - offset), checkedInt(stop - offset))
            else patch.value.slice(checkedInt(start - patch.range.offset), checkedInt(stop - patch.range.offset)).copyInto(bytes, checkedInt(start - offset))
        }
        reader.validateIdentity().orThrow()
        Bytes(bytes)
    }
    override suspend fun close(): Unit = Unit

    companion object {
        suspend fun create(reader: BinaryReader, patches: List<FixedPatch>): CoreResult<FixedPatchSource> = attempt {
            val identity = reader.identity().orThrow()
            val ordered = patches.sortedBy { it.range.offset }
            var end = 0uL
            val hash = Sha256()
            for (patch in ordered) {
                checkCancelled(reader.context)
                checkedRange(patch.range.offset, patch.range.length, identity.size)
                if (patch.range.offset < end || patch.value != null && patch.range.length != patch.value.size.toULong()) fail("INVALID_ARGUMENT", "Fixed patches overlap or change length")
                hash.update(unsignedBytes(patch.range.offset, 8, Endian.Big)); hash.update(unsignedBytes(patch.range.length, 8, Endian.Big))
                hash.update(Bytes(byteArrayOf(if (patch.value == null) 0 else 1)))
                patch.value?.let(hash::update)
                end = patch.range.endExclusive
            }
            FixedPatchSource(reader, frozenList(ordered), identity.copy(id = SourceId("${identity.id.value}:fixed-patch:${hash.finish().value}"), digest = null))
        }
    }
}
