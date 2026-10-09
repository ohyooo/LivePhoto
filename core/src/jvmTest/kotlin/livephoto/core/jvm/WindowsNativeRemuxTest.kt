package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Native compressed-packet experiment, never a public remux/preservation capability. */
class WindowsNativeRemuxTest {
    @Test fun privateRequestsAreBoundedAndCannotNameOtherOutputs() {
        val dir = Path.of(System.getProperty("java.home")).toAbsolutePath()
        val args = listOf("--remux-video-private", dir.resolve("input.mp4").toString(), dir.resolve("remux.mp4").toString(), "2", "8000000", "16065536", "0:400000,400000:800000")
        assertNotNull(WindowsNativeRemux.request(args.toTypedArray()))
        for ((index, value) in listOf(2 to dir.resolve("input.mp4").toString(), 2 to dir.resolve("other.mp4").toString(),
            2 to dir.parent.resolve("remux.mp4").toString(), 3 to "0", 3 to "65", 4 to "8000001", 5 to "16065537", 0 to "--remux",
            6 to "0:1", 6 to "0:400000,500000:800000", 6 to "0:-1,400000:800000", 6 to "0:400000,0:800000",
            6 to "0:400000,400000:9223372036854775807")) {
            val bad = args.toMutableList(); bad[index] = value
            assertNull(WindowsNativeRemux.request(bad.toTypedArray()))
        }
    }
    @Test fun actualCompressedPacketsAreVerifiedIndependentlyOfPublicRemux(): Unit = runImmediate {
        val backends = WindowsMediaFoundationBackend.available()
        if (System.getenv("LIVEPHOTO_REQUIRE_WINDOWS_MEDIA") == "true") assertEquals(1, backends.size)
        assumeTrue("Windows native experiment unavailable; no remux was run", backends.isNotEmpty())
        assertEquals(Implementation.Experimental, backends.single().capabilities().operations.single { it.operation == Operation.Remux }.implementation)
        val context = Context(Limits(128_000_000uL, 128_000_000uL))
        val dir = Files.createTempDirectory("livephoto-native-remux-test-")
        val input = dir.resolve("input.mp4"); val output = dir.resolve("remux.mp4")
        try {
            val text = javaClass.getResourceAsStream("/windows-media/frame-baseline.mp4.base64")!!.use { it.readNBytes(100_000).toString(Charsets.US_ASCII).trim() }
            val bytes = java.util.Base64.getDecoder().decode(text)
            fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals("0c7ebf0ca88f7d601ad953353321cc048a84cd5785392f3ab6cb7718c6193dd6", hash(bytes))
            Files.write(input, bytes)
            val original = FileBinarySource(input)
            val exactTimeline = try {
                val track = BmffVideoProbe(BinaryReader(original, context)).probe(ByteRange(0uL, original.size().orThrow())).orThrow().tracks.single()
                fun ticks(value: Long): Long {
                    val product = Math.multiplyExact(value, 10_000_000L)
                    assertEquals(0L, product % track.timescale.toLong())
                    return product / track.timescale.toLong()
                }
                track.samples.joinToString(",") { "${ticks(it.presentationTime)}:${ticks(it.duration.toLong())}" }
            } finally { original.close() }
            val java = Path.of(System.getProperty("java.home"), "bin", "java.exe")
            val classes = listOf(WindowsMediaApiWorker::class.java, Unit::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct()
            val command = listOf(java.toString(), "-Xms16m", "-Xmx128m", "--enable-native-access=ALL-UNNAMED", "-cp",
                classes.joinToString(File.pathSeparator), "livephoto.core.jvm.WindowsMediaApiWorker", "--remux-video-private", input.toString(), output.toString(), "8", "8000000", "16065536", exactTimeline)
            val process = ExternalProcess.run(command, 30_000, context)
            assertEquals(0, process.code, process.output); assertFalse(process.ioFailed || process.timedOut || process.outputLimited || process.cancelled)
            assertTrue(process.output.trim().startsWith("WINDOWS_MEDIA_API_REMUX=SUCCESS scope=private-compressed-video-not-preservation samples=8 "), process.output)
            val source = FileBinarySource(input); val target = FileBinarySource(output)
            try {
                val beforeReader = BinaryReader(source, context); val afterReader = BinaryReader(target, context)
                val before = BmffVideoProbe(beforeReader).probe(ByteRange(0uL, source.size().orThrow())).orThrow()
                val after = BmffVideoProbe(afterReader).probe(ByteRange(0uL, target.size().orThrow())).orThrow()
                assertEquals(VideoContainer.Mp4, after.container)
                val left = before.tracks.single(); val right = after.tracks.single()
                assertEquals(VideoCodec.Avc, right.codec); assertEquals(8, right.samples.size)
                assertEquals(left.width, right.width); assertEquals(left.height, right.height)
                for ((a, b) in left.samples.zip(right.samples)) {
                    assertEquals(a.range.length, b.range.length, "Encoded sample length")
                    assertEquals(sha256Range(beforeReader, a.range).orThrow(), sha256Range(afterReader, b.range).orThrow(), "Encoded sample bytes")
                    assertEquals(0, Time(a.presentationTime, left.timescale).compareTo(Time(b.presentationTime, right.timescale)), "PTS")
                    assertEquals(0, Time(a.duration.toLong(), left.timescale).compareTo(Time(b.duration.toLong(), right.timescale)), "Duration ${a.decodeTime}: ${a.duration}/${left.timescale} -> ${b.duration}/${right.timescale}")
                    assertEquals(a.isSync, b.isSync, "Sync flags")
                }
                // Native metadata is not accepted as preservation evidence. Restore the
                // classified source envelope using verified native packets, then independently
                // verify the complete result. This remains a same-MP4 internal experiment.
                val restored = TestSink()
                RemuxEnvelopeRestoration.write(beforeReader, afterReader, restored).orThrow()
                assertContentEquals(bytes, restored.written.toByteArray(), "Classified source envelope plus actual native packets")
                val restoredReader = BinaryReader(livephoto.core.memory.MemoryBinarySource(Bytes(restored.written.toByteArray()), SourceId("restored-native-packets")), context)
                val restoredFacts = BmffVideoProbe(restoredReader).probe(ByteRange(0uL, bytes.size.toULong())).orThrow()
                RemuxVerification.verify(beforeReader, before, restoredReader, restoredFacts)
                RemuxVerification.verifyMetadata(RemuxVerification.metadata(beforeReader, before), RemuxVerification.metadata(restoredReader, restoredFacts))
            } finally { target.close(); source.close() }
            val produced = Files.readAllBytes(output)
            val refused = ExternalProcess.run(command, 30_000, context)
            assertNotEquals(0, refused.code)
            assertContentEquals(produced, Files.readAllBytes(output), "Existing output must remain unchanged")
            assertContentEquals(bytes, Files.readAllBytes(input), "Input immutable")
            Files.delete(output)
            val withAudio = WindowsEncodedFixtures.bytes("audio")
            Files.write(input, withAudio)
            val audioRefused = ExternalProcess.run(command, 30_000, context)
            assertEquals(4, audioRefused.code, audioRefused.output)
            assertFalse(Files.exists(output), "A source with another track must not publish a partial video-only output")
            assertContentEquals(withAudio, Files.readAllBytes(input))
        } finally { Files.deleteIfExists(output); Files.deleteIfExists(input); Files.deleteIfExists(dir) }
    }
}
