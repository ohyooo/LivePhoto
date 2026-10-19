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
    data class Extracted(val uri: Uri, val report: livephoto.ui.ExtractedVideo)

    /** Preparation is memory-only. Each later export is an explicit single-file action. */
    suspend fun cleanSplit(input: Uri): CoreResult<livephoto.ui.PreparedCleanSplit> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val bytes = try { readSelected(input, livephoto.ui.ANALYSIS_INPUT_LIMIT) }
        catch (limit: InputBudgetExceededException) {
            return@withContext CoreResult.Failure(CoreError(IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read,
                limit.message ?: "Clean split input exceeds 32 MiB"))
        }
        livephoto.ui.prepareCleanSplit(core, bytes, Cancellation { job?.isActive == false })
    }

    suspend fun saveSplitFile(file: livephoto.ui.SplitFile): Uri = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val exporter = AndroidPhotoExporter(android.contentResolver)
        val name = "LivePhoto-clean-${UUID.randomUUID()}.${file.extension}"
        when (file.mime) {
            "image/jpeg" -> { require(file.extension == "jpg"); exporter.publish(file.bytes.toByteArray(), name) }
            "video/mp4", "video/quicktime" -> exporter.publishVideo(file.bytes.toByteArray(), name, file.mime)
            else -> error("Only prepared JPEG/MP4/MOV outputs may be saved")
        }
    }

    suspend fun extractVideo(input: Uri): CoreResult<Extracted> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val bytes = try { readSelected(input, livephoto.ui.ANALYSIS_INPUT_LIMIT) }
        catch (limit: InputBudgetExceededException) {
            return@withContext CoreResult.Failure(CoreError(IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read,
                limit.message ?: "Video extraction input exceeds 32 MiB"))
        }
        when (val result = livephoto.ui.prepareExtractedVideo(core, bytes, Cancellation { job?.isActive == false })) {
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
    suspend fun analyze(input: Uri): CoreResult<AnalysisResult> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val bytes = try { readSelected(input, livephoto.ui.ANALYSIS_INPUT_LIMIT) }
        catch (limit: InputBudgetExceededException) {
            return@withContext CoreResult.Failure(CoreError(IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read,
                limit.message ?: "Photo analysis input exceeds 32 MiB"))
        }
        livephoto.ui.analyzeSelectedPhoto(core, bytes, Cancellation { job?.isActive == false })
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
        val imageBytes = androidOperation("read-image", "image") { readSelected(image, MAX_INPUT_BYTES) }
        val videoBytes = androidOperation("read-video", "video") { readSelected(video, MAX_INPUT_BYTES - imageBytes.size) }
        val token = UUID.randomUUID().toString()
        val imageSource = MemoryBinarySource(Bytes(imageBytes), SourceId("android-image:$token"))
        val videoSource = MemoryBinarySource(Bytes(videoBytes), SourceId("android-video:$token"))
        val output = MemoryOutputTransaction(context, token)
        var created: OperationResult? = null
        try {
            when (val result = androidOperation("invoke-core", "request") {
                val policy = if (target.protocol == ProtocolIds.Oplus) MutationPolicy(loss = LossPolicy.AllowListed,
                    allowedLosses = listOf(MetadataLoss.OplusCameraUserComment, MetadataLoss.OplusAsciiCameraUserComment)) else MutationPolicy()
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
