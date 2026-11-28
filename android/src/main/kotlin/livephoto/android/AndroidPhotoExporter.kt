package livephoto.android

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import livephoto.core.BinarySource
import livephoto.core.Bytes
import livephoto.core.CoreResult
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One app-created photo/video row only; not a multi-asset filesystem transaction. */
class AndroidPhotoExporter(private val resolver: ContentResolver) {
    suspend fun publish(bytes: Bytes, displayName: String): Uri = publishMedia(MediaPayload.from(bytes), displayName,
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "image/jpeg", "Pictures/LivePhoto")

    internal suspend fun publish(source: BinarySource, byteLength: ULong, displayName: String): Uri =
        publishMedia(MediaPayload.from(source, byteLength), displayName,
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "image/jpeg", "Pictures/LivePhoto")

    suspend fun publishVideo(bytes: Bytes, displayName: String, mime: String): Uri {
        require(mime in setOf("video/mp4", "video/quicktime")) { "Only identified MP4/MOV extraction can be saved here" }
        return publishMedia(MediaPayload.from(bytes), displayName, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            mime, "Movies/LivePhoto")
    }

    private suspend fun publishMedia(payload: MediaPayload, displayName: String, collection: Uri, mime: String, relativePath: String): Uri {
        require(payload.size in 1..AndroidCreateController.MAX_OUTPUT_BYTES)
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
                writeMediaChunks(stream, payload) { currentCoroutineContext().ensureActive() }
                stream.flush()
            }
            checkNotNull(resolver.openInputStream(uri)) { "Cannot verify pending photo" }.use {
                requireMatchingMedia(it, payload) { currentCoroutineContext().ensureActive() }
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

internal class MediaPayload private constructor(val size: Int, val readAt: suspend (Int, Int) -> ByteArray) {
    companion object {
        fun from(bytes: Bytes) = MediaPayload(bytes.size) { offset, count ->
            bytes.slice(offset, offset + count).toByteArray()
        }

        fun from(source: BinarySource, byteLength: ULong): MediaPayload {
            require(byteLength in 1uL..AndroidCreateController.MAX_OUTPUT_BYTES.toULong())
            return MediaPayload(byteLength.toInt()) { offset, count ->
                when (val result = source.readAt(offset.toULong(), count.toUInt())) {
                    is CoreResult.Success -> {
                        currentCoroutineContext().ensureActive()
                        result.value.toByteArray()
                    }
                    is CoreResult.Failure -> throw IOException("Cannot read verified private output")
                }
            }
        }
    }
}

internal suspend fun writeMediaChunks(stream: OutputStream, bytes: Bytes, checkCancelled: suspend () -> Unit) =
    writeMediaChunks(stream, MediaPayload.from(bytes), checkCancelled)

/** Stream from committed output pages or private disk, without flattening the asset. */
internal suspend fun writeMediaChunks(stream: OutputStream, payload: MediaPayload, checkCancelled: suspend () -> Unit) {
    var offset = 0
    while (offset < payload.size) {
        checkCancelled()
        val count = minOf(MEDIA_CHUNK_BYTES, payload.size - offset)
        val block = payload.readAt(offset, count)
        check(block.size in 1..count) { "Verified Core output ended unexpectedly" }
        stream.write(block)
        offset += block.size
    }
}

internal suspend fun requireMatchingMedia(stream: InputStream, expected: Bytes, checkCancelled: suspend () -> Unit) =
    requireMatchingMedia(stream, MediaPayload.from(expected), checkCancelled)

/** Compare exact bytes against staged output, using bounded scratch buffers. */
internal suspend fun requireMatchingMedia(stream: InputStream, expected: MediaPayload, checkCancelled: suspend () -> Unit) {
    val buffer = ByteArray(minOf(expected.size, MEDIA_CHUNK_BYTES))
    var offset = 0
    while (offset < expected.size) {
        val requested = minOf(buffer.size, expected.size - offset)
        val block = expected.readAt(offset, requested)
        check(block.size in 1..requested) { "Verified Core output ended unexpectedly" }
        var start = 0
        while (start < block.size) {
            checkCancelled()
            val count = stream.read(buffer, 0, block.size - start)
            check(count in 1..(block.size - start) && (0 until count).all { buffer[it] == block[start + it] }) {
                "Pending photo differs from the verified Core output"
            }
            start += count
        }
        offset += block.size
    }
    checkCancelled()
    check(stream.read() == -1) { "Pending photo differs from the verified Core output" }
}
