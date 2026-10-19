package livephoto.android

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** One app-created JPEG row only; not a generic multi-asset filesystem transaction. */
class AndroidPhotoExporter(private val resolver: ContentResolver) {
    suspend fun publish(bytes: ByteArray, displayName: String): Uri {
        require(bytes.isNotEmpty() && bytes.size <= AndroidCreateController.MAX_OUTPUT_BYTES)
        require(displayName.isNotBlank() && '/' !in displayName && '\\' !in displayName && '\u0000' !in displayName)
        currentCoroutineContext().ensureActive()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LivePhoto")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collection, values)) { "Cannot create pending photo" }
        var published = false
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")) { "Cannot open pending output" }.use { stream ->
                var position = 0
                while (position < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(64 * 1024, bytes.size - position)
                    stream.write(bytes, position, count)
                    position += count
                }
                stream.flush()
            }
            val copied = checkNotNull(resolver.openInputStream(uri)) { "Cannot verify pending photo" }.use {
                readBounded(it, bytes.size) { currentCoroutineContext().ensureActive() }
            }
            check(bytes.contentEquals(copied)) { "Pending photo differs from the verified Core output" }
            currentCoroutineContext().ensureActive()
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) {
                "Photo publication result is uncertain; check the gallery before retrying"
            }
            published = true
            return uri
        } catch (failure: Exception) {
            if (!published) {
                // A lost commit response must never cause deletion of a published row.
                try {
                    resolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && cursor.getInt(0) == 1) resolver.delete(uri, null, null)
                    }
                } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }
}

/** Unknown length is bounded while reading, with no whole-input unbounded readBytes(). */
internal class InputBudgetExceededException : IllegalArgumentException("Input byte budget exceeded")

internal suspend fun readBounded(stream: InputStream, limit: Int, checkCancelled: suspend () -> Unit): ByteArray {
    require(limit >= 0)
    val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        checkCancelled()
        val count = stream.read(buffer)
        if (count == -1) break
        check(count > 0) { "Input stream made no progress" }
        if (count > limit - total) throw InputBudgetExceededException()
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}
