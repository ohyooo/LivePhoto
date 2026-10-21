package livephoto.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import livephoto.core.*

public enum class MediaKind { Image, Video, LivePhoto }

/** Opaque references belong to the platform adapter, never the protocol Core. */
public data class SelectedMediaInfo(val displayName: String? = null, val sizeBytes: Long? = null)
public data class CreatedPhoto(val operation: OperationResult)

/** Platform interactions only; presentation, state and layout live in commonMain. */
public interface LivePhotoUiPlatform {
    public val outputDescription: String
    public val successMessage: String get() = "已保存到 $outputDescription。"
    public val supportsVideoEditing: Boolean get() = false
    public val supportsAnalysis: Boolean get() = false
    public val supportsVideoExtraction: Boolean get() = false
    public val supportsCleanSplit: Boolean get() = false
    public val supportsConversion: Boolean get() = false
    public val supportsRepair: Boolean get() = false
    public val supportsPhotoKey: Boolean get() = false
    public val keySuccessMessage: String get() = "已修改关键照片时间并另存。"
    public val repairSuccessMessage: String get() = "已应用修复并另存。"
    public val conversionSuccessMessage: String get() = "已转换并另存。"
    public val videoExportSuccessMessage: String get() = "已保存原始视频。"
    public val hasResultPreview: Boolean get() = false
    public val supportsClipboard: Boolean get() = false
    public suspend fun copyText(text: String): Boolean = false
    public fun coverWarning(image: String?, video: String?): String? = null
    public fun coverPosition(image: String?, video: String?): Time? = null
    @Composable
    public fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit
    public suspend fun describe(reference: String): SelectedMediaInfo
    public fun release(reference: String) {}
    public suspend fun create(image: String, video: String, target: ProtocolSelector): CoreResult<CreatedPhoto>
    public suspend fun analyze(reference: String): CoreResult<AnalysisResult> = CoreResult.Failure(
        CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Inspect, "Photo analysis is not available in this adapter"))
    public suspend fun extractVideo(reference: String): CoreResult<ExtractedVideo> = CoreResult.Failure(
        CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Extract, "Video extraction is not available in this adapter"))
    public suspend fun cleanSplit(reference: String): CoreResult<PreparedCleanSplit> = CoreResult.Failure(
        CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Plan, "Clean split is not available in this adapter"))
    /** One explicit file save only, not atomic publication of the pair. */
    public suspend fun saveSplitFile(file: SplitFile): String = error("Split file export is not available in this adapter")
    public suspend fun convertPhoto(reference: String, target: ProtocolSelector): CoreResult<ConvertedPhoto> = CoreResult.Failure(
        CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Plan, "Conversion is not available in this adapter"))
    public suspend fun repairPhoto(reference: String, review: RepairReview? = null): CoreResult<PhotoRepairReport> = CoreResult.Failure(
        CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Plan, "Repair is not available in this adapter"))
    public suspend fun setPhotoKey(reference: String, requested: Time): CoreResult<PhotoKeyReport> = CoreResult.Failure(
        CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Plan, "Key editing is not available in this adapter"))
    public fun formatFailure(error: Exception, imageName: String?, videoName: String?): String
    @Composable
    public fun rememberVideoEditor(reference: String?): VideoEditor? = null
    @Composable
    public fun ImagePreview(reference: String, modifier: Modifier, onScroll: (Float) -> Unit) {}
    @Composable
    public fun ResultPreview(modifier: Modifier, onScroll: (Float) -> Unit) {}
}
