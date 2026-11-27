package livephoto.android

import android.content.Context as AndroidContext
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import livephoto.core.*
import livephoto.core.memory.MemoryOutputTransaction
import java.util.UUID

/** App orchestration only. Protocol policy and serialization remain in the shared Core. */
class AndroidCreateController(private val android: AndroidContext, private val core: LivePhotoCore) {
    data class Created(val uri: Uri, val operation: OperationResult)
    data class Extracted(val uri: Uri, val report: livephoto.ui.ExtractedVideo)
    data class Converted(val uri: Uri, val report: livephoto.ui.ConvertedPhoto)
    data class Repaired(val uri: Uri?, val report: livephoto.ui.PhotoRepairReport)
    data class Keyed(val uri: Uri, val report: livephoto.ui.PhotoKeyReport)

    suspend fun setPhotoKey(input: Uri, requested: Time): CoreResult<Keyed> = withPhotoInput(input) { source, cancellation ->
        when (val result = livephoto.ui.preparePhotoKey(core, source, requested, cancellation)) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> {
                currentCoroutineContext().ensureActive()
                val uri = AndroidPhotoExporter(android.contentResolver).publish(result.value.bytes.toByteArray(), "LivePhoto-key-${UUID.randomUUID()}.jpg")
                CoreResult.Success(Keyed(uri, result.value.report))
            }
        }
    }

    /** Preview/no-op/blocked results never call the exporter. */
    suspend fun repairPhoto(input: Uri, review: livephoto.ui.RepairReview? = null): CoreResult<Repaired> = withPhotoInput(input) { source, cancellation ->
        when (val result = livephoto.ui.preparePhotoRepair(core, source, review, cancellation)) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> {
                val uri = result.value.bytes?.let { repaired ->
                    currentCoroutineContext().ensureActive()
                    AndroidPhotoExporter(android.contentResolver).publish(repaired.toByteArray(), "LivePhoto-repaired-${UUID.randomUUID()}.jpg")
                }
                CoreResult.Success(Repaired(uri, result.value.report))
            }
        }
    }

    suspend fun convertPhoto(input: Uri, target: ProtocolSelector): CoreResult<Converted> = withPhotoInput(input) { source, cancellation ->
        when (val result = livephoto.ui.prepareConvertedPhoto(core, source, target, cancellation)) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> {
                currentCoroutineContext().ensureActive()
                val prepared = result.value
                val uri = AndroidPhotoExporter(android.contentResolver).publish(prepared.bytes.toByteArray(), "LivePhoto-converted-${UUID.randomUUID()}.jpg")
                CoreResult.Success(Converted(uri, prepared.report))
            }
        }
    }

    /** Preparation keeps output in memory. Each later export is an explicit single-file action. */
    suspend fun cleanSplit(input: Uri): CoreResult<livephoto.ui.PreparedCleanSplit<livephoto.core.Bytes>> = withPhotoInput(input) { source, cancellation ->
        livephoto.ui.prepareCleanSplit(core, source, cancellation)
    }

    suspend fun saveSplitFile(file: livephoto.ui.SplitFile<livephoto.core.Bytes>): Uri = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val exporter = AndroidPhotoExporter(android.contentResolver)
        val name = "LivePhoto-clean-${UUID.randomUUID()}.${file.extension}"
        when (file.mime) {
            "image/jpeg" -> { require(file.extension == "jpg"); exporter.publish(file.bytes.toByteArray(), name) }
            "video/mp4", "video/quicktime" -> exporter.publishVideo(file.bytes.toByteArray(), name, file.mime)
            else -> error("Only prepared JPEG/MP4/MOV outputs may be saved")
        }
    }

    suspend fun extractVideo(input: Uri): CoreResult<Extracted> = withPhotoInput(input) { source, cancellation ->
        when (val result = livephoto.ui.prepareExtractedVideo(core, source, cancellation)) {
            is CoreResult.Failure -> result
            is CoreResult.Success -> {
                currentCoroutineContext().ensureActive()
                val video = result.value
                val name = "LivePhoto-video-${UUID.randomUUID()}.${video.report.extension}"
                val uri = AndroidPhotoExporter(android.contentResolver).publishVideo(video.bytes.toByteArray(), name, video.report.mime)
                CoreResult.Success(Extracted(uri, video.report))
            }
        }
    }

    /** Read-only input adapter. No exporter, transaction, decoder or gallery write. */
    suspend fun analyze(input: Uri): CoreResult<AnalysisResult> = withPhotoInput(input) { source, cancellation ->
        livephoto.ui.analyzeSelectedPhoto(core, source, cancellation)
    }

    suspend fun create(image: Uri, video: Uri, target: ProtocolSelector): CoreResult<Created> = withContext(Dispatchers.IO) {
        require(target in setOf(
            ProtocolSelector(ProtocolIds.Oplus, ProfileId("jpeg-no-tail")),
            ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg")),
            ProtocolSelector(ProtocolIds.GoogleV1, ProfileId("jpeg")),
        )) { "This initial Android exporter supports only the three displayed single-JPEG profiles" }
        val job = currentCoroutineContext()[Job]
        val context = Context(Limits(MAX_INPUT_BYTES.toULong(), MAX_OUTPUT_BYTES.toULong()),
            cancellation = Cancellation { job?.isActive == false })
        val token = UUID.randomUUID().toString()
        val imageSource = androidOperation("read-image", "image") { snapshot(image, MAX_INPUT_BYTES) }
        try {
            val videoSource = androidOperation("read-video", "video") {
                snapshot(video, MAX_INPUT_BYTES - imageSource.byteLength)
            }
            try { createFromSnapshots(imageSource, videoSource, target, context, token) }
            finally { videoSource.close() }
        } finally { imageSource.close() }
    }

    private suspend fun createFromSnapshots(
        imageSource: BinarySource, videoSource: BinarySource, target: ProtocolSelector, context: Context, token: String,
    ): CoreResult<Created> {
        val output = MemoryOutputTransaction(context, token)
        var created: OperationResult? = null
        try {
            return when (val result = androidOperation("invoke-core", "request") {
                val policy = livephoto.ui.uiCreationPolicy(target)
                core.create(CreateRequest(imageSource, videoSource, target, policy = policy, output = output, context = context))
            }) {
                is CoreResult.Failure -> result
                is CoreResult.Success -> {
                    created = result.value
                    val asset = androidOperation("verify-output", "output") {
                        result.value.output.assets.single().also {
                            check(it.mime == "image/jpeg") { "This initial Android exporter supports one JPEG asset only" }
                        }
                    }
                    val uri = androidOperation("save-output", "output") {
                        currentCoroutineContext().ensureActive()
                        val bytes = output.committedAssets().getValue(asset.id).toByteArray()
                        AndroidPhotoExporter(android.contentResolver).publish(bytes, "LivePhoto-$token.jpg")
                    }
                    // Return the published URI, not a memory handle closed by this session.
                    // Preserve the original conservative/volatile durability report.
                    val published = result.value.copy(output = MediaOutput(
                        listOf(asset.copy(readableSource = null, accessReference = uri.toString())),
                        result.value.output.receipt,
                    ))
                    CoreResult.Success(Created(uri, published))
                }
            }
        } finally {
            created?.output?.assets?.forEach { it.readableSource?.close() }
        }
    }

    private suspend fun snapshot(uri: Uri, remaining: Int): AndroidInputSnapshot =
        AndroidInputSnapshot.copyFrom(android.cacheDir, remaining, SourceId("android-input:${UUID.randomUUID()}")) {
            checkNotNull(android.contentResolver.openInputStream(uri)) { "Cannot open selected input" }
        }

    private suspend fun <T> withPhotoInput(
        uri: Uri, action: suspend (BinarySource, Cancellation) -> CoreResult<T>,
    ): CoreResult<T> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val source = try { snapshot(uri, livephoto.ui.ANALYSIS_INPUT_LIMIT) }
        catch (_: InputBudgetExceededException) {
            return@withContext CoreResult.Failure(CoreError(IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read,
                "Photo input exceeds 32 MiB"))
        }
        try { action(source, Cancellation { job?.isActive == false }) }
        finally { source.close() }
    }

    companion object {
        const val MAX_INPUT_BYTES: Int = 32 * 1024 * 1024
        const val MAX_OUTPUT_BYTES: Int = 48 * 1024 * 1024
    }
}
