@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package livephoto.ui.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.ensureActive
import org.khronos.webgl.Int8Array
import org.w3c.files.Blob
import kotlin.coroutines.coroutineContext
import livephoto.core.*

/** Read immutable Blob slices, never materialize the entire input before parsing. */
internal class BrowserBlobSource(blob: Blob, id: SourceId) : BinarySource {
    private var file: Blob? = blob
    private val identity: SourceIdentity

    init {
        val size = browserFileSize(blob)
        require(size.isFinite() && size >= 0 && size <= 9_007_199_254_740_991.0) { "Invalid browser file size" }
        identity = SourceIdentity(id, GenerationToken("immutable-browser-blob-v1"), size.toULong())
    }

    override suspend fun identity(): CoreResult<SourceIdentity> =
        if (file == null) unavailable() else CoreResult.Success(identity)

    override suspend fun size(): CoreResult<ULong> =
        if (file == null) unavailable() else CoreResult.Success(identity.size)

    override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> {
        coroutineContext.ensureActive()
        val blob = file ?: return unavailable()
        if (offset > identity.size) return CoreResult.Failure(CoreError(
            IssueCode("OFFSET_OUT_OF_BOUNDS"), Stage.Read, "Read offset exceeds the browser source", Location(source = identity.id)))
        val count = minOf(length.toULong(), identity.size - offset, 64uL * 1024uL).toInt()
        if (count == 0) return CoreResult.Success(Bytes(byteArrayOf()))
        return try {
            val values = Int8Array(browserFileRangeBytes(blob, offset.toDouble(), offset.toDouble() + count).await())
            if (file == null) unavailable()
            else if (values.length != count) CoreResult.Failure(CoreError(
                IssueCode("IO_READ_FAILED"), Stage.Read, "Browser source returned an incomplete slice", Location(source = identity.id)))
            else CoreResult.Success(Bytes(ByteArray(count) { browserByte(values, it) }))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CoreResult.Failure(CoreError(
            IssueCode("IO_READ_FAILED"), Stage.Read, "Unable to read the selected browser file; select it again", Location(source = identity.id))) }
    }

    override suspend fun close() { file = null }

    private fun unavailable(): CoreResult.Failure = CoreResult.Failure(CoreError(
        IssueCode("IO_READ_FAILED"), Stage.Read, "Browser source is closed", Location(source = identity.id)))
}
