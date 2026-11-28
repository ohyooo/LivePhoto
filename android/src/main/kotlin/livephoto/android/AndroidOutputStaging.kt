package livephoto.android

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import livephoto.core.*
import livephoto.ui.UiOutput
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Operation-scoped single-JPEG staging, separate from the public MediaStore commit. */
internal class AndroidOutputStaging(private val directory: File, private val token: String) : StagedFileFactory, Closeable {
    private var staged: PrivateFile? = null

    override suspend fun create(id: AssetId, spec: OutputAssetSpec): CoreResult<StagedFile> {
        currentCoroutineContext().ensureActive()
        if (staged != null || spec.mime != "image/jpeg")
            return CoreResult.Failure(CoreError(IssueCode("INVALID_ARGUMENT"), Stage.WriteProtocol,
                "Android staging requires a single private JPEG"))
        return storage(Stage.WriteProtocol) {
            val file = AndroidPrivateCache.newOutput(directory)
            try {
                PrivateFile(file, RandomAccessFile(file, "rw"), SourceId("android-output:$token:${id.value}"))
                    .also { staged = it }
            } catch (failure: Exception) {
                file.delete()
                throw failure
            }
        }
    }

    internal class CommittedJpeg(
        private val transaction: StagedOutputTransaction,
        private val staging: AndroidOutputStaging,
    ) : UiOutput<AssetId>, OutputTransaction by transaction {
        override fun asset(id: AssetId): AssetId {
            check(transaction.isCommitted(id)) { "Private output was not committed" }
            return id
        }

        suspend fun openAsset(id: AssetId): CoreResult<BinarySource> {
            currentCoroutineContext().ensureActive()
            val file = staging.staged
            if (!transaction.isCommitted(id) || file == null) return CoreResult.Failure(
                CoreError(IssueCode("IO_READ_FAILED"), Stage.Publish, "Committed private JPEG is unavailable"),
            )
            return file.openSource()
        }
    }

    fun uiOutput(context: Context, operation: String): CommittedJpeg =
        CommittedJpeg(StagedOutputTransaction(context, operation, this), this)

    override fun close() { staged?.close(); staged = null }

    private class PrivateFile(private val file: File, private var writer: RandomAccessFile?, private val id: SourceId) : StagedFile, Closeable {
        private var length: Long? = null
        private val readers = mutableSetOf<RandomAccessFile>()

        override val sink = object : BinarySink {
            private suspend fun <T> writeAccess(action: (RandomAccessFile) -> T): CoreResult<T> {
                currentCoroutineContext().ensureActive()
                return storage(Stage.WriteProtocol) { action(writer ?: throw IOException("Writer closed")) }
            }

            override suspend fun write(bytes: Bytes): CoreResult<UInt> {
                val coroutine = currentCoroutineContext()
                return writeAccess { handle ->
                    var start = 0
                    while (start < bytes.size) {
                        coroutine.ensureActive()
                        val end = start + minOf(AndroidInputSnapshot.BLOCK_BYTES, bytes.size - start)
                        handle.write(bytes.slice(start, end).toByteArray())
                        start = end
                    }
                    bytes.size.toUInt()
                }
            }

            override suspend fun seek(offset: ULong): CoreResult<Unit> = writeAccess {
                if (offset > Long.MAX_VALUE.toULong()) throw IOException("Seek exceeds file offsets")
                it.seek(offset.toLong())
            }

            override suspend fun truncate(length: ULong): CoreResult<Unit> = writeAccess {
                if (length > it.length().toULong()) throw IOException("Truncation would extend the file")
                it.setLength(length.toLong())
            }

            override suspend fun flush(): CoreResult<Unit> = writeAccess { /* RandomAccessFile writes directly to the OS. */ }
            override suspend fun close(): CoreResult<Unit> = storage(Stage.WriteProtocol) { closeWriter() }
        }

        private fun closeWriter() {
            val handle = writer ?: return
            writer = null
            handle.close()
        }

        override suspend fun freeze(): CoreResult<Unit> {
            currentCoroutineContext().ensureActive()
            return storage(Stage.Verify) {
                val size = writer?.length() ?: throw IOException("Missing output writer")
                closeWriter()
                length = size
            }
        }

        override suspend fun openSource(): CoreResult<BinarySource> {
            currentCoroutineContext().ensureActive()
            return storage(Stage.Read) {
                val size = length ?: throw IOException("Output is not frozen")
                val handle = RandomAccessFile(file, "r")
                readers += handle
                val identity = SourceIdentity(id, GenerationToken(id.value), size.toULong())
                object : BinarySource {
                    private suspend fun <T> access(read: () -> T): CoreResult<T> {
                        currentCoroutineContext().ensureActive()
                        return storage(Stage.Read) {
                            if (handle !in readers || handle.length() != size) throw IOException("Staged output unavailable")
                            read()
                        }
                    }
                    override suspend fun identity(): CoreResult<SourceIdentity> = access { identity }
                    override suspend fun size(): CoreResult<ULong> = access { identity.size }
                    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> {
                        if (offset > identity.size) return CoreResult.Failure(CoreError(IssueCode("OFFSET_OUT_OF_BOUNDS"),
                            Stage.Read, "Read exceeds the private output length"))
                        return access {
                            val count = minOf(length.toULong(), identity.size - offset, AndroidInputSnapshot.BLOCK_BYTES.toULong()).toInt()
                            val array = ByteArray(count)
                            handle.seek(offset.toLong())
                            handle.readFully(array)
                            Bytes(array)
                        }
                    }
                    override suspend fun close() { if (readers.remove(handle)) closeQuietly(handle) }
                }
            }
        }

        override fun close() {
            writer?.let(::closeQuietly); writer = null
            readers.forEach(::closeQuietly); readers.clear()
            file.delete()
        }
    }
}

private fun closeQuietly(handle: Closeable) { try { handle.close() } catch (_: IOException) { } }

private inline fun <T> storage(stage: Stage, operation: () -> T): CoreResult<T> = try {
    CoreResult.Success(operation())
} catch (_: IOException) {
    privateStagingError(stage)
} catch (_: SecurityException) {
    privateStagingError(stage)
}

private fun privateStagingError(stage: Stage) = CoreResult.Failure(CoreError(
    IssueCode(if (stage == Stage.Read) "IO_READ_FAILED" else "IO_WRITE_FAILED"), stage,
    "Unable to access private Android output staging",
))
