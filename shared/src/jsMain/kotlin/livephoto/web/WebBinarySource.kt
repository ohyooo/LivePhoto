package livephoto.web

import kotlin.js.Promise
import kotlin.js.jsTypeOf
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import livephoto.core.*
import livephoto.core.memory.MemoryBinarySource

internal suspend fun <T> awaitWeb(value: Promise<T>): T = suspendCoroutine { continuation ->
    value.then<Unit>({ result -> continuation.resume(result) }, { error -> continuation.resumeWithException(error) })
}

private fun byteArray(input: dynamic): Boolean = js("input instanceof Int8Array") as Boolean

internal fun webInputSize(input: dynamic): Int {
    val size: dynamic = if (byteArray(input)) input.length else input.size
    require(jsTypeOf(size) == "number") { "Expected a bounded browser source" }
    val count = size as Double
    require(count.isFinite() && count >= 0 && count <= Int.MAX_VALUE && count % 1 == 0.0) { "Invalid browser source size" }
    return count.toInt()
}

/** The public Blob bridge supplies immutable size and a captured slice callback. */
internal fun webSource(input: dynamic, id: SourceId): BinarySource =
    if (byteArray(input)) MemoryBinarySource(Bytes(input as ByteArray), id) else WebBinarySource(input, id)

private class WebBinarySource(input: dynamic, id: SourceId) : BinarySource {
    private val count = webInputSize(input)
    private val read: dynamic = input.readAt
    private val identity = SourceIdentity(id, GenerationToken("immutable-browser-blob-v1"), count.toULong())
    init { require(jsTypeOf(read) == "function") { "Missing browser source reader" } }
    override suspend fun identity(): CoreResult<SourceIdentity> = CoreResult.Success(identity)
    override suspend fun size(): CoreResult<ULong> = CoreResult.Success(identity.size)
    override suspend fun close() {}
    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> {
        if (offset > identity.size) return CoreResult.Failure(CoreError(IssueCode("OFFSET_OUT_OF_BOUNDS"), Stage.Read,
            "Read exceeds browser source", Location(source = identity.id)))
        val requested = minOf(length.toULong(), identity.size - offset, 65536uL).toInt()
        if (requested == 0) return CoreResult.Success(Bytes(byteArrayOf()))
        return try {
            val bytes = awaitWeb(read(offset.toDouble(), requested) as Promise<ByteArray>)
            if (bytes.size != requested) CoreResult.Failure(CoreError(IssueCode("IO_READ_FAILED"), Stage.Read,
                "Incomplete browser slice", Location(source = identity.id)))
            else CoreResult.Success(Bytes(bytes))
        } catch (_: Throwable) {
            CoreResult.Failure(CoreError(IssueCode("IO_READ_FAILED"), Stage.Read,
                "Unable to read the selected browser input", Location(source = identity.id)))
        }
    }
}
