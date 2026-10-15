package livephoto.adapters.files

import livephoto.core.*
import okio.*
import okio.ByteString.Companion.toByteString

internal const val FILE_BLOCK_BYTES: Int = 65536

/** An immutable content identity plus bounded per-block proofs, not a mutable file's mtime. */
internal class FrozenFile(val length: Long, val digest: Digest, chunks: List<ByteString>) {
    val chunks = chunks.toList()
}
internal fun freezeFile(handle: FileHandle, length: Long, expected: Digest, context: Context): FrozenFile {
    val count = length / FILE_BLOCK_BYTES + if (length % FILE_BLOCK_BYTES != 0L) 1L else 0L
    if (count > Int.MAX_VALUE || count.toULong() > context.limits.maxItems ||
        count.toULong() > context.limits.maxMetadataBytes / 32uL) {
        reject("RESOURCE_LIMIT_EXCEEDED", "Frozen-file proof budget exceeded")
    }
    if (handle.size() != length) reject("SOURCE_CHANGED", "Snapshot size changed")
    val chunks = mutableListOf<ByteString>()
    val array = ByteArray(FILE_BLOCK_BYTES)
    val discarded = Buffer()
    var offset = 0L
    val digest = HashingSink.sha256(discarded).use { sink ->
        while (offset < length) {
            poll(context)
            val size = minOf(FILE_BLOCK_BYTES.toLong(), length - offset).toInt()
            readFileBlock(handle, offset, array, size, context)
            chunks += array.toByteString(0, size).sha256()
            sink.write(Buffer().write(array, 0, size), size.toLong())
            discarded.clear()
            offset += size
        }
        if (handle.size() != length) reject("SOURCE_CHANGED", "Snapshot size changed")
        Digest(sink.hash.hex())
    }
    poll(context)
    if (digest != expected) reject("SOURCE_CHANGED", "Snapshot content changed during freezing")
    return FrozenFile(length, digest, chunks)
}
internal fun readFileBlock(handle: FileHandle, offset: Long, array: ByteArray, size: Int, context: Context) {
    var completed = 0
    while (completed < size) {
        poll(context)
        val n = handle.read(offset + completed, array, completed, size - completed)
        if (n <= 0) reject("SOURCE_CHANGED", "Frozen source ended unexpectedly")
        completed += n
    }
}
