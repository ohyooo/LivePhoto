package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.jpeg.*

/** Streamable read-only JPEG rewrite view. No materialized media or intermediate public commit. */
internal class JpegProjectionSource private constructor(
    private val reader: BinaryReader,
    private val parts: List<Part>,
    private val viewIdentity: SourceIdentity,
) : BinarySource {
    private data class Part(val start: ULong, val length: ULong, val range: ByteRange? = null, val bytes: Bytes? = null)
    override suspend fun identity(): CoreResult<SourceIdentity> = attempt { reader.validateIdentity().orThrow(); viewIdentity }
    override suspend fun size(): CoreResult<ULong> = attempt { reader.validateIdentity().orThrow(); viewIdentity.size }
    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = attempt {
        checkedRange(offset, length.toULong(), viewIdentity.size)
        if (length > 65_536u) fail("RESOURCE_LIMIT_EXCEEDED", "Projection reads must use bounded IO buffers")
        val result = ByteArray(length.toInt())
        var copied = 0
        var cursor = offset
        for (part in parts) {
            checkCancelled(reader.context)
            if (copied == result.size) break
            if (cursor < part.start || cursor >= part.start + part.length) continue
            val relative = cursor - part.start
            val count = minOf((result.size - copied).toULong(), part.length - relative).toUInt()
            val bytes = part.bytes?.slice(checkedInt(relative), checkedInt(relative + count.toULong()))
                ?: reader.readBuffer(part.range!!.offset + relative, count).orThrow()
            bytes.copyInto(result, copied)
            copied += bytes.size
            cursor += bytes.size.toULong()
        }
        if (copied != result.size) fail("UNEXPECTED_EOF", "JPEG projection is incomplete")
        reader.validateIdentity().orThrow()
        Bytes(result)
    }
    override suspend fun close(): Unit = Unit

    companion object {
        suspend fun create(session: SourceSession, plan: JpegRewritePlan, includeTrailing: Boolean = false): CoreResult<JpegProjectionSource> = attempt {
            val jpeg = session.jpeg ?: fail("UNSUPPORTED_CONTAINER", "JPEG projection requires parsed image content")
            val verified = JpegRewrite.plan(jpeg, plan.patches).orThrow()
            if (verified.outputLength != plan.outputLength) fail("INVALID_ARGUMENT", "Projection length differs from verified rewrite")
            val identity = session.reader.identity().orThrow()
            val parts = mutableListOf<Part>()
            var position = 0uL
            var output = 0uL
            val hash = Sha256()
            for (patch in verified.patches) {
                patch.appleProof?.let { proof ->
                    if (proof.sourceIdentity != identity) fail("SOURCE_CHANGED", "Apple projection authorization no longer matches input")
                }
                patch.exifProof?.let { proof ->
                    if (proof.sourceIdentity != identity || proof.originalTiffRange != null && sha256Range(session.reader, proof.originalTiffRange).orThrow() != proof.originalTiffDigest)
                        fail("SOURCE_CHANGED", "EXIF projection proof no longer matches the source")
                }
                val range = ByteRange(position, patch.range.offset - position)
                if (range.length != 0uL) { parts += Part(output, range.length, range); output = checkedAdd(output, range.length) }
                if (patch.replacement.size != 0) { parts += Part(output, patch.replacement.size.toULong(), bytes = patch.replacement); output = checkedAdd(output, patch.replacement.size.toULong()) }
                hash.update(unsignedBytes(patch.range.offset, 8, Endian.Big)); hash.update(unsignedBytes(patch.range.length, 8, Endian.Big)); hash.update(patch.replacement)
                position = patch.range.endExclusive
            }
            val remainder = ByteRange(position, (if (includeTrailing) identity.size else jpeg.primary.endExclusive) - position)
            if (remainder.length != 0uL) parts += Part(output, remainder.length, remainder)
            val view = identity.copy(id = SourceId("${identity.id.value}:jpeg-projection:${hash.finish().value}${if (includeTrailing) ":full" else ""}"),
                size = if (includeTrailing) checkedAdd(verified.outputLength, jpeg.trailing.length) else verified.outputLength, digest = null)
            JpegProjectionSource(session.reader, frozenList(parts), view)
        }
    }
}
