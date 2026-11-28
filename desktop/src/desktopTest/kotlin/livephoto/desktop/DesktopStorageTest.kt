package livephoto.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import livephoto.core.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopStorageTest {
    @Test fun motionPhotoSaveSuggestionsDoNotOverrideChosenNames() = runBlocking {
        val root = Files.createTempDirectory("livephoto-save-name-test-")
        val image = root.resolve("input.jpg")
        val video = root.resolve("input.mp4")
        val customPhoto = root.resolve("我的照片.jpg")
        val customCopy = root.resolve("我的副本.jpg")
        val selected = ArrayDeque(listOf(customPhoto, customCopy))
        val offered = mutableListOf<String?>()
        val ffmpeg = assertNotNull(System.getenv("LIVEPHOTO_FFMPEG"))
        fun generate(vararg args: String) {
            val process = ProcessBuilder(listOf(ffmpeg, "-nostdin", "-n", "-hide_banner", "-loglevel", "error") + args)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            try { assertTrue(process.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()) }
            finally { if (process.isAlive) process.destroyForcibly() }
        }
        try {
            generate("-f", "lavfi", "-i", "color=c=blue:s=64x64", "-frames:v", "1", "-threads", "1", "-update", "1", image.toString())
            generate("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-t", "2", "-c:v", "libx264", "-threads", "1",
                "-pix_fmt", "yuv420p", "-bf", "0", "-g", "25", "-use_editlist", "0", "-map_metadata", "-1",
                "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", video.toString())
            DesktopPlatform { _, name -> offered.add(name); selected.removeFirst() }.use { host ->
                val target = ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg"))
                assertIs<CoreResult.Success<*>>(host.create(host.register(image), host.register(video), target))
                assertTrue(Files.size(customPhoto) > Files.size(video))
                assertIs<CoreResult.Success<*>>(host.convertPhoto(host.register(customPhoto), target))
                assertEquals(listOf<String?>("LivePhoto-google.motionphoto.v2_MP.jpg", "LivePhoto-converted_MP.jpg"), offered.toList())
                assertContentEquals(Files.readAllBytes(customPhoto), Files.readAllBytes(customCopy))
                assertFalse(Files.exists(root.resolve(assertNotNull(offered.first()))))
            }
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

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
