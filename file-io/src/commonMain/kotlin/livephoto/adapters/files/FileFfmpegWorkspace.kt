package livephoto.adapters.files

import livephoto.core.*
import livephoto.core.ffmpeg.*
import okio.*

/** External-tool storage, independent of JVM and process implementation.
 * parent is an existing caller-controlled private directory, never a shared temp fallback.
 * retainedSpool counts input snapshots still owned by the caller. Serialized operations only. */
public object FileFfmpegWorkspace {
    public suspend fun open(parent: String, source: BinarySource, context: Context, outputName: String,
        producesOutput: Boolean, retainedSpool: ULong = 0uL,
        fileSystem: FileSystem = localFileSystem): CoreResult<FfmpegFiles> {
        var files: LocalFfmpegFiles? = null
        return try {
            require(outputName in setOf("output.bin", "frame.jpg"))
            val before = when (val result = source.identity()) {
                is CoreResult.Success -> result.value
                is CoreResult.Failure -> return result
            }
            poll(context)
            if (retainedSpool > context.limits.maxSpoolBytes ||
                before.size > context.limits.maxSpoolBytes - retainedSpool || before.size > Long.MAX_VALUE.toULong())
                reject("RESOURCE_LIMIT_EXCEEDED", "FFmpeg input exceeds remaining spool budget")
            val available = context.limits.maxSpoolBytes - retainedSpool - before.size
            val limit = if (producesOutput) minOf(available, context.limits.maxOutputBytes, Long.MAX_VALUE.toULong()) else 0uL
            if (producesOutput && limit == 0uL) reject("RESOURCE_LIMIT_EXCEEDED", "FFmpeg has no output storage budget")
            if (parent.isBlank()) reject("INVALID_ARGUMENT", "A private FFmpeg workspace is required")
            val root = freshRoot(fileSystem, "$parent/livephoto-ffmpeg-${newToken()}")
            reserve(fileSystem, root)
            val owned = LocalFfmpegFiles(root, outputName, limit, producesOutput, context, fileSystem)
            files = owned
            fileSystem.openReadWrite(root / "input.bin", mustCreate = true).use { handle ->
                owned.ownsInput = true
                var offset = 0uL
                while (offset < before.size) {
                    poll(context)
                    val requested = minOf(65536uL, before.size - offset).toUInt()
                    val bytes = when (val result = source.readAt(offset, requested)) {
                        is CoreResult.Success -> result.value
                        is CoreResult.Failure -> throw FileMediaFailure(result.error)
                    }
                    if (bytes.size == 0 || bytes.size.toUInt() > requested)
                        reject("SOURCE_CHANGED", "FFmpeg input copy made invalid progress")
                    handle.write(offset.toLong(), bytes.toByteArray(), 0, bytes.size)
                    offset += bytes.size.toULong()
                }
                handle.flush()
            }
            val after = when (val result = source.identity()) {
                is CoreResult.Success -> result.value
                is CoreResult.Failure -> return cleanupFailure(owned, result)
            }
            if (before != after) reject("SOURCE_CHANGED", "Source changed during FFmpeg input capture")
            CoreResult.Success(owned)
        } catch (failure: Exception) {
            val error = if (failure is FileMediaFailure) CoreResult.Failure(failure.error)
                else fileResult<FfmpegFiles>(Stage.Read, "FFmpeg workspace acquisition") { throw failure } as CoreResult.Failure
            files?.let { cleanupFailure(it, error) } ?: error
        }
    }
    private suspend fun cleanupFailure(files: FfmpegFiles, failure: CoreResult.Failure): CoreResult.Failure =
        when (val cleanup = files.close()) {
            is CoreResult.Success -> failure
            is CoreResult.Failure -> cleanup
        }
}

private class FileMediaFailure(val error: CoreError) : Exception()

private class LocalFfmpegFiles(private val root: Path, outputName: String,
    override val outputLimit: ULong, private val producesOutput: Boolean,
    private val context: Context, private val fs: FileSystem) : FfmpegFiles {
    private val inputPath = root / "input.bin"
    private val outputPath = root / outputName
    override val input: String get() = inputPath.toString()
    override val output: String get() = outputPath.toString()
    var ownsInput = false
    private var closed = false
    private val readers = mutableListOf<FileSnapshotSource>()
    private fun length(): Long {
        if (closed) reject("IO_READ_FAILED", "FFmpeg workspace is closed")
        poll(context)
        if (!producesOutput || fs.metadataOrNull(outputPath) == null)
            reject("FFMPEG_OUTPUT_MISSING", "FFmpeg produced no output")
        val length = regular(fs, outputPath).size!!
        if (length.toULong() > outputLimit) reject("RESOURCE_LIMIT_EXCEEDED", "FFmpeg output exceeds its spool budget")
        return length
    }
    override suspend fun outputSize(): CoreResult<ULong> = fileResult(Stage.Verify) { length().toULong() }
    override suspend fun openOutput(): CoreResult<BinarySource> = fileResult(Stage.Verify, "FFmpeg output freezing") {
        val length = length()
        val proof = fs.openReadOnly(outputPath).use { handle ->
            freezeFile(handle, length, digestFile(handle, length, context), context)
        }
        val token = newToken()
        FileSnapshotSource(fs, { outputPath }, SourceId("ffmpeg:$token"),
            GenerationToken("$token:${proof.digest.value}"), proof, context).also { readers += it }
    }
    override suspend fun patchOutput(patches: List<FfmpegPatch>): CoreResult<Unit> = fileResult(Stage.Remux, "AAC hint patch") {
        val size = length().toULong()
        if (readers.any { !it.isReleased }) reject("INVALID_ARGUMENT", "Close frozen readers before patching output")
        var end = 0uL
        for (patch in patches.sortedBy { it.range.offset }) {
            if (patch.range.offset < end || patch.range.offset > size || patch.range.length > size - patch.range.offset ||
                patch.range.length != patch.bytes.size.toULong()) reject("OFFSET_OUT_OF_BOUNDS", "AAC hint patch exceeds owned output or overlaps")
            end = patch.range.offset + patch.range.length
        }
        fs.openReadWrite(outputPath, mustExist = true).use { handle ->
            for (patch in patches) { poll(context); handle.write(patch.range.offset.toLong(), patch.bytes.toByteArray(), 0, patch.bytes.size) }
            handle.flush()
            if (handle.size().toULong() != size) reject("SOURCE_CHANGED", "AAC hint patch changed output extent")
        }
    }
    override suspend fun close(): CoreResult<Unit> = fileResult(Stage.Remux, "FFmpeg workspace cleanup") {
        closed = true
        readers.forEach { it.release() }
        // No cancellation poll during cleanup. Unknown children cause directory deletion to fail.
        var failure: Exception? = null
        for (path in listOfNotNull(outputPath, inputPath.takeIf { ownsInput }, root)) {
            try { fs.delete(path) } catch (error: Exception) { failure = error }
        }
        failure?.let { throw it }
    }
}
