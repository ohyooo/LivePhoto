package livephoto.core.memory

import livephoto.core.*
import livephoto.core.binary.*

/** Immutable in-memory adapter; IDs are provider identities, never inferred from filenames. */
public class MemoryBinarySource(
    private val bytes: Bytes,
    private val id: SourceId,
    private val generation: GenerationToken = GenerationToken("immutable-memory-v1"),
) : BinarySource {
    private var closed: Boolean = false
    private val digest: Digest by lazy { Sha256().also { it.update(bytes) }.finish() }
    override suspend fun identity(): CoreResult<SourceIdentity> = attempt {
        ensureOpen(); SourceIdentity(id, generation, bytes.size.toULong(), digest)
    }
    override suspend fun size(): CoreResult<ULong> = attempt { ensureOpen(); bytes.size.toULong() }
    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = attempt {
        ensureOpen()
        if (offset > bytes.size.toULong()) fail("OFFSET_OUT_OF_BOUNDS", "Read offset exceeds the memory source", Stage.Read)
        val count = minOf(length.toULong(), bytes.size.toULong() - offset).toInt()
        bytes.slice(offset.toInt(), offset.toInt() + count)
    }
    override suspend fun close(): Unit { closed = true }
    private fun ensureOpen() { if (closed) fail("IO_READ_FAILED", "Memory source is closed", Stage.Read) }
}

/**
 * Staging is isolated from committed visibility. Commit publishes one complete immutable map.
 * This is process-memory atomic visibility, with volatile durability, rather than a filesystem
 * rename claim. Every openStaged/returned source is a separate caller-owned read handle.
 * A transaction is confined to one serialized operation: callers must serialize every method
 * (including committedAssets) and must not share it between concurrent mutations. The Core
 * performs its staging IO serially. Atomic asset-set visibility applies under this confinement;
 * this adapter does not promise thread-safe access or durable persistence.
 */
public class MemoryOutputTransaction(private val context: Context, private val token: String) : OutputTransaction {
    private var state: TransactionState = TransactionState.Open
    private var totalBytes: ULong = 0uL
    private val staging: LinkedHashMap<AssetId, MemorySink> = linkedMapOf()
    private var published: Map<AssetId, Bytes> = emptyMap()

    init { require(token.isNotBlank()) }

    override fun capabilities(): OutputCapabilities = OutputCapabilities(true, true, true, false, true)
    override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = attempt {
        checkCancelled(context); requireState(TransactionState.Open)
        if (staging.size.toULong() >= context.limits.maxItems) fail("RESOURCE_LIMIT_EXCEEDED", "Output asset count exceeds the budget", Stage.WriteProtocol)
        val id = AssetId("asset-${staging.size}")
        val sink = MemorySink(this)
        staging[id] = sink
        OutputHandle(id, sink)
    }
    override suspend fun prepare(): CoreResult<Unit> = attempt<Unit> {
        checkCancelled(context); requireState(TransactionState.Open)
        if (staging.isEmpty()) fail("INVALID_ARGUMENT", "Cannot prepare an empty asset set", Stage.Verify)
        state = TransactionState.Prepared
    }
    override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> = attempt {
        checkCancelled(context)
        if (state != TransactionState.Prepared && state != TransactionState.Verified) fail("INVALID_ARGUMENT", "Staged reads require a prepared transaction", Stage.Verify)
        val sink = staging[id] ?: fail("INVALID_ARGUMENT", "Unknown staged asset", Stage.Verify)
        MemoryBinarySource(sink.snapshot(), SourceId("staging:$token:${id.value}"), GenerationToken(token))
    }
    override suspend fun commit(): CoreResult<Receipt> = attempt {
        checkCancelled(context)
        if (state != TransactionState.Prepared && state != TransactionState.Verified) fail("INVALID_ARGUMENT", "Commit requires a prepared transaction", Stage.Publish)
        // Core calls commit only after independent readback verification; freeze before visibility.
        state = TransactionState.Verified
        val assets = staging.mapValues { it.value.snapshot() }
        published = assets
        state = TransactionState.Committed
        receipt()
    }
    override suspend fun abort(): CoreResult<Unit> = attempt<Unit> {
        if (state == TransactionState.Committed) fail("INVALID_ARGUMENT", "Committed assets cannot be aborted", Stage.Publish)
        staging.clear(); totalBytes = 0uL; state = TransactionState.Aborted
    }
    override suspend fun query(): CoreResult<Receipt> = CoreResult.Success(receipt())

    public fun committedAssets(): Map<AssetId, Bytes> = published.toMap()

    private fun receipt(): Receipt = Receipt(token, state, if (state == TransactionState.Committed) published.keys.toList() else staging.keys.toList(), Atomicity.AssetSetRequired, "volatile-process-memory")
    private fun requireState(expected: TransactionState) { if (state != expected) fail("INVALID_ARGUMENT", "Output transaction is not in the required state", Stage.WriteProtocol) }
    private fun resize(previous: Int, next: Int) {
        requireState(TransactionState.Open); checkCancelled(context)
        if (next > previous) {
            val delta = (next - previous).toULong()
            if (delta > context.limits.maxOutputBytes - totalBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Staging asset-set byte budget exceeded", Stage.WriteProtocol)
            totalBytes += delta
        } else totalBytes -= (previous - next).toULong()
    }

    private class MemorySink(private val owner: MemoryOutputTransaction) : BinarySink {
        private var storage = ByteArray(0)
        private var length = 0
        private var position = 0
        private var closed = false

        private fun writable() {
            owner.requireState(TransactionState.Open); checkCancelled(owner.context)
            if (closed) fail("IO_WRITE_FAILED", "Staging sink is closed", Stage.WriteProtocol)
        }
        private fun capacity(required: Int) {
            if (required <= storage.size) return
            val limit = minOf(owner.context.limits.maxOutputBytes, Int.MAX_VALUE.toULong()).toInt()
            if (required > limit) fail("RESOURCE_LIMIT_EXCEEDED", "Memory staging exceeds its buffer limit", Stage.WriteProtocol)
            val doubled = minOf(storage.size.toLong() * 2, limit.toLong()).toInt()
            storage = storage.copyOf(maxOf(required, minOf(maxOf(16, doubled), limit)))
        }
        override suspend fun write(bytes: Bytes): CoreResult<UInt> = attempt {
            writable()
            val end = checkedInt(checkedAdd(position.toULong(), bytes.size.toULong()))
            val nextLength = maxOf(length, end)
            owner.resize(length, nextLength); capacity(nextLength)
            bytes.copyInto(storage, position)
            position = end; length = nextLength
            bytes.size.toUInt()
        }
        override suspend fun seek(offset: ULong): CoreResult<Unit> = attempt<Unit> { writable(); position = checkedInt(offset) }
        override suspend fun truncate(length: ULong): CoreResult<Unit> = attempt<Unit> {
            writable(); val next = checkedInt(length)
            owner.resize(this.length, next); capacity(next)
            if (next < this.length) storage.fill(0, next, this.length)
            else if (next > this.length) storage.fill(0, this.length, next)
            this.length = next; position = minOf(position, next)
        }
        override suspend fun flush(): CoreResult<Unit> = attempt<Unit> { writable() }
        override suspend fun close(): CoreResult<Unit> { closed = true; return CoreResult.Success(Unit) }
        fun snapshot(): Bytes = Bytes(storage.copyOf(length))
    }
}
