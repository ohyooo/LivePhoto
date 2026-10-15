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
    create: suspend (Uri, Uri, ProtocolSelector) -> CoreResult<AndroidCreateController.Created>,
) {
    val context = LocalContext.current.applicationContext
    val platform = remember(context, describe, create) {
        object : LivePhotoUiPlatform {
            override val outputDescription = "Pictures/LivePhoto"
            @Composable
            override fun rememberPicker(kind: MediaKind, onResult: (String?) -> Unit): () -> Unit {
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { onResult(it?.toString()) }
                return { launcher.launch(arrayOf(if (kind == MediaKind.Image) "image/*" else "video/*")) }
            }
            override suspend fun describe(reference: String): SelectedMediaInfo =
                describe?.invoke(Uri.parse(reference)) ?: readSelectedMediaInfo(context.contentResolver, Uri.parse(reference))
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
