package livephoto.android

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream

/** One app-created photo/video row only; not a multi-asset filesystem transaction. */
class AndroidPhotoExporter(private val resolver: ContentResolver) {
    suspend fun publish(bytes: ByteArray, displayName: String): Uri = publishMedia(bytes, displayName,
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "image/jpeg", "Pictures/LivePhoto")

    suspend fun publishVideo(bytes: ByteArray, displayName: String, mime: String): Uri {
        require(mime in setOf("video/mp4", "video/quicktime")) { "Only identified MP4/MOV extraction can be saved here" }
        return publishMedia(bytes, displayName, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            mime, "Movies/LivePhoto")
    }

    private suspend fun publishMedia(bytes: ByteArray, displayName: String, collection: Uri, mime: String, relativePath: String): Uri {
        require(bytes.isNotEmpty() && bytes.size <= AndroidCreateController.MAX_OUTPUT_BYTES)
        require(displayName.isNotBlank() && '/' !in displayName && '\\' !in displayName && '\u0000' !in displayName)
        currentCoroutineContext().ensureActive()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
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
            checkNotNull(resolver.openInputStream(uri)) { "Cannot verify pending photo" }.use {
                requireMatchingMedia(it, bytes) { currentCoroutineContext().ensureActive() }
            }
            currentCoroutineContext().ensureActive()
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                "Photo publication result is uncertain; check the gallery before retrying"
            }
            published = true
            return uri
        } catch (failure: Exception) {
            if (!published) {
                // A lost commit response must never cause deletion of a published row.
                try {
                    resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && cursor.getInt(0) == 1) resolver.delete(uri, null, null)
                    }
                } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }
}

/** Compare exactly the Core bytes with one bounded scratch buffer. Never retain a second output copy. */
internal suspend fun requireMatchingMedia(stream: InputStream, expected: ByteArray, checkCancelled: suspend () -> Unit) {
    val buffer = ByteArray(minOf(expected.size, 64 * 1024))
    var offset = 0
    while (offset < expected.size) {
        checkCancelled()
        val requested = minOf(buffer.size, expected.size - offset)
        val count = stream.read(buffer, 0, requested)
        check(count in 1..requested && (0 until count).all { buffer[it] == expected[offset + it] }) {
            "Pending photo differs from the verified Core output"
        }
        offset += count
    }
    checkCancelled()
    check(stream.read() == -1) { "Pending photo differs from the verified Core output" }
}
