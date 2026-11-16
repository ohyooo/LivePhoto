package livephoto.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import livephoto.core.Bytes
import livephoto.ui.SplitFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCancellationTest {
    @Test fun cancellingSaveClosesItsWaitAndAllowsTheNextSave() = runBlocking {
        val directory = Files.createTempDirectory("LivePhoto-cancel-save-")
        val target = directory.resolve("saved.jpg")
        val opened = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var first = true
        val platform = DesktopPlatform { _, _ ->
            if (first) {
                first = false
                opened.complete(Unit)
                try { awaitCancellation() } finally { released.complete(Unit) }
            } else target
        }
        val bytes = Bytes(byteArrayOf(1, 2, 3, 4))
        val output = SplitFile(bytes, "image/jpeg", "jpg", bytesDigest(bytes), bytes.size.toULong())
        try {
            withTimeout(10_000) {
                val waiting = async { platform.saveSplitFile(output) }
                opened.await()
                platform.cancelTask()
                waiting.join()
                released.await()
                assertTrue(waiting.isCancelled)
                assertFalse(Files.exists(target))
                platform.saveSplitFile(output)
                assertContentEquals(bytes.toByteArray(), Files.readAllBytes(target))
            }
        } finally {
            platform.close()
            Files.deleteIfExists(target)
            Files.delete(directory)
        }
    }
}
