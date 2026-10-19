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
    public val videoExportSuccessMessage: String get() = "已保存原始视频。"
    public val hasResultPreview: Boolean get() = false
    @Composable
    public fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit
    public suspend fun describe(reference: String): SelectedMediaInfo
    public suspend fun create(image: String, video: String, target: ProtocolSelector): CoreResult<CreatedPhoto>
    public suspend fun analyze(reference: String): CoreResult<AnalysisResult> = CoreResult.Failure(
        CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Inspect, "Photo analysis is not available in this adapter"))
    public suspend fun extractVideo(reference: String): CoreResult<ExtractedVideo> = CoreResult.Failure(
        CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Extract, "Video extraction is not available in this adapter"))
    public fun formatFailure(error: Exception, imageName: String?, videoName: String?): String
    @Composable
    public fun rememberVideoEditor(reference: String?): VideoEditor? = null
    @Composable
    public fun ImagePreview(reference: String, modifier: Modifier, onScroll: (Float) -> Unit) {}
    @Composable
    public fun ResultPreview(modifier: Modifier, onScroll: (Float) -> Unit) {}
}
