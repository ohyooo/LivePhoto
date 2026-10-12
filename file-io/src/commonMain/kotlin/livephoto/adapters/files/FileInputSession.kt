package livephoto.adapters.files

import livephoto.core.*
import okio.*
import okio.Path.Companion.toPath

/**
 * Bounded immutable input snapshots. Parent must be caller-controlled, local and trusted,
 * not a shared writable temp directory. No recursive cleanup or original-file deletion.
 * Sources are borrowed until close(); closing a source alone does not release spool storage.
 * Calls must be serialized. Concurrent acquisition changes are rejected by two content passes;
 * this is not an OS snapshot/lock guarantee against a hostile writer.
 */
public class FileInputSession(
    root: String,
    private val context: Context,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val requestedRoot = root
    private var directory: Path? = null
    private var closed = false
    private var allocated = 0uL
    private var proofItems = 0uL
    private val token = newToken()
    private val files = mutableListOf<Path>()
    private val sources = mutableListOf<FileSnapshotSource>()

    public fun capture(input: String): CoreResult<BinarySource> = fileResult(Stage.Read) {
        if (closed) reject("IO_READ_FAILED", "Input session is closed")
        poll(context)
        if (files.size.toULong() >= context.limits.maxSources.toULong()) {
            reject("RESOURCE_LIMIT_EXCEEDED", "Source count budget exceeded")
        }
        val original = input.toPath()
        val before = regular(fileSystem, original)
        val length = before.size!!
        if (length.toULong() > context.limits.maxSpoolBytes - allocated) {
            reject("RESOURCE_LIMIT_EXCEEDED", "Input spool budget exceeded")
        }
        val blocks = (length / FILE_BLOCK_BYTES + if (length % FILE_BLOCK_BYTES != 0L) 1L else 0L).toULong()
        if (blocks > context.limits.maxItems - proofItems ||
            blocks > context.limits.maxMetadataBytes / 32uL - proofItems) {
            reject("RESOURCE_LIMIT_EXCEEDED", "Input proof budget exceeded")
        }
        val parent = directory ?: freshRoot(fileSystem, requestedRoot).also {
            reserve(fileSystem, it)
            directory = it
        }
        val copy = parent / "source-${files.size + 1}.bin"
        var ownsCopy = false
        try {
            val captured = fileSystem.openReadOnly(original).use { source ->
                val digest = fileSystem.openReadWrite(copy, mustCreate = true).use { output ->
                    ownsCopy = true
                    files += copy
                    val value = digestFile(source, length, context, output)
                    output.flush()
                    value
                }
                if (digestFile(source, length, context) != digest) reject("SOURCE_CHANGED", "Input content changed during capture")
                digest
            }
            val after = regular(fileSystem, original)
            if (after.size != before.size || after.lastModifiedAtMillis != before.lastModifiedAtMillis ||
                after.createdAtMillis != before.createdAtMillis) {
                reject("SOURCE_CHANGED", "Input metadata changed during capture")
            }
            val proof = fileSystem.openReadOnly(copy).use {
                freezeFile(it, length, captured, context)
            }
            allocated += length.toULong()
            proofItems += blocks
            FileSnapshotSource(fileSystem, { copy }, SourceId("$token:source-${sources.size + 1}"),
                GenerationToken("$token:${captured.value}"), proof, context).also { sources += it }
        } catch (failure: Exception) {
            if (ownsCopy) {
                try { fileSystem.delete(copy, mustExist = true); files.remove(copy) } catch (_: Exception) {
                    // Retain the exact owned file for explicit close() retry. Never scan/delete unknown children.
                    allocated += length.toULong()
                }
            }
            throw failure
        }
    }

    public fun close(): CoreResult<Unit> = fileResult(Stage.Read) {
        sources.forEach { it.release() }
        closed = true
        files.toList().forEach { fileSystem.delete(it); files.remove(it) }
        directory?.let { fileSystem.delete(it, mustExist = true); directory = null }
        allocated = 0uL
        proofItems = 0uL
    }
}
