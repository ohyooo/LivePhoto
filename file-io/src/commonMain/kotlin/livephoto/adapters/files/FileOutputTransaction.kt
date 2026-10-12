package livephoto.adapters.files

import livephoto.core.*
import okio.*

/**
 * New-directory-only, serialized, local-file-system transaction. Parent must be
 * caller-controlled and trusted. One same-parent directory rename publishes all assets.
 * No overwrite, cross-volume fallback, directory fsync promise or recursive deletion.
 * Only adapters honoring FileSystem.atomicMove's contract may be injected.
 */
public class FileOutputTransaction(
    root: String,
    private val context: Context,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : OutputTransaction {
    private val requestedRoot = root
    private val token = newToken()
    private var directory: Path? = null
    private var ownsStaging = false
    private var ownsMarker = false
    private var state = TransactionState.Open
    private var allocated = 0uL
    private val markerName = ".transaction-$token"
    private val entries = linkedMapOf<AssetId, Entry>()
    private data class Entry(val name: String, val sink: FileSink, var proof: FrozenFile? = null)
    private fun staging(): Path = checkNotNull(directory) / ".staging"
    private fun published(): Path = checkNotNull(directory) / "assets"
    override fun capabilities(): OutputCapabilities = OutputCapabilities(true, true, false, false, true)

    /** CLI-only path resolver; Core still receives opaque asset IDs and BinarySource. */
    public fun assetPath(id: AssetId): String = (published() / entries.getValue(id).name).toString()
    private fun requireState(expected: TransactionState) {
        if (state != expected) reject("INVALID_ARGUMENT", "Transaction is not in the required state")
    }
    override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = fileResult(Stage.WriteProtocol) {
        requireState(TransactionState.Open); poll(context)
        if (entries.size.toULong() >= context.limits.maxItems) reject("RESOURCE_LIMIT_EXCEEDED", "Asset count budget exceeded")
        if (directory == null) {
            val root = freshRoot(fileSystem, requestedRoot)
            reserve(fileSystem, root)
            directory = root
            fileSystem.createDirectory(staging(), mustCreate = true)
            ownsStaging = true
            fileSystem.openReadWrite(staging() / markerName, mustCreate = true).use { it.flush() }
            ownsMarker = true
        }
        if (!ownsStaging || !ownsMarker) reject("IO_WRITE_FAILED", "Staging initialization was incomplete")
        val id = AssetId("asset-${entries.size + 1}")
        val extension = when (spec.mime) {
            "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/heic" -> "heic"; "image/avif" -> "avif"
            "video/mp4" -> "mp4"; "video/quicktime" -> "mov"; else -> "bin"
        }
        val name = "${id.value}.$extension" // Never use an untrusted recommendedName as a path.
        val sink = FileSink(fileSystem.openReadWrite(staging() / name, mustCreate = true))
        entries[id] = Entry(name, sink)
        OutputHandle(id, sink)
    }
    private fun checkTree(path: Path) {
        val metadata = fileSystem.metadata(path)
        if (!metadata.isDirectory || metadata.symlinkTarget != null ||
            fileSystem === FileSystem.SYSTEM && systemPathIsLink(path)) {
            reject("IO_WRITE_FAILED", "Reserved directory was modified")
        }
        val expected = entries.values.map { path / it.name }.toSet() + (path / markerName)
        if (fileSystem.list(path).toSet() != expected) reject("IO_WRITE_FAILED", "Reserved contents were modified")
        if (regular(fileSystem, path / markerName).size != 0L) reject("IO_WRITE_FAILED", "Transaction marker was modified")
    }
    override suspend fun prepare(): CoreResult<Unit> = fileResult(Stage.Publish) {
        requireState(TransactionState.Open); poll(context)
        if (entries.isEmpty()) reject("INVALID_ARGUMENT", "Cannot prepare an empty asset set")
        checkTree(staging())
        var proofItems = 0uL
        entries.values.forEach {
            val blocks = it.sink.length / FILE_BLOCK_BYTES.toULong() +
                if (it.sink.length % FILE_BLOCK_BYTES.toULong() != 0uL) 1uL else 0uL
            if (blocks > context.limits.maxItems - proofItems ||
                blocks > context.limits.maxMetadataBytes / 32uL - proofItems) {
                reject("RESOURCE_LIMIT_EXCEEDED", "Asset-set proof budget exceeded")
            }
            proofItems += blocks
        }
        entries.values.forEach { entry ->
            entry.sink.finish()
            entry.proof = fileSystem.openReadOnly(staging() / entry.name).use {
                val digest = digestFile(it, entry.sink.length.toLong(), context)
                freezeFile(it, entry.sink.length.toLong(), digest, context)
            }
        }
        state = TransactionState.Prepared
    }
    override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> = fileResult(Stage.Read) {
        poll(context)
        if (state !in setOf(TransactionState.Prepared, TransactionState.Committed)) {
            reject("INVALID_ARGUMENT", "Staged reads require prepared output")
        }
        val entry = entries[id] ?: reject("INVALID_ARGUMENT", "Unknown asset")
        FileSnapshotSource(fileSystem, { (if (state == TransactionState.Committed) published() else staging()) / entry.name },
            SourceId("$token:${id.value}"), GenerationToken("$token:${entry.proof!!.digest.value}"),
            entry.proof!!, context)
    }
    override suspend fun commit(): CoreResult<Receipt> = fileResult(Stage.Publish) {
        requireState(TransactionState.Prepared); poll(context)
        checkTree(staging())
        if (fileSystem.metadataOrNull(published()) != null ||
            fileSystem === FileSystem.SYSTEM && systemPathIsLink(published())) {
            reject("OUTPUT_EXISTS", "Reserved publication path already exists")
        }
        try {
            fileSystem.atomicMove(staging(), published())
            state = TransactionState.Committed
        } catch (_: Exception) {
            // A provider may have moved the directory before losing its response.
            // Never blindly retry the move or remove possibly committed assets.
            state = TransactionState.Indeterminate
            reject("COMMIT_OUTCOME_UNKNOWN", "Publication outcome is uncertain; query before retrying")
        }
        receipt()
    }
    private fun recover() {
        if (state != TransactionState.Indeterminate) return
        val staged = fileSystem.metadataOrNull(staging())
        val visible = fileSystem.metadataOrNull(published())
        if (staged != null && visible == null) {
            checkTree(staging())
            state = TransactionState.Prepared
        } else if (staged == null && visible != null) {
            checkTree(published())
            val recoveryContext = Context(context.limits)
            entries.values.forEach { entry ->
                val digest = fileSystem.openReadOnly(published() / entry.name).use {
                    digestFile(it, entry.sink.length.toLong(), recoveryContext)
                }
                if (digest != entry.proof!!.digest) reject("COMMIT_OUTCOME_UNKNOWN", "Published assets could not be verified")
            }
            state = TransactionState.Committed
        } else {
            reject("COMMIT_OUTCOME_UNKNOWN", "Publication outcome is still uncertain")
        }
    }
    override suspend fun query(): CoreResult<Receipt> = fileResult(Stage.Publish) { recover(); receipt() }
    override suspend fun abort(): CoreResult<Unit> = fileResult(Stage.Publish) {
        recover()
        if (state == TransactionState.Committed) reject("INVALID_ARGUMENT", "Committed assets cannot be aborted")
        entries.values.forEach { it.sink.finish() }
        if (ownsStaging) {
            if (ownsMarker) checkTree(staging())
            entries.values.forEach { fileSystem.delete(staging() / it.name) }
            if (ownsMarker) fileSystem.delete(staging() / markerName)
            // These are non-recursive deletes. Unknown children stop cleanup, never get deleted.
            fileSystem.delete(staging(), mustExist = true)
            ownsStaging = false; ownsMarker = false
        }
        directory?.let { fileSystem.delete(it, mustExist = true); directory = null }
        allocated = 0uL
        state = TransactionState.Aborted
    }
    private fun receipt(): Receipt = Receipt(token, state, entries.keys.toList(), Atomicity.AssetSetRequired,
        "atomic directory visibility; directory fsync not guaranteed")

    private inner class FileSink(private var handle: FileHandle?) : BinarySink {
        var length = 0uL
            private set
        private var position = 0uL
        private fun writer(): FileHandle {
            requireState(TransactionState.Open); poll(context)
            return handle ?: reject("INVALID_ARGUMENT", "Writer is closed")
        }
        fun finish() {
            handle?.let { it.flush(); it.close(); handle = null }
        }
        override suspend fun write(bytes: Bytes): CoreResult<UInt> = fileResult(Stage.WriteProtocol) {
            val file = writer()
            if (bytes.size == 0) return@fileResult 0u
            if (bytes.size.toULong() > Long.MAX_VALUE.toULong() - position) reject("INTEGER_OVERFLOW", "Write range exceeds file offsets")
            val end = position + bytes.size.toULong()
            val growth = if (end > length) end - length else 0uL
            if (growth > context.limits.maxOutputBytes - allocated) reject("RESOURCE_LIMIT_EXCEEDED", "Asset-set output budget exceeded")
            allocated += growth; length = maxOf(length, end)
            val array = bytes.toByteArray()
            file.write(position.toLong(), array, 0, array.size)
            position = end
            bytes.size.toUInt()
        }
        override suspend fun seek(offset: ULong): CoreResult<Unit> = fileResult(Stage.WriteProtocol) {
            writer()
            if (offset > Long.MAX_VALUE.toULong()) reject("OFFSET_OUT_OF_BOUNDS", "Seek exceeds file offset range")
            position = offset
        }
        override suspend fun truncate(length: ULong): CoreResult<Unit> = fileResult(Stage.WriteProtocol) {
            val file = writer()
            if (length > this.length || length > file.size().toULong()) reject("INVALID_ARGUMENT", "Truncate cannot extend a file")
            file.resize(length.toLong())
            allocated -= this.length - length; this.length = length
            position = minOf(position, length)
        }
        override suspend fun flush(): CoreResult<Unit> = fileResult(Stage.WriteProtocol) { writer().flush() }
        override suspend fun close(): CoreResult<Unit> = fileResult(Stage.WriteProtocol) { finish() }
    }
}
