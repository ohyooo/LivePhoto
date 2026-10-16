@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package livephoto.web

import kotlin.js.Promise
import kotlin.js.JsExport
import kotlin.coroutines.*
import livephoto.core.*
import livephoto.core.memory.*

private const val INPUT_LIMIT: Int = 32 * 1024 * 1024
private const val OUTPUT_LIMIT: Int = 48 * 1024 * 1024

/** JS-safe DTO. Binary data is Int8Array; no Kotlin handles or ULong escape. */
@JsExport
public class WebResult(
    public val success: Boolean,
    public val code: String = "",
    public val stage: String = "",
    public val message: String = "",
    public val protocol: String = "",
    public val verdict: String = "",
    public val coverage: String = "",
    public val mime: String = "",
    public val bytes: ByteArray? = null,
    public val issues: Array<String> = emptyArray(),
)

private fun context(): Context = Context(Limits(INPUT_LIMIT.toULong(), OUTPUT_LIMIT.toULong()))
private fun failure(error: CoreError): WebResult = WebResult(false, error.code.value, error.stage.name, error.message)
private fun invalid(code: String, message: String): WebResult = WebResult(false, code, "Read", message)
private fun promise(block: suspend () -> WebResult): Promise<WebResult> = Promise { resolve, reject ->
    block.startCoroutine(object : Continuation<WebResult> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(result: Result<WebResult>) { result.fold(resolve, reject) }
    })
}

/** Three already-supported profiles only; default does not authorize metadata loss. */
@JsExport
public fun createLivePhoto(image: ByteArray, video: ByteArray, target: String = "oplus.olive", replaceCameraComment: Boolean = false): Promise<WebResult> = promise {
    if (image.size.toLong() + video.size > INPUT_LIMIT) return@promise invalid("RESOURCE_LIMIT_EXCEEDED", "Combined inputs exceed 32 MiB")
    val selector = when (target) {
        "oplus.olive" -> ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail"))
        "google.motionphoto.v2" -> ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg"))
        "google.microvideo.v1" -> ProtocolSelector(ProtocolIds.GoogleV1, ProfileId("jpeg"))
        else -> return@promise invalid("CAPABILITY_UNSUPPORTED", "This Web entry supports only the three documented JPEG profiles")
    }
    val ctx = context()
    val photo = MemoryBinarySource(Bytes(image), SourceId("web-image"))
    val movie = MemoryBinarySource(Bytes(video), SourceId("web-video"))
    val output = MemoryOutputTransaction(ctx, "web-create")
    var created: OperationResult? = null
    try {
        val policy = if (replaceCameraComment && selector.protocol == ProtocolIds.Oplus) MutationPolicy(loss = LossPolicy.AllowListed,
            allowedLosses = listOf(MetadataLoss.OplusCameraUserComment, MetadataLoss.OplusAsciiCameraUserComment)) else MutationPolicy()
        when (val result = DefaultLivePhotoCore().create(CreateRequest(photo, movie, selector, policy = policy, output = output, context = ctx))) {
            is CoreResult.Failure -> failure(result.error)
            is CoreResult.Success -> {
                created = result.value
                val asset = result.value.output.assets.single()
                WebResult(true, protocol = target, verdict = result.value.validation.verdict.name,
                    coverage = result.value.validation.coverage.name, mime = asset.mime,
                    bytes = output.committedAssets().getValue(asset.id).toByteArray(),
                    issues = (result.value.issues + result.value.validation.issues).map { it.code.value }.distinct().toTypedArray())
            }
        }
    } finally {
        created?.output?.assets?.forEach { it.readableSource?.close() }
        photo.close(); movie.close()
    }
}

@JsExport
public fun detectLivePhoto(input: ByteArray): Promise<WebResult> = read(input) { source, ctx ->
    when (val result = DefaultLivePhotoCore().detect(ReadRequest(SourceSet.Single(source), ctx))) {
        is CoreResult.Failure -> failure(result.error)
        is CoreResult.Success -> WebResult(true, protocol = result.value.primaryProtocol?.protocol?.value.orEmpty(),
            verdict = result.value.disposition.name, issues = result.value.issues.map { it.code.value }.toTypedArray())
    }
}

/** Structure and protocol only, not a decoder or gallery/device certification. */
@JsExport
public fun validateLivePhoto(input: ByteArray): Promise<WebResult> = read(input) { source, ctx ->
    when (val result = DefaultLivePhotoCore().validate(ValidationRequest(SourceSet.Single(source),
        layers = listOf(Layer.Structure, Layer.Protocol), context = ctx))) {
        is CoreResult.Failure -> failure(result.error)
        is CoreResult.Success -> WebResult(true, verdict = result.value.verdict.name,
            coverage = result.value.coverage.name, issues = result.value.issues.map { it.code.value }.toTypedArray())
    }
}

@JsExport
public fun extractVideo(input: ByteArray): Promise<WebResult> = read(input) { source, ctx ->
    val output = MemoryOutputTransaction(ctx, "web-extract")
    var extracted: OperationResult? = null
    try {
        when (val result = DefaultLivePhotoCore().extract(ExtractRequest(SourceSet.Single(source), emptyList(), output = output, context = ctx))) {
            is CoreResult.Failure -> failure(result.error)
            is CoreResult.Success -> {
                extracted = result.value
                val asset = result.value.output.assets.singleOrNull { it.role == AssetRole.MotionVideo }
                    ?: return@read invalid("CAPABILITY_UNSUPPORTED", "Expected one embedded motion video")
                WebResult(true, mime = asset.mime, bytes = output.committedAssets().getValue(asset.id).toByteArray())
            }
        }
    } finally { extracted?.output?.assets?.forEach { it.readableSource?.close() } }
}

private fun read(input: ByteArray, block: suspend (BinarySource, Context) -> WebResult): Promise<WebResult> = promise {
    if (input.size > OUTPUT_LIMIT) return@promise invalid("RESOURCE_LIMIT_EXCEEDED", "Input exceeds 48 MiB")
    val source = MemoryBinarySource(Bytes(input), SourceId("web-input"))
    try { block(source, context()) } finally { source.close() }
}
