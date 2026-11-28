package livephoto.android

import android.content.Context as AndroidContext
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import livephoto.core.*
import livephoto.ui.motionPhotoJpegFileName
import java.io.IOException
import java.util.UUID

private class SnapshotStorageUnavailableException : IOException("无法在应用缓存中准备输入文件，请检查剩余存储空间后重试。")

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
                val uri = AndroidPhotoExporter(android.contentResolver).publish(result.value.bytes, "LivePhoto-key-${UUID.randomUUID()}.jpg")
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
                    AndroidPhotoExporter(android.contentResolver).publish(repaired, "LivePhoto-repaired-${UUID.randomUUID()}.jpg")
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
                val uri = AndroidPhotoExporter(android.contentResolver).publish(prepared.bytes, motionPhotoJpegFileName("LivePhoto-converted-${UUID.randomUUID()}", prepared.report.target.protocol))
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
            "image/jpeg" -> { require(file.extension == "jpg"); exporter.publish(file.bytes, name) }
            "video/mp4", "video/quicktime" -> exporter.publishVideo(file.bytes, name, file.mime)
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
                val uri = AndroidPhotoExporter(android.contentResolver).publishVideo(video.bytes, name, video.report.mime)
                CoreResult.Success(Extracted(uri, video.report))
            }
        }
    }

    /** Read-only input adapter. No exporter, transaction, decoder or gallery write. */
    suspend fun analyze(input: Uri): CoreResult<AnalysisResult> = withPhotoInput(input, ANDROID_INSPECTION_INPUT_LIMIT) { source, cancellation ->
        inspectAndroidPhoto(core, source, cancellation)
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
        val staging = AndroidOutputStaging(android.cacheDir, token)
        val output = StagedOutputTransaction(context, token, staging)
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
                    val name = motionPhotoJpegFileName("LivePhoto-$token", target.protocol)
                    val uri = androidOperation("save-output", "output") {
                        currentCoroutineContext().ensureActive()
                        check(output.isCommitted(asset.id)) { "Private output was not committed" }
                        AndroidPhotoExporter(android.contentResolver).publish(checkNotNull(asset.readableSource), asset.byteLength, name)
                    }
                    // Return the published URI, not a private file handle closed by this session.
                    // Preserve the original conservative/volatile durability report.
                    val published = result.value.copy(output = MediaOutput(
                        listOf(asset.copy(recommendedName = name, readableSource = null, accessReference = uri.toString())),
                        result.value.output.receipt,
                    ))
                    CoreResult.Success(Created(uri, published))
                }
            }
        } finally {
            try { created?.output?.assets?.forEach { it.readableSource?.close() } }
            finally { staging.close() }
        }
    }

    private suspend fun snapshot(uri: Uri, remaining: Int): AndroidInputSnapshot = try {
        AndroidInputSnapshot.copyFrom(android.cacheDir, remaining, SourceId("android-input:${UUID.randomUUID()}")) {
            android.contentResolver.openInputStream(uri) ?: throw SelectedDocumentUnavailableException()
        }
    } catch (failure: SelectedDocumentUnavailableException) { throw failure }
    catch (_: IOException) { throw SnapshotStorageUnavailableException() }

    private suspend fun <T> withPhotoInput(
        uri: Uri, limit: Int = MAX_INPUT_BYTES, action: suspend (BinarySource, Cancellation) -> CoreResult<T>,
    ): CoreResult<T> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val source = try { snapshot(uri, limit) }
        catch (_: InputBudgetExceededException) {
            return@withContext CoreResult.Failure(CoreError(IssueCode("RESOURCE_LIMIT_EXCEEDED"), Stage.Read,
                "Photo input exceeds ${limit / (1024 * 1024)} MiB"))
        }
        catch (unavailable: SelectedDocumentUnavailableException) {
            return@withContext CoreResult.Failure(CoreError(IssueCode("INPUT_UNAVAILABLE"), Stage.Read, unavailable.message.orEmpty()))
        }
        catch (unavailable: SnapshotStorageUnavailableException) {
            return@withContext CoreResult.Failure(CoreError(IssueCode("IO_READ_FAILED"), Stage.Read, unavailable.message.orEmpty()))
        }
        try { action(source, Cancellation { job?.isActive == false }) }
        finally { source.close() }
    }

    companion object {
        const val MAX_INPUT_BYTES: Int = 32 * 1024 * 1024
        const val MAX_OUTPUT_BYTES: Int = 48 * 1024 * 1024
    }
}
