@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package livephoto.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import livephoto.core.BinarySource
import livephoto.core.Bytes
import livephoto.core.CoreError
import livephoto.core.CoreResult
import livephoto.core.GenerationToken
import livephoto.core.IssueCode
import livephoto.core.SourceId
import livephoto.core.SourceIdentity
import livephoto.core.Stage
import platform.Foundation.NSURL
import platform.posix.EINTR
import platform.posix.ELOOP
import platform.posix.O_CLOEXEC
import platform.posix.O_NOFOLLOW
import platform.posix.O_NONBLOCK
import platform.posix.O_RDONLY
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close as closeDescriptor
import platform.posix.errno
import platform.posix.fstat
import platform.posix.lstat
import platform.posix.open
import platform.posix.pread
import platform.posix.stat

/** One invocation owns this descriptor and never serializes a provider path or bookmark. */
internal class IosReadOnlyFileSource private constructor(
    private val file: NSURL,
    private val holdsScope: Boolean,
    private var descriptor: Int,
    private val original: FileVersion,
    id: SourceId,
) : BinarySource {
    val sourceSize: ULong = original.size.toULong()
    private val stableIdentity = SourceIdentity(id, GenerationToken("ios-read-only-file"), sourceSize)

    override suspend fun identity(): CoreResult<SourceIdentity> = fileResult {
        validateUnchanged()
        stableIdentity
    }

    override suspend fun size(): CoreResult<ULong> = fileResult {
        validateUnchanged()
        sourceSize
    }

    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = fileResult {
        validateUnchanged()
        if (offset > sourceSize) fileFailure("OFFSET_OUT_OF_BOUNDS", "Read offset exceeds the file size")
        val count = minOf(length.toULong(), sourceSize - offset, COPY_CHUNK_BYTES.toULong()).toInt()
        if (count == 0) return@fileResult Bytes(ByteArray(0))
        val buffer = ByteArray(count)
        val read = buffer.usePinned { pinned ->
            var attempts = 0
            var received: Long
            do {
                received = pread(descriptor, pinned.addressOf(0), count.toULong(), offset.toLong())
                attempts++
            } while (received < 0 && errno == EINTR && attempts < 4)
            received
        }
        validateUnchanged()
        if (read < 0) fileFailure("IO_READ_FAILED", "Unable to read the input file")
        if (read == 0L) fileFailure("UNEXPECTED_EOF", "The input file ended during a read")
        Bytes(if (read == count.toLong()) buffer else buffer.copyOf(read.toInt()))
    }

    fun validateUnchanged() {
        if (descriptor < 0) fileFailure("IO_READ_FAILED", "The input file is closed")
        if (versionOf(descriptor) != original) fileFailure("SOURCE_CHANGED", "The input file changed during inspection")
    }

    override suspend fun close() {
        if (descriptor < 0) return
        val heldDescriptor = descriptor
        descriptor = -1
        try {
            closeDescriptor(heldDescriptor)
        } finally {
            if (holdsScope) file.stopAccessingSecurityScopedResource()
        }
    }

    companion object {
        private const val COPY_CHUNK_BYTES = 64 * 1024

        fun openFile(file: NSURL, id: SourceId): IosReadOnlyFileSource {
            val path = file.path
            if (!file.isFileURL() || !isLocalFileHost(file.host) ||
                path == null || !path.startsWith('/') || '\u0000' in path
            ) fileFailure("INVALID_ARGUMENT", "An absolute local file URL is required")

            val holdsScope = file.startAccessingSecurityScopedResource()
            var descriptor = -1
            var transferred = false
            try {
                // Refuse special files before opening, and re-check the actual descriptor after
                // opening. NOFOLLOW/NONBLOCK protect against a last-component race.
                val selected = versionAtPath(path)
                if (!selected.isRegularFile) fileFailure("INVALID_ARGUMENT", "Input must be a regular file")
                descriptor = open(path, O_RDONLY or O_CLOEXEC or O_NOFOLLOW or O_NONBLOCK)
                if (descriptor < 0) {
                    if (errno == ELOOP) fileFailure("INVALID_ARGUMENT", "Symbolic links are not accepted as input files")
                    fileFailure("IO_READ_FAILED", "Unable to open the input file")
                }
                val initial = versionOf(descriptor)
                if (initial != selected) fileFailure("SOURCE_CHANGED", "The input file changed before inspection")
                return IosReadOnlyFileSource(file, holdsScope, descriptor, initial, id).also { transferred = true }
            } finally {
                if (!transferred) {
                    try {
                        if (descriptor >= 0) closeDescriptor(descriptor)
                    } finally {
                        if (holdsScope) file.stopAccessingSecurityScopedResource()
                    }
                }
            }
        }

        private fun isLocalFileHost(host: String?): Boolean =
            host.isNullOrEmpty() || host.equals("localhost", ignoreCase = true)
    }
}

private data class FileVersion(
    val kind: Int,
    val device: Int,
    val inode: ULong,
    val size: Long,
    val modifiedSeconds: Long,
    val modifiedNanos: Long,
    val changedSeconds: Long,
    val changedNanos: Long,
) {
    val isRegularFile: Boolean get() = kind == S_IFREG && size >= 0
}

private fun versionAtPath(path: String): FileVersion = memScoped {
    val entry = alloc<stat>()
    if (lstat(path, entry.ptr) != 0) fileFailure("IO_READ_FAILED", "Unable to open the input file")
    entry.version()
}

private fun versionOf(descriptor: Int): FileVersion = memScoped {
    val entry = alloc<stat>()
    if (fstat(descriptor, entry.ptr) != 0) fileFailure("IO_READ_FAILED", "Unable to identify the input file")
    entry.version()
}

private fun stat.version(): FileVersion = FileVersion(
    kind = st_mode.toInt() and S_IFMT,
    device = st_dev,
    inode = st_ino,
    size = st_size,
    modifiedSeconds = st_mtimespec.tv_sec,
    modifiedNanos = st_mtimespec.tv_nsec,
    changedSeconds = st_ctimespec.tv_sec,
    changedNanos = st_ctimespec.tv_nsec,
)

internal class IosFileInspectionFailure(code: String, message: String) : Exception(message) {
    val error = CoreError(IssueCode(code), Stage.Read, message)
}

private fun fileFailure(code: String, message: String): Nothing = throw IosFileInspectionFailure(code, message)

private inline fun <T> fileResult(action: () -> T): CoreResult<T> = try {
    CoreResult.Success(action())
} catch (failure: IosFileInspectionFailure) {
    CoreResult.Failure(failure.error)
}
