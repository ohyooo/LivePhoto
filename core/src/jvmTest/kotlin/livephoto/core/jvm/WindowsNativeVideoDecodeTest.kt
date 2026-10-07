package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.BmffVideoProbe
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

/** OS decoder evidence, not a vendor/device fixture or public backend coverage claim. */
class WindowsNativeVideoDecodeTest {
    private fun worker(args: List<String>): ProcessResult {
        val javaPath = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val classes = listOf(WindowsMediaApiWorker::class.java, Unit::class.java).map {
            Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
        }.distinct()
        return ExternalProcess.run(listOf(javaPath.toString(), "-Xms16m", "-Xmx128m", "--enable-native-access=ALL-UNNAMED", "-cp",
            classes.joinToString(File.pathSeparator), "livephoto.core.jvm.WindowsMediaApiWorker") + args, 30_000)
    }
    private fun usable(): Boolean {
        val result = worker(listOf("--preflight"))
        assertFalse(result.timedOut || result.ioFailed || result.outputLimited)
        if (System.getenv("LIVEPHOTO_REQUIRE_WINDOWS_MEDIA") == "true") assertEquals(0, result.code, result.output)
        if (result.code == 3) { assertTrue(result.output.contains("PREFLIGHT=UNAVAILABLE")); return false }
        assertEquals(0, result.code, result.output)
        return true
    }
    @Test fun invalidDecodeLimitsAreRejectedBeforeAnyNativeAccess() {
        for (args in listOf(listOf("--decode-video"), listOf("--decode-video", "relative.mp4", "1", "2048", "128000000"),
            listOf("--decode-video", Path.of(System.getProperty("java.home")).toString(), "0", "2048", "128000000"))) {
            val result = worker(args)
            assertEquals(2, result.code, result.output)
            assertFalse(result.timedOut || result.ioFailed || result.outputLimited)
        }
    }
    @Test fun actualAvcDecodeReachesEosWithIndependentPresentationTimeline(): Unit = runImmediate {
        if (!usable()) return@runImmediate // Explicit unavailable evidence, not a claimed decoder success.
        val found = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        assertNotNull(found.ffmpegPath, "Existing FFmpeg is required to generate this explicitly synthetic encoder fixture")
        val directory = Files.createTempDirectory("livephoto-os-decode-")
        val owned = mutableListOf<Path>()
        try {
            for (bFrames in listOf(0, 2)) {
                val path = directory.resolve("avc B $bFrames.mp4"); owned.add(path)
                val encoded = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error",
                    "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "4", "-c:v", "libx264", "-preset", "medium",
                    "-bf", bFrames.toString(), "-g", "4", "-pix_fmt", "yuv420p", path.toString()), 60_000)
                assertEquals(0, encoded.code, encoded.output)
                assertFalse(encoded.ioFailed || encoded.timedOut || encoded.outputLimited)
                val source = FileBinarySource(path)
                val context = Context(Limits(128_000_000uL, 128_000_000uL))
                val reader = BinaryReader(source, context)
                val range = ByteRange(0uL, source.size().orThrow())
                try {
                    val before = sha256Range(reader, range).orThrow()
                    val facts = BmffVideoProbe(reader).probe(range).orThrow()
                    val track = facts.tracks.single()
                    assertEquals(VideoCodec.Avc, track.codec)
                    val digest = MessageDigest.getInstance("SHA-256")
                    track.samples.sortedBy { it.presentationTime }.forEach {
                        val scaled = Math.multiplyExact(it.presentationTime, 10_000_000L)
                        assertEquals(0L, scaled % track.timescale.toLong())
                        digest.update(ByteBuffer.allocate(8).putLong(scaled / track.timescale.toLong()).array())
                    }
                    val expected = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    val result = worker(listOf("--decode-video", path.toString(), "4", "65536", "128000000"))
                    assertEquals(0, result.code, result.output)
                    assertFalse(result.timedOut || result.ioFailed || result.outputLimited)
                    assertTrue(result.output.contains("DECODE=SUCCESS scope=selected-avc-video frames=4 width=64 height=64 ptsSha256=$expected"), result.output)
                    val bounded = worker(listOf("--decode-video", path.toString(), "1", "65536", "128000000"))
                    assertEquals(4, bounded.code, bounded.output)
                    assertTrue(bounded.output.contains("DECODE=FAILED")); assertFalse(bounded.output.contains("DECODE=SUCCESS"))
                    assertFalse(bounded.timedOut || bounded.ioFailed || bounded.outputLimited)
                    assertEquals(before, sha256Range(reader, range).orThrow())
                } finally { source.close() }
            }
            val small = directory.resolve("below OS decoder minimum.mp4"); owned.add(small)
            val encoded = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=32x32:rate=25", "-frames:v", "4", "-c:v", "libx264", "-pix_fmt", "yuv420p", small.toString()), 60_000)
            assertEquals(0, encoded.code, encoded.output)
            assertFalse(encoded.ioFailed || encoded.timedOut || encoded.outputLimited)
            val smallBytes = Files.readAllBytes(small)
            val rejected = worker(listOf("--decode-video", small.toString(), "4", "65536", "128000000"))
            assertEquals(4, rejected.code, rejected.output); assertFalse(rejected.output.contains("DECODE=SUCCESS"))
            assertFalse(rejected.ioFailed || rejected.timedOut || rejected.outputLimited)
            assertContentEquals(smallBytes, Files.readAllBytes(small))
        } finally { owned.forEach { Files.deleteIfExists(it) }; Files.deleteIfExists(directory) }
    }
    @Test fun malformedLocalMediaNeverReportsDecodeSuccess() {
        if (!usable()) return
        val path = Files.createTempFile("livephoto-invalid-os-", ".mp4")
        try {
            Files.write(path, byteArrayOf(1, 2, 3, 4))
            val result = worker(listOf("--decode-video", path.toString(), "4", "65536", "128000000"))
            assertEquals(4, result.code, result.output)
            assertTrue(result.output.contains("DECODE=FAILED")); assertFalse(result.output.contains("DECODE=SUCCESS"))
            assertFalse(result.timedOut || result.ioFailed || result.outputLimited)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(path))
        } finally { Files.deleteIfExists(path) }
    }
}
