package livephoto.android

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidPrivateCacheTest {
    @Test fun coldStartReclaimsOnlyTopLevelOwnedRegularTempFiles() {
        val directory = createTempDirectory("android-cache-restart").toFile()
        try {
            val snapshot = AndroidPrivateCache.newInput(directory).apply { writeText("snapshot") }
            val stage = AndroidPrivateCache.newOutput(directory).apply { writeText("stage") }
            val legacy = directory.resolve("livephoto-output-6254129389327106552.stage").apply { writeText("old") }
            val unrelated = directory.resolve("unrelated").apply { writeText("keep") }
            val nearMatch = directory.resolve("livephoto-input-not-a-number.snapshot").apply { writeText("keep") }
            val folder = directory.resolve("livephoto-output-12.stage").apply { assertTrue(mkdir()) }
            val nested = AndroidPrivateCache.newInput(folder).apply { writeText("keep") }
            // Windows may require elevated permissions to create symbolic links.
            val symlink = if (File.separatorChar == '/') {
                Files.createSymbolicLink(directory.resolve("livephoto-input-42.snapshot").toPath(), unrelated.toPath())
            } else null
            repeat(2) { AndroidPrivateCache.reclaimAfterProcessDeath(directory) }
            assertFalse(snapshot.exists())
            assertFalse(stage.exists())
            assertFalse(legacy.exists())
            assertEquals("keep", unrelated.readText())
            assertEquals("keep", nearMatch.readText())
            assertEquals("keep", nested.readText())
            if (symlink != null) assertTrue(Files.isSymbolicLink(symlink))
        } finally { assertTrue(directory.deleteRecursively()) }
    }
}
