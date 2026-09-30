package livephoto.core.binary

import livephoto.core.*

internal class CoreFault(val error: CoreError) : Exception(error.message)

internal fun fail(code: String, message: String, stage: Stage = Stage.Parse, location: Location? = null): Nothing =
    throw CoreFault(CoreError(IssueCode(code), stage, message, location))

internal fun <T> CoreResult<T>.orThrow(): T = when (this) {
    is CoreResult.Success -> value
    is CoreResult.Failure -> throw CoreFault(error)
}

internal suspend fun <T> attempt(block: suspend () -> T): CoreResult<T> = try {
    CoreResult.Success(block())
} catch (fault: CoreFault) {
    CoreResult.Failure(fault.error)
}

internal fun <T> attemptNow(block: () -> T): CoreResult<T> = try {
    CoreResult.Success(block())
} catch (fault: CoreFault) {
    CoreResult.Failure(fault.error)
}

internal fun checkCancelled(context: Context): Unit {
    if (context.cancellation?.isCancelled() == true) fail("CANCELLED", "Operation was cancelled", Stage.Read)
}

internal fun checkedAdd(left: ULong, right: ULong): ULong {
    if (right > ULong.MAX_VALUE - left) fail("INTEGER_OVERFLOW", "Unsigned addition overflows")
    return left + right
}

internal fun checkedMultiply(left: ULong, right: ULong): ULong {
    if (left != 0uL && right > ULong.MAX_VALUE / left) fail("INTEGER_OVERFLOW", "Unsigned multiplication overflows")
    return left * right
}

internal fun checkedInt(value: ULong): Int {
    if (value > Int.MAX_VALUE.toULong()) fail("RESOURCE_LIMIT_EXCEEDED", "Byte allocation exceeds the platform buffer limit")
    return value.toInt()
}

internal fun checkedRange(offset: ULong, length: ULong, size: ULong): ByteRange {
    if (length > ULong.MAX_VALUE - offset) fail("INTEGER_OVERFLOW", "Byte range exceeds UInt64")
    if (offset > size || length > size - offset) fail("OFFSET_OUT_OF_BOUNDS", "Byte range exceeds its parent boundary")
    return ByteRange(offset, length)
}

/** Retained metadata accounting, separate from bounded streaming buffers. */
internal class ParseBudget(private val context: Context) {
    private var bytes: ULong = 0uL
    private var items: ULong = 0uL
    fun poll(): Unit = checkCancelled(context)

    fun retain(length: ULong): Unit {
        checkCancelled(context)
        if (length > context.limits.maxMetadataBytes - bytes) fail("RESOURCE_LIMIT_EXCEEDED", "Metadata byte budget exceeded")
        bytes += length
    }

    fun item(depth: UInt = 0u): Unit {
        checkCancelled(context)
        if (depth > minOf(context.limits.maxDepth, 256u) || items >= context.limits.maxItems) fail("RESOURCE_LIMIT_EXCEEDED", "Parser depth or item budget exceeded")
        items++
    }
}
