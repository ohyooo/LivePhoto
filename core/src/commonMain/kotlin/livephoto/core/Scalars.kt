package livephoto.core

/** An immutable byte buffer. Neither the supplied array nor returned copies alias its storage. */
public class Bytes(value: ByteArray) {
    private val storage: ByteArray = value.copyOf()
    public val size: Int get() = storage.size
    public operator fun get(index: Int): Byte = storage[index]
    public fun toByteArray(): ByteArray = storage.copyOf()
    override fun equals(other: Any?): Boolean = other is Bytes && storage.contentEquals(other.storage)
    override fun hashCode(): Int = storage.contentHashCode()
    override fun toString(): String = "Bytes(size=$size)"
}

/** JSON-compatible values only; floating point, platform objects and handles are excluded. */
public sealed interface Value {
    public data object Null : Value
    public data class Text(public val value: String) : Value
    public data class BooleanValue(public val value: Boolean) : Value
    /** Exact JSON number spelling, including values beyond IEEE-754's exact integer range. */
    public data class Number(public val decimal: String) : Value {
        init { require(JSON_NUMBER.matches(decimal)) { "Invalid JSON number" } }
    }
    public data class ArrayValue(public val values: List<Value>) : Value
    public data class ObjectValue(public val entries: Map<String, Value>) : Value
}

private val JSON_NUMBER: Regex = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

/** A detached, read-only list that cannot expose its backing mutable collection. */
internal fun <T> frozenList(values: List<T>): List<T> = FrozenList(values)

private class FrozenList<T>(values: List<T>) : AbstractList<T>() {
    private val storage: List<T> = values.toList()
    override val size: Int get() = storage.size
    override fun get(index: Int): T = storage[index]
}

public data class SourceId(public val value: String) { init { require(value.isNotBlank()) } }
public data class ResourceId(public val value: String) { init { require(value.isNotBlank()) } }
public data class AssetId(public val value: String) { init { require(value.isNotBlank()) } }
public data class TrackId(public val value: String) { init { require(value.isNotBlank()) } }
public data class ProtocolId(public val value: String) { init { require(value.isNotBlank()) } }
public data class ProfileId(public val value: String) { init { require(value.isNotBlank()) } }
public data class IssueCode(public val value: String) { init { require(value.isNotBlank()) } }
public data class EvidenceId(public val value: String) { init { require(value.isNotBlank()) } }
public data class GenerationToken(public val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = "GenerationToken(redacted)"
}
public data class Digest(public val value: String) { init { require(value.isNotBlank()) } }

public sealed interface CoreResult<out T> {
    public data class Success<T>(public val value: T) : CoreResult<T>
    public data class Failure(public val error: CoreError) : CoreResult<Nothing>
}
