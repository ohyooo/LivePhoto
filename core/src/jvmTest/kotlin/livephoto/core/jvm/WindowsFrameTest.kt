package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.selectFrame
import livephoto.core.memory.MemoryOutputTransaction
import java.nio.file.Files
import java.nio.file.Path
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.*

class WindowsFrameTest {
    private val context = Context(Limits(128_000_000uL, 128_000_000uL))
    @Test fun invalidSelectionsAreRejectedBeforeNativeAccess() {
        val directory = Path.of(System.getProperty("java.home")).toAbsolutePath()
        val input = directory.resolve("input.mp4")
        val args = listOf("--select-video-frame", input.toString(), "8", "65536", "128000000", "3", directory.resolve("selected.nv12").toString())
        assertNotNull(WindowsNativeVideoDecode.request(args.toTypedArray()))
        for ((index, value) in listOf(2 to "65", 5 to "-1", 5 to "8", 6 to directory.resolve("wrong.nv12").toString(),
            6 to directory.parent.resolve("selected.nv12").toString())) {
            val malformed = args.toMutableList(); malformed[index] = value
            assertNull(WindowsNativeVideoDecode.request(malformed.toTypedArray()))
        }
    }
    @Test fun packedPixelBoundsAndIndependentNeutralTransferVectors() {
        assertEquals(0, WindowsFramePixels.srgb(0.0)); assertEquals(255, WindowsFramePixels.srgb(1.0))
        assertEquals(139, WindowsFramePixels.srgb(0.5))
        val bytes = ByteArray(64 * 64 * 3 / 2) { if (it < 4096) 16 else 128.toByte() }
        assertEquals(0, WindowsFramePixels.image(bytes, 64, 64).getRGB(10, 10) and 0xffffff)
        bytes.fill(235.toByte(), 0, 4096)
        assertEquals(0xffffff, WindowsFramePixels.image(bytes, 64, 64).getRGB(10, 10) and 0xffffff)
        bytes.fill(63, 0, 4096)
        for (offset in 4096 until bytes.size step 2) { bytes[offset] = 102; bytes[offset + 1] = 240.toByte() }
        val red = WindowsFramePixels.image(bytes, 64, 64).getRGB(10, 10)
        assertEquals(255, (red ushr 16) and 255); assertTrue(((red ushr 8) and 255) <= 3); assertTrue((red and 255) <= 3)
        assertFailsWith<IllegalArgumentException> { WindowsFramePixels.image(bytes.copyOf(bytes.size - 1), 64, 64) }
        assertFailsWith<IllegalArgumentException> { WindowsFramePixels.image(bytes, 63, 64) }
        assertFailsWith<IllegalArgumentException> { WindowsFramePixels.image(bytes, Int.MAX_VALUE, Int.MAX_VALUE) }
        assertFailsWith<IllegalStateException> { WindowsFramePixels.image(bytes, 64, 64) { error("cancelled") } }
    }
    @Test fun actualSystemFrameMatchesIndependentPackedPixelsAndExactVfrBFrameSelection(): Unit = runImmediate {
        checkProfile("main")
    }
    @Test fun actualHighEightBitFramesMatchIndependentPixelsWithoutHdrDowngrade(): Unit = runImmediate {
        checkProfile("high")
    }
    @Test fun actualQuickTimeNclcFramesMatchIndependentPixelsWithoutRangeGuessing(): Unit = runImmediate {
        checkProfile("high", mov = true)
    }
    private suspend fun checkProfile(profile: String, mov: Boolean = false) {
        val backends = WindowsMediaFoundationBackend.available()
        if (System.getenv("LIVEPHOTO_REQUIRE_WINDOWS_MEDIA") == "true") assertEquals(1, backends.size)
        assumeTrue("Windows decoder is unavailable; no OS extraction was run", backends.isNotEmpty())
        val ffmpeg = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of)).ffmpegPath
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(ffmpeg)
        val dir = Files.createTempDirectory("livephoto-system-frame-test-")
        val extension = if (mov) "mov" else "mp4"
        val input = dir.resolve("main.$extension"); val raw = dir.resolve("selected.nv12"); val independent = dir.resolve("independent.nv12")
        val encoded = dir.resolve("encoded.mp4")
        val unknown = dir.resolve("unknown.mp4")
        fun process(args: List<String>) {
            val result = ExternalProcess.run(listOf(ffmpeg.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror") + args, 60_000)
            assertEquals(0, result.code, result.output); assertFalse(result.ioFailed || result.timedOut || result.outputLimited)
        }
        try {
            if (ffmpeg != null) {
            process(listOf("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "8", "-vf", "setpts='if(lt(N,4),N,4+(N-4)*2)/(25*TB)',setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709",
                "-fps_mode", "vfr", "-c:v", "libx264", "-profile:v", profile, "-bf", "2", "-g", "8", "-pix_fmt", "yuv420p",
                "-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-chroma_sample_location", "left",
                "-bsf:v", "filter_units=remove_types=6",
                "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", (if (mov) encoded else input).toString()))
            if (mov) process(listOf("-i", encoded.toString(), "-map", "0:v:0", "-c", "copy", "-metadata:s:v", "encoder=",
                "-fflags", "+bitexact", "-movflags", "+write_colr", "-write_btrt", "0", "-f", "mov", input.toString()))
            process(listOf("-i", input.toString(), "-an", "-fps_mode", "passthrough", "-pix_fmt", "nv12", "-f", "rawvideo", independent.toString()))
            } else {
                Files.write(input, golden("$profile.$extension", if (mov) "5929ddf215c236013abb1dff65c2c3537a878fe4c2ad40fa0af7e46ada2efe19" else if (profile == "high") "1ecefdc76527df166b6795bc9eb06e7fd1de8905fb5a9fa442cc54728d896d39" else "3a48a592b23e8409646eee8bc6d016dba119f42cb8322ec4cec0cecf7412e5dd"))
                Files.write(independent, golden("$profile.nv12", if (profile == "high") "0da458ea1ac5c32d1a759c7ba0b126928c79f368432dbf259faef16b88678e14" else "42ae6dc2051cfb4d5e4170eb2c68cd88516ed578a889e24a89d6ea638f1c9562"))
            }
            val expected = Files.readAllBytes(independent); assertEquals(6144 * 8, expected.size)
            val java = Path.of(System.getProperty("java.home"), "bin", "java.exe")
            val classes = listOf(WindowsMediaApiWorker::class.java, Unit::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct()
            val command = listOf(java.toString(), "-Xms16m", "-Xmx128m", "--enable-native-access=ALL-UNNAMED", "-cp", classes.joinToString(File.pathSeparator), "livephoto.core.jvm.WindowsMediaApiWorker")
            for (index in 0..7) {
                val result = ExternalProcess.run(command + listOf("--select-video-frame", input.toString(), "8", "65536", "128000000", index.toString(), raw.toString()), 30_000)
                assertEquals(0, result.code, result.output); assertFalse(result.ioFailed || result.timedOut || result.outputLimited)
                assertContentEquals(expected.copyOfRange(index * 6144, (index + 1) * 6144), Files.readAllBytes(raw), "Presentation frame $index")
                if (index == 0) {
                    val refused = ExternalProcess.run(command + listOf("--select-video-frame", input.toString(), "8", "65536", "128000000", "0", raw.toString()), 30_000)
                    assertEquals(4, refused.code, refused.output)
                    assertContentEquals(expected.copyOfRange(0, 6144), Files.readAllBytes(raw), "Existing private output must not be overwritten")
                }
                Files.delete(raw)
            }
            val source = FileBinarySource(input)
            try {
                val reader = BinaryReader(source, context); val range = ByteRange(0uL, source.size().orThrow())
                val before = sha256Range(reader, range).orThrow(); val video = BmffVideoProbe(reader).probe(range).orThrow()
                assertEquals(if (mov) VideoContainer.Mov else VideoContainer.Mp4, video.container)
                assertTrue(video.tracks.single().samples.map { it.duration }.distinct().size > 1)
                val selected = selectFrame(video, CoverPosition.FrameIndex(3uL)); assertFalse(selected.sample.isSync)
                val output = MemoryOutputTransaction(context, "system-frame")
                val result = DefaultLivePhotoCore(backends.single()).extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(source)),
                    CoverPosition.FrameIndex(3uL), ImageEncoding(ImageFormat.Jpeg), output, context))
                val frame = assertIs<CoreResult.Success<FrameResult>>(result, result.toString()).value
                assertEquals(selected.time, frame.actualTime); assertEquals(3uL, frame.actualFrameIndex)
                assertTrue(frame.operation.execution.none { it.transcoded || it.remuxed })
                val jpeg = frame.operation.output.assets.single().readableSource!!
                try {
                    val bytes = BinaryReader(jpeg, context).readBuffer(0uL, jpeg.size().orThrow().toUInt()).orThrow().toByteArray()
                    val image = ImageIO.read(bytes.inputStream()); assertEquals(64, image.width); assertEquals(64, image.height)
                    val pixels = WindowsFramePixels.image(expected.copyOfRange(3 * 6144, 4 * 6144), 64, 64)
                    var total = 0L
                    for (y in 0 until 64) for (x in 0 until 64) for (shift in listOf(0, 8, 16))
                        total += kotlin.math.abs(((image.getRGB(x, y) ushr shift) and 255) - ((pixels.getRGB(x, y) ushr shift) and 255))
                    assertTrue(total / (64.0 * 64 * 3) < 12, "JPEG average error $total")
                } finally { jpeg.close() }
                assertEquals(before, sha256Range(reader, range).orThrow())
                val refused = MemoryOutputTransaction(context, "system-png")
                assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backends.single()).extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(source)),
                    CoverPosition.FrameIndex(0uL), ImageEncoding(ImageFormat.Png), refused, context)))
                assertTrue(refused.committedAssets().isEmpty())
                for (requestContext in listOf(context.copy(limits = context.limits.copy(maxMetadataBytes = 1uL)),
                    context.copy(cancellation = Cancellation { true }))) {
                    val refusedOutput = MemoryOutputTransaction(requestContext, "system-frame-limited")
                    assertIs<CoreResult.Failure>(DefaultLivePhotoCore(backends.single()).extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(source)),
                        CoverPosition.FrameIndex(0uL), ImageEncoding(ImageFormat.Jpeg), refusedOutput, requestContext)))
                    assertTrue(refusedOutput.committedAssets().isEmpty())
                }
            } finally { source.close() }
            if (ffmpeg != null) {
            process(listOf("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "4", "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p", unknown.toString()))
            } else Files.write(unknown, golden("unknown.mp4", "30ef2b00aec325af47749d08ab210a174fc18765da71d7f6f45281738f95fd6b"))
            val sourceUnknown = FileBinarySource(unknown); val output = MemoryOutputTransaction(context, "unknown-system-frame")
            try {
                val result = DefaultLivePhotoCore(backends.single()).extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(sourceUnknown)),
                    CoverPosition.FrameIndex(0uL), ImageEncoding(ImageFormat.Jpeg), output, context))
                assertIs<CoreResult.Failure>(result); assertTrue(output.committedAssets().isEmpty())
            } finally { sourceUnknown.close() }
        } finally {
            for (path in listOf(raw, independent, input, unknown, encoded)) Files.deleteIfExists(path)
            Files.deleteIfExists(dir)
        }
    }
    private fun golden(name: String, hash: String): ByteArray {
        val encoded = javaClass.getResourceAsStream("/windows-media/frame-$name.base64")!!.use { it.readNBytes(100_000).toString(Charsets.US_ASCII).trim() }
        val bytes = java.util.Base64.getDecoder().decode(encoded)
        assertEquals(hash, java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
        return bytes
    }
}
