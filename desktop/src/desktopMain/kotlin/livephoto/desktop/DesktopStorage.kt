package livephoto.desktop

import kotlinx.coroutines.suspendCancellableCoroutine
import livephoto.core.Bytes
import livephoto.core.Digest
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun chooseFile(owner: Frame?, saveName: String? = null): Path? = suspendCancellableCoroutine { continuation ->
    var dialog: FileDialog? = null
    continuation.invokeOnCancellation { EventQueue.invokeLater { dialog?.dispose() } }
    EventQueue.invokeLater {
        if (continuation.isActive) {
            try {
                val picker = FileDialog(owner, if (saveName == null) "选择媒体文件" else "另存为", if (saveName == null) FileDialog.LOAD else FileDialog.SAVE)
                dialog = picker
                if (saveName != null) picker.file = saveName
                picker.isVisible = true
                if (continuation.isActive) continuation.resume(picker.file?.let { Path.of(picker.directory, it) })
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            finally { dialog?.dispose() }
        }
    }
}

internal fun bytesDigest(bytes: Bytes): Digest {
    val hash = MessageDigest.getInstance("SHA-256")
    for (offset in 0 until bytes.size step 65536) hash.update(bytes.slice(offset, minOf(bytes.size, offset + 65536)).toByteArray())
    return Digest(hash.digest().joinToString("") { "%02x".format(it) })
}

/** The verified sibling is atomically linked into a new name. Existing files cannot be replaced. */
internal fun publishFile(target: Path, bytes: Bytes, expected: Digest, cancelled: () -> Boolean = { false }) {
    fun checkCancelled() { if (cancelled()) throw kotlinx.coroutines.CancellationException("Save cancelled") }
    checkCancelled()
    val destination = target.toAbsolutePath()
    val temporary = Files.createTempFile(destination.parent, ".livephoto-", ".part")
    try {
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
            for (offset in 0 until bytes.size step 65536) {
                checkCancelled()
                val buffer = ByteBuffer.wrap(bytes.slice(offset, minOf(bytes.size, offset + 65536)).toByteArray())
                while (buffer.hasRemaining()) check(channel.write(buffer) > 0) { "Output made no progress" }
            }
            channel.force(true)
        }
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(temporary).use { input ->
            val buffer = ByteArray(65536)
            while (true) { checkCancelled(); val count = input.read(buffer); if (count < 0) break; if (count == 0) error("Readback made no progress"); hash.update(buffer, 0, count) }
        }
        val actual = hash.digest().joinToString("") { "%02x".format(it) }
        check(Files.size(temporary) == bytes.size.toLong() && actual == expected.value) { "Output verification failed" }
        checkCancelled(); Files.createLink(destination, temporary)
    } finally { Files.deleteIfExists(temporary) }
}
