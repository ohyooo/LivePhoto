package livephoto.android

import android.content.Context as AndroidContext
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import livephoto.core.*
import livephoto.core.memory.MemoryBinarySource
import livephoto.core.memory.MemoryOutputTransaction
import java.util.UUID

/** App orchestration only. Protocol policy and serialization remain in the shared Core. */
class AndroidCreateController(private val android: AndroidContext, private val core: LivePhotoCore) {
    data class Created(val uri: Uri, val operation: OperationResult)

    suspend fun create(image: Uri, video: Uri, target: ProtocolSelector): CoreResult<Created> = withContext(Dispatchers.IO) {
        require(target in setOf(
            ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail")),
            ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg")),
            ProtocolSelector(ProtocolIds.GoogleV1, ProfileId("jpeg")),
        )) { "This initial Android exporter supports only the three displayed single-JPEG profiles" }
        val job = currentCoroutineContext()[Job]
        val context = Context(Limits(MAX_INPUT_BYTES.toULong(), MAX_OUTPUT_BYTES.toULong()),
            cancellation = Cancellation { job?.isActive == false })
        val imageBytes = readSelected(image, MAX_INPUT_BYTES)
        val videoBytes = readSelected(video, MAX_INPUT_BYTES - imageBytes.size)
        val token = UUID.randomUUID().toString()
        val imageSource = MemoryBinarySource(Bytes(imageBytes), SourceId("android-image:$token"))
        val videoSource = MemoryBinarySource(Bytes(videoBytes), SourceId("android-video:$token"))
        val output = MemoryOutputTransaction(context, token)
        var created: OperationResult? = null
        try {
            when (val result = core.create(CreateRequest(imageSource, videoSource, target, output = output, context = context))) {
                is CoreResult.Failure -> result
                is CoreResult.Success -> {
                    created = result.value
                    val asset = result.value.output.assets.single()
                    check(asset.mime == "image/jpeg") { "This initial Android exporter supports one JPEG asset only" }
                    currentCoroutineContext().ensureActive()
                    val bytes = output.committedAssets().getValue(asset.id).toByteArray()
                    val uri = AndroidPhotoExporter(android.contentResolver).publish(bytes, "LivePhoto-$token.jpg")
                    CoreResult.Success(Created(uri, result.value))
                }
            }
        } finally {
            created?.output?.assets?.forEach { it.readableSource?.close() }
            imageSource.close()
            videoSource.close()
        }
    }

    private suspend fun readSelected(uri: Uri, remaining: Int): ByteArray {
        require(remaining > 0) { "Input byte budget exceeded" }
        val stream = checkNotNull(android.contentResolver.openInputStream(uri)) { "Cannot open selected input" }
        return stream.use { readBounded(it, remaining) { currentCoroutineContext().ensureActive() } }
    }

    companion object {
        const val MAX_INPUT_BYTES: Int = 32 * 1024 * 1024
        const val MAX_OUTPUT_BYTES: Int = 48 * 1024 * 1024
    }
}
