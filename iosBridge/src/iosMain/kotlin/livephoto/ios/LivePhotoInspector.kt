@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package livephoto.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import livephoto.core.Bytes
import livephoto.core.Context
import livephoto.core.CoreResult
import livephoto.core.DefaultLivePhotoCore
import livephoto.core.Limits
import livephoto.core.ReadRequest
import livephoto.core.SourceId
import livephoto.core.SourceSet
import livephoto.core.memory.MemoryBinarySource
import platform.Foundation.*

/** Native Swift-friendly result. No source bytes or handle is retained. */
public class LivePhotoInspection internal constructor(
    public val successful: Boolean,
    public val disposition: String? = null,
    public val protocolId: String? = null,
    public val profileId: String? = null,
    public val pairingMatches: Boolean? = null,
    public val issueCodes: List<String> = emptyList(),
    public val errorCode: String? = null,
    public val errorStage: String? = null,
    public val errorMessage: String? = null,
)

/**
 * Read-only structural inspection of bytes already available to the caller.
 * Use on a background thread. Callers must not mutate the input during this call.
 * A successful inspection does not guarantee a Live Photo or Photos compatibility.
 */
public class LivePhotoInspector public constructor() {
    /** Maximum combined size in bytes; inputs are copied into immutable Kotlin storage. */
    public val inputLimitBytes: Int get() = INPUT_LIMIT

    /** Inspect a single carrier. For an Apple image + movie pair, use [inspectPair]. */
    public fun inspect(data: NSData): LivePhotoInspection = inspectBounded(data, null)

    /** Inspect explicit image and video assets; no filename or proximity matching. */
    public fun inspectPair(image: NSData, video: NSData): LivePhotoInspection = inspectBounded(image, video)

    private fun inspectBounded(first: NSData, second: NSData?): LivePhotoInspection {
        val firstSize = first.length
        val secondSize = second?.length ?: 0uL
        val budget = INPUT_LIMIT.toULong()
        if (firstSize > budget || secondSize > budget - firstSize) {
            return failure("RESOURCE_LIMIT_EXCEEDED", "Combined input exceeds 32 MiB")
        }
        if (firstSize == 0uL || (second != null && secondSize == 0uL)) {
            return failure("INVALID_ARGUMENT", "Each input must contain data")
        }

        return try {
            inspectInputs(first, firstSize.toInt(), second, secondSize.toInt(), budget)
        } catch (_: Exception) {
            // Unhandled Kotlin exceptions must not cross the Objective-C API boundary.
            LivePhotoInspection(false, errorCode = "INTERNAL_ERROR", errorStage = "Parse",
                errorMessage = "Unable to inspect input")
        }
    }

    private fun inspectInputs(
        first: NSData,
        firstSize: Int,
        second: NSData?,
        secondSize: Int,
        budget: ULong,
    ): LivePhotoInspection = runBlocking {
        val firstSource = MemoryBinarySource(first.immutableBytes(firstSize), SourceId("ios-input"))
        try {
            val secondSource = second?.let {
                MemoryBinarySource(it.immutableBytes(secondSize), SourceId("ios-video"))
            }
            try {
                val input = if (secondSource == null) SourceSet.Single(firstSource)
                    else SourceSet.Pair(firstSource, secondSource)
                val context = Context(Limits(
                    maxSpoolBytes = budget,
                    maxOutputBytes = 0uL,
                    maxSources = 2u,
                    maxMetadataBytes = budget,
                ))
                when (val result = DefaultLivePhotoCore().inspect(ReadRequest(input, context))) {
                    is CoreResult.Failure -> with(result.error) {
                        LivePhotoInspection(false, errorCode = code.value, errorStage = stage.name,
                            errorMessage = message)
                    }
                    is CoreResult.Success -> with(result.value) {
                        LivePhotoInspection(
                            successful = true,
                            disposition = detection.disposition.name,
                            protocolId = detection.primaryProtocol?.protocol?.value,
                            profileId = detection.primaryProtocol?.profile?.value,
                            pairingMatches = pairing?.matches,
                            issueCodes = (detection.issues + issues).map { it.code.value }.distinct(),
                        )
                    }
                }
            } finally {
                secondSource?.close()
            }
        } finally {
            firstSource.close()
        }
    }
}

private const val INPUT_LIMIT: Int = 32 * 1024 * 1024
private const val COPY_CHUNK: Int = 64 * 1024

private fun failure(code: String, message: String): LivePhotoInspection =
    LivePhotoInspection(false, errorCode = code, errorStage = "Read", errorMessage = message)

private fun NSData.immutableBytes(length: Int): Bytes = Bytes.fromChunks(sequence {
    var offset = 0
    while (offset < length) {
        val count = minOf(COPY_CHUNK, length - offset)
        val chunk = ByteArray(count)
        chunk.usePinned { getBytes(it.addressOf(0), range = NSMakeRange(offset.toULong(), count.toULong())) }
        yield(chunk)
        offset += count
    }
})
