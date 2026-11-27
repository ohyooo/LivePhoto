package livephoto.android

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import livephoto.core.BinarySource
import livephoto.core.Bytes
import livephoto.core.CoreError
import livephoto.core.CoreResult
import livephoto.core.Digest
import livephoto.core.GenerationToken
import livephoto.core.IssueCode
import livephoto.core.SourceIdentity
import livephoto.core.SourceId
import livephoto.core.Stage

/**
 * The picker may return a provider pipe or a file that changes after selection. Copy at the
 * adapter boundary so the Core has a stable, seekable view without keeping the whole input
 * in the heap. The caller scopes the snapshot to one operation on the IO dispatcher.
 */
internal class AndroidInputSnapshot private constructor(
    private val file: File,
    private val reader: RandomAccessFile,
    private val identity: SourceIdentity,
    val byteLength: Int,
) : BinarySource {
    private val lock = Any()
    private var closed = false

    override suspend fun identity(): CoreResult<SourceIdentity> = access { CoreResult.Success(identity) }

    override suspend fun size(): CoreResult<ULong> = access { CoreResult.Success(identity.size) }

    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = access {
        if (offset > identity.size) return@access readFailure("OFFSET_OUT_OF_BOUNDS", "Read offset exceeds the Android input snapshot")
        val count = minOf(length.toULong(), identity.size - offset, BLOCK_BYTES.toULong()).toInt()
        val buffer = ByteArray(count)
        if (count != 0) {
            reader.seek(offset.toLong())
            reader.readFully(buffer)
        }
        CoreResult.Success(Bytes(buffer))
    }

    override suspend fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try { reader.close() }
            catch (_: IOException) { }
            finally { file.delete() }
        }
    }

    private suspend fun <T> access(block: () -> CoreResult<T>): CoreResult<T> {
        currentCoroutineContext().ensureActive()
        return synchronized(lock) {
            if (closed) readFailure("IO_READ_FAILED", "Android input snapshot is closed")
            else try { block() }
            catch (_: IOException) { readFailure("IO_READ_FAILED", "Cannot read Android input snapshot") }
        }
    }

    companion object {
        internal const val BLOCK_BYTES = 64 * 1024
        private val HEX_DIGITS = "0123456789abcdef"
        private val GENERATION = GenerationToken("android-private-snapshot-v1")

        suspend fun copyFrom(
            directory: File,
            limit: Int,
            id: SourceId,
            openInput: () -> InputStream,
        ): AndroidInputSnapshot {
            require(limit >= 0)
            currentCoroutineContext().ensureActive()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(minOf(BLOCK_BYTES, limit.coerceAtLeast(1)))
            val file = File.createTempFile("livephoto-input-", ".snapshot", directory)
            var total = 0
            var transferred = false
            try {
                openInput().use { input ->
                    file.outputStream().use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            // One look-ahead byte distinguishes exact-limit EOF from overflow.
                            val request = minOf(buffer.size.toLong(), limit.toLong() - total + 1).toInt()
                            val count = input.read(buffer, 0, request)
                            currentCoroutineContext().ensureActive()
                            if (count == -1) break
                            if (count !in 1..request) throw IOException("Selected input stream made no progress")
                            if (count > limit - total) throw InputBudgetExceededException()
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            total += count
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val hash = digest.digest().joinToString("") {
                    val byte = it.toInt() and 0xFF
                    "${HEX_DIGITS[byte ushr 4]}${HEX_DIGITS[byte and 0xF]}"
                }
                val identity = SourceIdentity(id, GENERATION, total.toULong(), Digest(hash))
                val snapshot = AndroidInputSnapshot(file, RandomAccessFile(file, "r"), identity, total)
                transferred = true
                return snapshot
            } finally {
                if (!transferred) file.delete()
            }
        }

        private fun readFailure(code: String, message: String) =
            CoreResult.Failure(CoreError(IssueCode(code), Stage.Read, message))
    }
}
