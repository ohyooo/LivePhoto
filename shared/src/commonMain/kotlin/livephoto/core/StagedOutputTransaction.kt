package livephoto.core

import livephoto.core.binary.*

/** Platform-owned temporary file. freeze must close writers before exposing immutable readers. */
public interface StagedFile {
    public val sink: BinarySink
    public suspend fun freeze(): CoreResult<Unit>
    public suspend fun openSource(): CoreResult<BinarySource>
}
public fun interface StagedFileFactory {
    public suspend fun create(id: AssetId, spec: OutputAssetSpec): CoreResult<StagedFile>
}

/** Serialized private staging. Only commit makes the complete asset set visible to the caller.
 * The platform owns temporary-file cleanup, including after a terminated Worker. */
public class StagedOutputTransaction(private val context: Context, private val token: String,
    private val factory: StagedFileFactory) : OutputTransaction {
    private var state = TransactionState.Open
    private var frozen = false
    private var total = 0uL
    private val files = linkedMapOf<AssetId, Entry>()
    override fun capabilities(): OutputCapabilities = OutputCapabilities(true, true, false, false, true)
    override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = attempt {
        writable()
        if (files.size.toULong() >= context.limits.maxItems) fail("RESOURCE_LIMIT_EXCEEDED", "Output asset count exceeds budget", Stage.WriteProtocol)
        val id = AssetId("asset-${files.size}")
        val entry = Entry(factory.create(id, spec).orThrow())
        files[id] = entry
        OutputHandle(id, entry)
    }
    override suspend fun prepare(): CoreResult<Unit> = attempt {
        writable()
        if (files.isEmpty()) fail("INVALID_ARGUMENT", "Cannot prepare an empty asset set", Stage.Verify)
        // Freeze before verification. Failed preparation leaves all writers unavailable.
        state = TransactionState.Prepared
        for (entry in files.values) entry.file.freeze().orThrow()
        frozen = true
    }
    override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> = attempt {
        checkCancelled(context)
        if (!frozen || state != TransactionState.Prepared && state != TransactionState.Verified)
            fail("INVALID_ARGUMENT", "Staged reads require a prepared transaction", Stage.Verify)
        (files[id] ?: fail("INVALID_ARGUMENT", "Unknown staged asset", Stage.Verify)).file.openSource().orThrow()
    }
    override suspend fun commit(): CoreResult<Receipt> = attempt {
        checkCancelled(context)
        if (!frozen || state != TransactionState.Prepared && state != TransactionState.Verified)
            fail("INVALID_ARGUMENT", "Commit requires a prepared transaction", Stage.Publish)
        state = TransactionState.Verified
        state = TransactionState.Committed
        receipt()
    }
    override suspend fun abort(): CoreResult<Unit> = attempt {
        if (state == TransactionState.Committed) fail("INVALID_ARGUMENT", "Committed output cannot be aborted", Stage.Publish)
        state = TransactionState.Aborted
        for (entry in files.values) entry.file.sink.close().orThrow()
    }
    override suspend fun query(): CoreResult<Receipt> = CoreResult.Success(receipt())
    public fun isCommitted(id: AssetId): Boolean = state == TransactionState.Committed && id in files
    private fun receipt() = Receipt(token, state, files.keys.toList(), Atomicity.AssetSetRequired, "private-staging-no-crash-durability")
    private fun writable() {
        checkCancelled(context)
        if (state != TransactionState.Open) fail("INVALID_ARGUMENT", "Output transaction is frozen", Stage.WriteProtocol)
    }
    private inner class Entry(val file: StagedFile) : BinarySink {
        var position = 0uL
        var length = 0uL
        var closed = false
        private fun checkWrite() { writable(); if (closed) fail("IO_WRITE_FAILED", "Output writer is closed", Stage.WriteProtocol) }
        private fun reserve(next: ULong) {
            if (next > length && next - length > context.limits.maxOutputBytes - total)
                fail("RESOURCE_LIMIT_EXCEEDED", "Staging asset-set byte budget exceeded", Stage.WriteProtocol)
        }
        private fun resize(next: ULong) { if (next >= length) total += next - length else total -= length - next; length = next }
        override suspend fun write(bytes: Bytes): CoreResult<UInt> = attempt {
            checkWrite()
            if (bytes.size == 0) return@attempt 0u
            if (bytes.size.toULong() > ULong.MAX_VALUE - position) fail("OFFSET_OUT_OF_BOUNDS", "Output position overflow", Stage.WriteProtocol)
            reserve(maxOf(length, position + bytes.size.toULong()))
            val count = file.sink.write(bytes).orThrow()
            if (count.toULong() > bytes.size.toULong()) fail("IO_WRITE_FAILED", "Output writer returned an invalid count", Stage.WriteProtocol)
            if (count > 0u) { position += count.toULong(); resize(maxOf(length, position)) }; count
        }
        override suspend fun seek(offset: ULong): CoreResult<Unit> = attempt {
            checkWrite()
            if (offset > context.limits.maxOutputBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Output seek exceeds budget", Stage.WriteProtocol)
            file.sink.seek(offset).orThrow(); position = offset
        }
        override suspend fun truncate(length: ULong): CoreResult<Unit> = attempt {
            checkWrite(); reserve(length); file.sink.truncate(length).orThrow(); resize(length); position = minOf(position, length)
        }
        override suspend fun flush(): CoreResult<Unit> = attempt { checkWrite(); file.sink.flush().orThrow() }
        override suspend fun close(): CoreResult<Unit> {
            if (closed) return CoreResult.Success(Unit)
            closed = true
            // The factory freezes/closes the underlying handle during prepare.
            return CoreResult.Success(Unit)
        }
    }
}
