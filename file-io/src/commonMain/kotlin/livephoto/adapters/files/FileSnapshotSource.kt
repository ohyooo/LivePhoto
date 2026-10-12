package livephoto.adapters.files

import livephoto.core.*
import okio.*

/**
 * Reads a frozen file owned by the adapter, never the caller's mutable input.
 * No persistent handle: staged readers remain usable after directory publication on Windows.
 * Confined to one serialized operation; the owner must keep its private workspace alive.
 */
internal class FileSnapshotSource(
    private val fileSystem: FileSystem,
    private val path: () -> Path,
    private val id: SourceId,
    private val generation: GenerationToken,
    private val length: Long,
    private val digest: Digest,
    private val context: Context,
) : BinarySource {
    private var closed = false
    private fun checkFile(): Path {
        if (closed) reject("IO_READ_FAILED", "Source is closed")
        poll(context)
        val file = path()
        if (regular(fileSystem, file).size != length) reject("SOURCE_CHANGED", "Frozen source size changed")
        return file
    }
    override suspend fun identity(): CoreResult<SourceIdentity> = fileResult(Stage.Read) {
        fileSystem.openReadOnly(checkFile()).use {
            if (digestFile(it, length, context) != digest) reject("SOURCE_CHANGED", "Frozen source content changed")
        }
        SourceIdentity(id, generation, length.toULong(), digest)
    }
    override suspend fun size(): CoreResult<ULong> = fileResult(Stage.Read) { checkFile(); length.toULong() }
    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = fileResult(Stage.Read) {
        val file = checkFile()
        if (offset > this.length.toULong()) reject("OFFSET_OUT_OF_BOUNDS", "Read offset exceeds source")
        if (length > 64u * 1024u * 1024u) reject("RESOURCE_LIMIT_EXCEEDED", "Read exceeds adapter buffer limit")
        val count = minOf(length.toULong(), this.length.toULong() - offset).toInt()
        val bytes = ByteArray(count)
        fileSystem.openReadOnly(file).use { handle ->
            if (handle.size() != this.length) reject("SOURCE_CHANGED", "Frozen source size changed")
            var read = 0
            while (read < count) {
                poll(context)
                val n = handle.read(offset.toLong() + read, bytes, read, count - read)
                if (n <= 0) reject("SOURCE_CHANGED", "Frozen source ended unexpectedly")
                read += n
            }
            if (handle.size() != this.length) reject("SOURCE_CHANGED", "Frozen source size changed")
        }
        poll(context)
        Bytes(bytes)
    }
    override suspend fun close() { release() }
    internal fun release() { closed = true }
}
