package livephoto.desktop

import kotlinx.coroutines.CancellationException
import livephoto.core.Bytes
import livephoto.core.Digest
import java.nio.file.Files
import kotlin.test.*

class DesktopStorageTest {
    @Test fun verifiedSavePublishesExactBytesAndCannotReplaceAnExistingFile() {
        val root = Files.createTempDirectory("livephoto-save-test-")
        try {
            val bytes = Bytes(ByteArray(131079) { (it % 127).toByte() })
            val target = root.resolve("中文 output.jpg")
            publishFile(target, bytes, bytesDigest(bytes))
            assertContentEquals(bytes.toByteArray(), Files.readAllBytes(target))
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { publishFile(target, Bytes(byteArrayOf(9)), bytesDigest(Bytes(byteArrayOf(9)))) }
            assertContentEquals(bytes.toByteArray(), Files.readAllBytes(target))
            assertEquals(listOf(target), Files.list(root).use { it.toList() })
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    @Test fun failedVerificationAndCancelledSaveLeaveNoVisibleOrTemporaryOutput() {
        val root = Files.createTempDirectory("livephoto-save-failure-")
        try {
            val target = root.resolve("output.jpg")
            val bytes = Bytes(byteArrayOf(1, 2, 3))
            assertFailsWith<IllegalStateException> { publishFile(target, bytes, Digest("0".repeat(64))) }
            assertFailsWith<CancellationException> { publishFile(target, bytes, bytesDigest(bytes)) { true } }
            assertFalse(Files.exists(target))
            assertTrue(Files.list(root).use { it.findAny().isEmpty })
        } finally { Files.delete(root) }
    }
}
