package livephoto.adapters.files

import livephoto.core.*
import okio.*
import okio.Path.Companion.toPath
import kotlin.random.Random

internal class FileFault(val code: String, val safeMessage: String) : Exception()
internal fun reject(code: String, message: String): Nothing = throw FileFault(code, message)
internal fun <T> fileResult(stage: Stage, operation: String = "File adapter operation", block: () -> T): CoreResult<T> = try {
    CoreResult.Success(block())
} catch (failure: FileFault) {
    CoreResult.Failure(CoreError(IssueCode(failure.code), stage, failure.safeMessage))
} catch (failure: Exception) {
    // Provider exception messages can contain paths, credentials or metadata.
    CoreResult.Failure(CoreError(IssueCode(if (stage == Stage.Read) "IO_READ_FAILED" else "IO_WRITE_FAILED"),
        stage, "$operation failed", details = mapOf("exceptionType" to Value.Text(failure::class.simpleName ?: "Exception"))))
}
internal fun poll(context: Context) {
    if (context.cancellation?.isCancelled() == true) reject("CANCELLED", "Operation was cancelled")
}
internal fun newToken(): String = List(4) { Random.nextLong().toULong().toString(16).padStart(16, '0') }.joinToString("")
internal fun freshRoot(fileSystem: FileSystem, name: String): Path {
    val path = name.toPath(normalize = true)
    if (path.name in listOf("", ".", "..") || path.parent == null && path.isAbsolute) {
        reject("INVALID_ARGUMENT", "A new named directory is required")
    }
    return fileSystem.canonicalize(path.parent ?: ".".toPath()) / path.name
}
internal fun reserve(fileSystem: FileSystem, root: Path) {
    if (fileSystem.metadataOrNull(root) != null) reject("OUTPUT_EXISTS", "Output directory already exists")
    try {
        fileSystem.createDirectory(root, mustCreate = true)
    } catch (failure: Exception) {
        if (fileSystem.metadataOrNull(root) != null) reject("OUTPUT_EXISTS", "Output directory already exists")
        throw failure
    }
}
internal fun regular(fileSystem: FileSystem, path: Path): FileMetadata {
    if (fileSystem === FileSystem.SYSTEM && systemPathIsLink(path)) {
        reject("INVALID_ARGUMENT", "Symbolic links and reparse points are not accepted")
    }
    val metadata = fileSystem.metadata(path)
    if (!metadata.isRegularFile || metadata.symlinkTarget != null || metadata.size == null || metadata.size!! < 0) {
        reject("INVALID_ARGUMENT", "A regular non-symlink file with a known size is required")
    }
    return metadata
}
internal expect fun systemPathIsLink(path: Path): Boolean

/** Bounded streaming; cancellation is checked between every chunk, including empty inputs. */
internal fun digestFile(handle: FileHandle, expectedSize: Long, context: Context, copy: FileHandle? = null): Digest {
    poll(context)
    if (handle.size() != expectedSize) reject("SOURCE_CHANGED", "Input size changed")
    val bytes = ByteArray(65536)
    val discarded = Buffer()
    var offset = 0L
    val digest = HashingSink.sha256(discarded).use { sink ->
        while (offset < expectedSize) {
            poll(context)
            val count = handle.read(offset, bytes, 0, minOf(65536L, expectedSize - offset).toInt())
            if (count <= 0) reject("SOURCE_CHANGED", "Input ended before its declared size")
            copy?.write(offset, bytes, 0, count)
            sink.write(Buffer().write(bytes, 0, count), count.toLong())
            discarded.clear()
            offset += count
        }
        if (handle.read(offset, bytes, 0, 1) != -1 || handle.size() != expectedSize) {
            reject("SOURCE_CHANGED", "Input size changed")
        }
        Digest(sink.hash.hex())
    }
    poll(context)
    return digest
}
