package livephoto.core

public data class SourceIdentity(
    public val id: SourceId,
    public val generation: GenerationToken,
    public val size: ULong,
    public val digest: Digest? = null,
)

public class Snapshot(identities: List<SourceIdentity>, public val token: GenerationToken) {
    public val identities: List<SourceIdentity> = frozenList(identities)
    init { require(this.identities.map { it.id }.distinct().size == this.identities.size) { "Duplicate source identity" } }
    override fun equals(other: Any?): Boolean = other is Snapshot && identities == other.identities && token == other.token
    override fun hashCode(): Int = 31 * identities.hashCode() + token.hashCode()
    override fun toString(): String = "Snapshot(sources=${identities.size})"
}

public data class Limits(
    public val maxSpoolBytes: ULong,
    public val maxOutputBytes: ULong,
    public val maxSources: UInt = 16u,
    public val maxMetadataBytes: ULong = 64uL * 1024uL * 1024uL,
    public val maxDepth: UInt = 64u,
    public val maxItems: ULong = 1_000_000uL,
) {
    init { require(maxSources > 0u && maxDepth > 0u && maxItems > 0uL) }
}

public data class ProgressEvent(
    public val stage: Stage,
    public val completed: ULong,
    public val total: ULong? = null,
    public val unit: String,
) {
    init { require(total == null || completed <= total) }
}

public fun interface Cancellation { public fun isCancelled(): Boolean }
public fun interface ProgressReceiver { public fun onProgress(event: ProgressEvent): Unit }
public data class Context(
    public val limits: Limits,
    public val cancellation: Cancellation? = null,
    public val progress: ProgressReceiver? = null,
)

/** Borrowed immutable source. Short reads are legal; adapters serialize non-concurrent providers. */
public interface BinarySource {
    public suspend fun identity(): CoreResult<SourceIdentity>
    public suspend fun size(): CoreResult<ULong>
    public suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes>
    public suspend fun close(): Unit
}

/** A staging writer. Short writes are legal and must be handled by the caller. */
public interface BinarySink {
    public suspend fun write(bytes: Bytes): CoreResult<UInt>
    public suspend fun seek(offset: ULong): CoreResult<Unit>
    public suspend fun truncate(length: ULong): CoreResult<Unit>
    public suspend fun flush(): CoreResult<Unit>
    public suspend fun close(): CoreResult<Unit>
}

public data class OutputCapabilities(
    public val seekable: Boolean,
    public val assetSetAtomic: Boolean,
    public val replacesAtomically: Boolean,
    public val durableFlush: Boolean,
    public val canReadStaged: Boolean,
)

public data class OutputAssetSpec(
    public val role: AssetRole,
    public val recommendedName: String? = null,
    public val mime: String,
) { init { require(mime.isNotBlank()) } }

public data class OutputHandle(public val id: AssetId, public val sink: BinarySink)

public class Receipt(
    public val token: String,
    public val state: TransactionState,
    assetIds: List<AssetId>,
    public val atomicity: Atomicity,
    public val durability: String,
) {
    public val assetIds: List<AssetId> = frozenList(assetIds)
    init { require(token.isNotBlank()); require(this.assetIds.distinct().size == this.assetIds.size) }
    override fun toString(): String = "Receipt(state=$state, assets=${assetIds.size}, atomicity=$atomicity)"
}

/**
 * Open -> Prepared -> Verified -> Committed. Failed precommit work is aborted.
 * prepare freezes writers; Core independently verifies openStaged reads before commit.
 * Adapters must enforce visibility/atomicity and recover uncertain commits through query.
 */
public interface OutputTransaction {
    public fun capabilities(): OutputCapabilities
    public suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle>
    public suspend fun prepare(): CoreResult<Unit>
    public suspend fun openStaged(id: AssetId): CoreResult<BinarySource>
    public suspend fun commit(): CoreResult<Receipt>
    public suspend fun abort(): CoreResult<Unit>
    public suspend fun query(): CoreResult<Receipt>
}

public sealed interface SourceSet {
    public data class Single(public val source: BinarySource) : SourceSet
    public data class Pair(public val image: BinarySource, public val video: BinarySource) : SourceSet
    public class Candidates(sources: List<BinarySource>) : SourceSet {
        public val sources: List<BinarySource> = frozenList(sources)
        init { require(this.sources.isNotEmpty()) { "Candidates must contain at least one source" } }
    }
}

public data class ResourceRef(
    public val input: SourceSet,
    public val resourceId: ResourceId? = null,
    public val snapshot: Snapshot? = null,
)
