package livephoto.core

/** An immutable byte buffer. Neither the supplied array nor returned copies alias its storage. */
public class Bytes private constructor(private val storage: List<ByteArray>, private val pageSize: Int, private val start: Int, public val size: Int) {
    public constructor(value: ByteArray) : this(listOf(value.copyOf()), maxOf(1, value.size), 0, value.size)
    public companion object {
        internal const val PAGE_SIZE: Int = 65536
        /** Copies a stream of chunks into immutable pages, without a contiguous file-sized array. */
        public fun fromChunks(chunks: Sequence<ByteArray>): Bytes {
            val pages = mutableListOf<ByteArray>()
            var size = 0
            for (chunk in chunks) {
                require(chunk.size <= Int.MAX_VALUE - size) { "Byte buffer exceeds supported size" }
                var offset = 0
                while (offset < chunk.size) {
                    val within = size % PAGE_SIZE
                    if (within == 0) pages.add(ByteArray(PAGE_SIZE))
                    val count = minOf(PAGE_SIZE - within, chunk.size - offset)
                    chunk.copyInto(pages.last(), within, offset, offset + count)
                    size += count
                    offset += count
                }
            }
            return takePages(pages, size)
        }
        /** Private fixed-size pages become immutable together with the transaction. */
        internal fun takePages(pages: List<ByteArray>, length: Int): Bytes {
            require(length >= 0 && pages.sumOf { it.size.toLong() } >= length)
            require(pages.dropLast(1).all { it.size == PAGE_SIZE } && (pages.lastOrNull()?.size ?: 0) <= PAGE_SIZE)
            return Bytes(pages.toList(), PAGE_SIZE, 0, length)
        }
    }
    public operator fun get(index: Int): Byte {
        require(index in 0 until size) { "Byte index exceeds buffer" }
        val offset = start + index
        return storage[offset / pageSize][offset % pageSize]
    }
    public fun toByteArray(): ByteArray = ByteArray(size).also { copyInto(it, 0) }
    internal fun copyInto(destination: ByteArray, destinationOffset: Int): Unit {
        require(destinationOffset >= 0 && size <= destination.size - destinationOffset)
        var copied = 0
        while (copied < size) {
            val offset = start + copied
            val count = minOf(size - copied, pageSize - offset % pageSize)
            storage[offset / pageSize].copyInto(destination, destinationOffset + copied, offset % pageSize, offset % pageSize + count)
            copied += count
        }
    }
    /** An immutable view over already immutable storage; no repeated short-IO buffer copying. */
    public fun slice(startIndex: Int, endIndex: Int = size): Bytes {
        require(startIndex >= 0 && endIndex >= startIndex && endIndex <= size)
        return Bytes(storage, pageSize, start + startIndex, endIndex - startIndex)
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
