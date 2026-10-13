package livephoto.adapters.files

import livephoto.core.*
import okio.*
import okio.ByteString.Companion.toByteString

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
    private val proof: FrozenFile,
    private val context: Context,
) : BinarySource {
    private var closed = false
    internal val isReleased: Boolean get() = closed
    private val length: Long get() = proof.length
    private fun checkFile(): Path {
        if (closed) reject("IO_READ_FAILED", "Source is closed")
        poll(context)
        val file = path()
        if (regular(fileSystem, file).size != length) reject("SOURCE_CHANGED", "Frozen source size changed")
        return file
    }
    override suspend fun identity(): CoreResult<SourceIdentity> = fileResult(Stage.Read) {
        // This identity describes the owned immutable content, not the mutable original.
        // Every returned byte is checked against its frozen block proof in readAt().
        checkFile()
        SourceIdentity(id, generation, length.toULong(), proof.digest)
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
            val block = ByteArray(FILE_BLOCK_BYTES)
            while (read < count) {
                poll(context)
                val position = offset.toLong() + read
                val blockIndex = (position / FILE_BLOCK_BYTES).toInt()
                val blockStart = position / FILE_BLOCK_BYTES * FILE_BLOCK_BYTES
                val blockSize = minOf(FILE_BLOCK_BYTES.toLong(), this.length - blockStart).toInt()
                readFileBlock(handle, blockStart, block, blockSize, context)
                if (block.toByteString(0, blockSize).sha256() != proof.chunks[blockIndex]) {
                    reject("SOURCE_CHANGED", "Frozen source content changed")
                }
                val from = (position - blockStart).toInt()
                val n = minOf(count - read, blockSize - from)
                block.copyInto(bytes, read, from, from + n)
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
