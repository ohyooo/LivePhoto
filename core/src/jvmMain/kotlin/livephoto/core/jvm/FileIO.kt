package livephoto.core.jvm

import livephoto.core.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

private fun <T> io(stage: Stage, block: () -> T): CoreResult<T> = try { CoreResult.Success(block()) }
catch (e: Exception) { CoreResult.Failure(CoreError(IssueCode(when (e) {
    is FileAlreadyExistsException -> "OUTPUT_EXISTS"
    is AtomicMoveNotSupportedException -> "ATOMIC_PUBLICATION_UNAVAILABLE"
    else -> if (stage == Stage.Read) "IO_READ_FAILED" else "IO_WRITE_FAILED"
}), stage, "File adapter operation failed: ${e.javaClass.simpleName}")) }

/** Random access without persistent open handles, so prepared readers survive directory publication. */
public class FileBinarySource private constructor(private val path: () -> Path, private val id: SourceId) : BinarySource {
    public constructor(path: Path) : this({ path.toAbsolutePath().normalize() }, SourceId(path.toAbsolutePath().normalize().toString()))
    private var closed = false
    private fun attributes(): BasicFileAttributes {
        check(!closed) { "Source is closed" }
        val attributes = Files.readAttributes(path(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile) { "Source must be a regular file, not a symbolic link" }
        return attributes
    }
    private fun identityNow(): SourceIdentity {
        val a = attributes()
        return SourceIdentity(id, GenerationToken("${a.fileKey()}:${a.size()}:${a.lastModifiedTime()}:${a.creationTime()}"), a.size().toULong())
    }
    override suspend fun identity(): CoreResult<SourceIdentity> = io(Stage.Read) { identityNow() }
    override suspend fun size(): CoreResult<ULong> = io(Stage.Read) { attributes().size().toULong() }
    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = io(Stage.Read) {
        require(offset <= Long.MAX_VALUE.toULong() && length <= 64u * 1024u * 1024u) { "Read exceeds adapter bounds" }
        val before = identityNow()
        require(offset <= before.size) { "Read offset exceeds source" }
        val buffer = ByteBuffer.allocate(minOf(length.toULong(), before.size - offset).toInt())
        FileChannel.open(path(), StandardOpenOption.READ).use { channel ->
            var position = offset.toLong()
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, position)
                check(n > 0) { "Source changed or was truncated during read" }
                position += n
            }
        }
        check(identityNow() == before) { "Source changed during read" }
        Bytes(buffer.array())
    }
    override suspend fun close() { closed = true }
    internal companion object {
        fun relocating(path: () -> Path, id: SourceId): FileBinarySource = FileBinarySource(path, id)
    }
}

/**
 * New-directory-only publication. Reserves a fresh output root, writes a private staging
 * directory, then atomically exposes the entire asset set at root/assets. Never replaces
 * existing output or input paths. Atomic rename failure leaves no partially published set.
 * The empty root can be visible before commit; media are only published under assets.
 */
public class DirectoryOutputTransaction(path: Path, private val context: Context) : OutputTransaction {
    public val root: Path = path.toAbsolutePath().normalize()
    private val staging = root.resolve(".staging")
    private val published = root.resolve("assets")
    private val token = UUID.randomUUID().toString()
    private var state = TransactionState.Open
    private var ownedRoot = false
    private var allocated = 0uL
    private data class Entry(val name: String, val sink: FileSink)
    private val entries = linkedMapOf<AssetId, Entry>()
    override fun capabilities(): OutputCapabilities = OutputCapabilities(true, true, false, false, true)
    public fun assetPath(id: AssetId): Path = published.resolve(entries.getValue(id).name)
    override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = io(Stage.WriteProtocol) {
        check(state == TransactionState.Open)
        check(context.cancellation?.isCancelled() != true) { "Cancelled" }
        require(entries.size.toULong() < context.limits.maxItems)
        if (!ownedRoot) {
            Files.createDirectory(root) // CREATE_NEW semantics, including symlinks and existing empty directories.
            ownedRoot = true
            Files.createDirectory(staging)
        }
        val id = AssetId("asset-${entries.size + 1}")
        val extension = when (spec.mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/heic" -> "heic"; "video/mp4" -> "mp4"; "video/quicktime" -> "mov"; else -> "bin" }
        val name = "${id.value}.$extension"
        val sink = FileSink(staging.resolve(name))
        entries[id] = Entry(name, sink)
        OutputHandle(id, sink)
    }
    override suspend fun prepare(): CoreResult<Unit> = io(Stage.Publish) {
        check(state == TransactionState.Open && entries.isNotEmpty())
        entries.values.forEach { it.sink.finish() }
        state = TransactionState.Prepared
    }
    override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> = io(Stage.Read) {
        check(state in setOf(TransactionState.Prepared, TransactionState.Committed))
        val name = entries.getValue(id).name
        FileBinarySource.relocating({ (if (state == TransactionState.Committed) published else staging).resolve(name) }, SourceId("$token:${id.value}"))
    }
    override suspend fun commit(): CoreResult<Receipt> = io(Stage.Publish) {
        check(state == TransactionState.Prepared)
        check(!Files.exists(published, LinkOption.NOFOLLOW_LINKS)) { "Reserved publication path was modified" }
        Files.move(staging, published, StandardCopyOption.ATOMIC_MOVE)
        state = TransactionState.Committed
        receipt()
    }
    override suspend fun abort(): CoreResult<Unit> = io(Stage.Publish) {
        check(state != TransactionState.Committed) { "Cannot abort committed output" }
        if (ownedRoot) {
            entries.values.forEach { it.sink.finish(); Files.deleteIfExists(staging.resolve(it.name)) }
            Files.deleteIfExists(staging)
            Files.deleteIfExists(root)
            ownedRoot = false
        }
        state = TransactionState.Aborted
    }
    override suspend fun query(): CoreResult<Receipt> = CoreResult.Success(receipt())
    private fun receipt(): Receipt = Receipt(token, state, entries.keys.toList(), Atomicity.AssetSetRequired, "atomic visibility; directory fsync not guaranteed")

    private inner class FileSink(path: Path) : BinarySink {
        private val channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        private var length = 0uL
        fun finish() { if (channel.isOpen) { channel.force(true); channel.close() } }
        private fun grow(newLength: ULong) {
            require(newLength <= Long.MAX_VALUE.toULong())
            val additional = if (newLength > length) newLength - length else 0uL
            require(additional <= context.limits.maxOutputBytes - allocated) { "Output budget exceeded" }
            allocated += additional
            length = maxOf(length, newLength)
        }
        override suspend fun write(bytes: Bytes): CoreResult<UInt> = io(Stage.WriteProtocol) {
            check(state == TransactionState.Open && channel.isOpen)
            grow(channel.position().toULong() + bytes.size.toULong())
            val buffer = ByteBuffer.wrap(bytes.toByteArray())
            while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
            bytes.size.toUInt()
        }
        override suspend fun seek(offset: ULong): CoreResult<Unit> = io(Stage.WriteProtocol) {
            check(state == TransactionState.Open && channel.isOpen)
            require(offset <= Long.MAX_VALUE.toULong()); channel.position(offset.toLong()); Unit
        }
        override suspend fun truncate(length: ULong): CoreResult<Unit> = io(Stage.WriteProtocol) {
            check(state == TransactionState.Open && channel.isOpen)
            require(length <= this.length) { "Truncate cannot extend a file" }
            channel.truncate(length.toLong()); allocated -= this.length - length; this.length = length
        }
        override suspend fun flush(): CoreResult<Unit> = io(Stage.WriteProtocol) { channel.force(true) }
        override suspend fun close(): CoreResult<Unit> = io(Stage.WriteProtocol) { finish() }
    }
}
