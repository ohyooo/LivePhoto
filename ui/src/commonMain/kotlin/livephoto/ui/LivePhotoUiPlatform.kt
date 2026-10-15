package livephoto.ui

import androidx.compose.runtime.Composable
import livephoto.core.*

public enum class MediaKind { Image, Video }

/** Opaque references belong to the platform adapter, never the protocol Core. */
public data class SelectedMediaInfo(val displayName: String? = null, val sizeBytes: Long? = null)
public data class CreatedPhoto(val operation: OperationResult)

/** Platform interactions only; presentation, state and layout live in commonMain. */
public interface LivePhotoUiPlatform {
    public val outputDescription: String
    @Composable
    public fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit
    public suspend fun describe(reference: String): SelectedMediaInfo
    public suspend fun create(image: String, video: String, target: ProtocolSelector): CoreResult<CreatedPhoto>
    public fun formatFailure(error: Exception, imageName: String?, videoName: String?): String
}
