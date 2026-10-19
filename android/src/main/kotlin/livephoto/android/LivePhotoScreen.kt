package livephoto.android

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import livephoto.core.*
import livephoto.ui.CreatedPhoto
import livephoto.ui.LivePhotoUiPlatform
import livephoto.ui.MediaKind

/** Android supplies picker/URI/storage only; all screen content and state are shared. */
@Composable
internal fun LivePhotoScreen(
    describe: (suspend (Uri) -> SelectedMediaInfo)? = null,
    analyze: (suspend (Uri) -> CoreResult<AnalysisResult>)? = null,
    extractVideo: (suspend (Uri) -> CoreResult<AndroidCreateController.Extracted>)? = null,
    cleanSplit: (suspend (Uri) -> CoreResult<livephoto.ui.PreparedCleanSplit>)? = null,
    saveSplitFile: (suspend (livephoto.ui.SplitFile) -> Uri)? = null,
    convertPhoto: (suspend (Uri, ProtocolSelector) -> CoreResult<AndroidCreateController.Converted>)? = null,
    repairPhoto: (suspend (Uri, livephoto.ui.RepairReview?) -> CoreResult<AndroidCreateController.Repaired>)? = null,
    setPhotoKey: (suspend (Uri, Time) -> CoreResult<AndroidCreateController.Keyed>)? = null,
    create: suspend (Uri, Uri, ProtocolSelector) -> CoreResult<AndroidCreateController.Created>,
) {
    val context = LocalContext.current.applicationContext
    val platform = remember(context, describe, analyze, extractVideo, cleanSplit, saveSplitFile, convertPhoto, repairPhoto, setPhotoKey, create) {
        object : LivePhotoUiPlatform {
            override val outputDescription = "Pictures/LivePhoto"
            override val supportsAnalysis = analyze != null
            override val supportsVideoExtraction = extractVideo != null
            override val supportsCleanSplit = cleanSplit != null && saveSplitFile != null
            override val supportsConversion = convertPhoto != null
            override val supportsRepair = repairPhoto != null
            override val supportsPhotoKey = setPhotoKey != null
            override val keySuccessMessage = "已修改关键照片时间并另存到 Pictures/LivePhoto。"
            override val repairSuccessMessage = "已应用修复并另存到 Pictures/LivePhoto。"
            override val conversionSuccessMessage = "已转换并另存到 Pictures/LivePhoto。"
            override val videoExportSuccessMessage = "已提取并保存到 Movies/LivePhoto。"
            @Composable
            override fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit {
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { onResult(it?.toString()) }
                return { launcher.launch(arrayOf(if (kind == MediaKind.Video) "video/*" else "image/*")) }
            }
            override suspend fun describe(reference: String): SelectedMediaInfo =
                describe?.invoke(Uri.parse(reference)) ?: readSelectedMediaInfo(context.contentResolver, Uri.parse(reference))
            override suspend fun analyze(reference: String): CoreResult<AnalysisResult> =
                analyze?.invoke(Uri.parse(reference)) ?: super.analyze(reference)
            override suspend fun extractVideo(reference: String): CoreResult<livephoto.ui.ExtractedVideo> =
                when (val result = extractVideo?.invoke(Uri.parse(reference))) {
                    null -> super.extractVideo(reference)
                    is CoreResult.Failure -> result
                    is CoreResult.Success -> CoreResult.Success(result.value.report)
                }
            override suspend fun cleanSplit(reference: String): CoreResult<livephoto.ui.PreparedCleanSplit> =
                cleanSplit?.invoke(Uri.parse(reference)) ?: super.cleanSplit(reference)
            override suspend fun saveSplitFile(file: livephoto.ui.SplitFile): String {
                checkNotNull(saveSplitFile)(file)
                val folder = if (file.mime == "image/jpeg") "Pictures/LivePhoto" else "Movies/LivePhoto"
                return "${file.mime}：已保存到 $folder；另一文件需单独保存。"
            }
            override suspend fun convertPhoto(reference: String, target: ProtocolSelector): CoreResult<livephoto.ui.ConvertedPhoto> =
                when (val result = convertPhoto?.invoke(Uri.parse(reference), target)) {
                    null -> super.convertPhoto(reference, target)
                    is CoreResult.Failure -> result
                    is CoreResult.Success -> CoreResult.Success(result.value.report)
                }
            override suspend fun setPhotoKey(reference: String, requested: Time): CoreResult<livephoto.ui.PhotoKeyReport> =
                when (val result = setPhotoKey?.invoke(Uri.parse(reference), requested)) {
                    null -> super.setPhotoKey(reference, requested)
                    is CoreResult.Failure -> result
                    is CoreResult.Success -> CoreResult.Success(result.value.report)
                }
            override suspend fun repairPhoto(reference: String, review: livephoto.ui.RepairReview?): CoreResult<livephoto.ui.PhotoRepairReport> =
                when (val result = repairPhoto?.invoke(Uri.parse(reference), review)) {
                    null -> super.repairPhoto(reference, review)
                    is CoreResult.Failure -> result
                    is CoreResult.Success -> CoreResult.Success(result.value.report)
                }
            override suspend fun create(image: String, video: String, target: ProtocolSelector): CoreResult<CreatedPhoto> =
                when (val result = create(Uri.parse(image), Uri.parse(video), target)) {
                    is CoreResult.Failure -> result
                    is CoreResult.Success -> CoreResult.Success(CreatedPhoto(result.value.operation))
                }
            override fun formatFailure(error: Exception, imageName: String?, videoName: String?): String =
                formatAndroidFailure(error, imageName, videoName)
        }
    }
    livephoto.ui.LivePhotoScreen(platform)
}
