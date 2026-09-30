package livephoto.core

/** An immutable byte buffer. Neither the supplied array nor returned copies alias its storage. */
public class Bytes private constructor(private val storage: ByteArray, private val start: Int, public val size: Int) {
    public constructor(value: ByteArray) : this(value.copyOf(), 0, value.size)
    public operator fun get(index: Int): Byte {
        require(index in 0 until size) { "Byte index exceeds buffer" }
        return storage[start + index]
    }
    public fun toByteArray(): ByteArray = storage.copyOfRange(start, start + size)
    internal fun copyInto(destination: ByteArray, destinationOffset: Int): Unit {
        require(destinationOffset >= 0 && size <= destination.size - destinationOffset)
        storage.copyInto(destination, destinationOffset, start, start + size)
    }
    /** An immutable view over already immutable storage; no repeated short-IO buffer copying. */
    public fun slice(startIndex: Int, endIndex: Int = size): Bytes {
        require(startIndex >= 0 && endIndex >= startIndex && endIndex <= size)
        return Bytes(storage, start + startIndex, endIndex - startIndex)
    }
    override fun equals(other: Any?): Boolean = other is Bytes && size == other.size && (0 until size).all { this[it] == other[it] }
    override fun hashCode(): Int {
        var hash = 1
        for (index in 0 until size) hash = 31 * hash + this[index]
        return hash
    }
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
