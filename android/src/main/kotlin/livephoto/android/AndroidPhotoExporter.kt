package livephoto.android

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import livephoto.core.Bytes
import java.io.InputStream
import java.io.OutputStream

/** One app-created photo/video row only; not a multi-asset filesystem transaction. */
class AndroidPhotoExporter(private val resolver: ContentResolver) {
    suspend fun publish(bytes: Bytes, displayName: String): Uri = publishMedia(bytes, displayName,
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "image/jpeg", "Pictures/LivePhoto")

    suspend fun publishVideo(bytes: Bytes, displayName: String, mime: String): Uri {
        require(mime in setOf("video/mp4", "video/quicktime")) { "Only identified MP4/MOV extraction can be saved here" }
        return publishMedia(bytes, displayName, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            mime, "Movies/LivePhoto")
    }

    private suspend fun publishMedia(bytes: Bytes, displayName: String, collection: Uri, mime: String, relativePath: String): Uri {
        require(bytes.size in 1..AndroidCreateController.MAX_OUTPUT_BYTES)
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
                writeMediaChunks(stream, bytes) { currentCoroutineContext().ensureActive() }
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

private const val MEDIA_CHUNK_BYTES = 64 * 1024

/** Keep the committed Core pages immutable and avoid flattening the whole output. */
internal suspend fun writeMediaChunks(stream: OutputStream, bytes: Bytes, checkCancelled: suspend () -> Unit) {
    var offset = 0
    while (offset < bytes.size) {
        checkCancelled()
        val end = offset + minOf(MEDIA_CHUNK_BYTES, bytes.size - offset)
        stream.write(bytes.slice(offset, end).toByteArray())
        offset = end
    }
}

/** Compare exactly the Core bytes with one bounded scratch buffer. Never retain a second output copy. */
internal suspend fun requireMatchingMedia(stream: InputStream, expected: Bytes, checkCancelled: suspend () -> Unit) {
    val buffer = ByteArray(minOf(expected.size, MEDIA_CHUNK_BYTES))
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
