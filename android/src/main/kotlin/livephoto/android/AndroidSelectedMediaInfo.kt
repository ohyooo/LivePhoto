package livephoto.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Provider metadata is for display, never format detection or authorization of a larger read. */
internal data class SelectedMediaInfo(val displayName: String? = null, val sizeBytes: Long? = null)

internal suspend fun readSelectedMediaInfo(resolver: ContentResolver, uri: Uri): SelectedMediaInfo = withContext(Dispatchers.IO) {
    var name: String? = null
    var size: Long? = null
    currentCoroutineContext().ensureActive()
    try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)?.take(256)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        // Unknown metadata must not become zero, or prevent an otherwise readable provider input.
    }
    currentCoroutineContext().ensureActive()
    SelectedMediaInfo(name, size)
}
