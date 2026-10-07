package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.selectFrame
import livephoto.core.memory.MemoryOutputTransaction
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Real decoder tests on explicit synthetic fixtures; not device compatibility certification. */
class FfmpegFrameIntegrationTest {
    private val context = Context(Limits(128_000_000uL, 128_000_000uL))
    private fun discovery(): BackendDiscovery {
        val found = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(found.ffmpegPath, "Required existing FFmpeg is absent")
        assumeTrue("No existing FFmpeg; real frame extraction was not run", found.ffmpegPath != null)
        return found
    }
    internal suspend fun fixture(found: BackendDiscovery, path: Path, colors: Boolean = true) {
        val arguments = listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror",
            "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "8", "-vf", "setpts='if(lt(N,4),N,4+(N-4)*2)/(25*TB)'" +
                (if (colors) ",setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709" else ""), "-fps_mode", "vfr",
            "-c:v", "libx264", "-preset", "medium", "-bf", "2", "-g", "8", "-pix_fmt", "yuv420p") +
            (if (colors) listOf("-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709") else emptyList()) +
            listOf("-map_metadata", "-1", "-map_chapters", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", path.toString())
        val process = ExternalProcess.run(arguments, 60_000L)
        assertEquals(0, process.code, process.output); assertFalse(process.ioFailed)
    }
    @Test fun realVfrBFrameSelectionMatchesExactPtsAndNeverSelectsSyncInstead(): Unit = runImmediate {
        val found = discovery(); val directory = Files.createTempDirectory("livephoto-frame-fixture-"); val file = directory.resolve("B frames VFR.mp4")
        try {
            fixture(found, file)
            val input = FileBinarySource(file)
            try {
                val reader = BinaryReader(input, context); val range = ByteRange(0uL, reader.identity().orThrow().size)
                val before = sha256Range(reader, range).orThrow(); val video = BmffVideoProbe(reader).probe(range).orThrow()
                val track = video.tracks.single()
                assertTrue(track.samples.map { it.presentationTime } != track.samples.map { it.presentationTime }.sorted(), "Fixture must actually contain reordered B frames")
                assertTrue(track.samples.map { it.duration }.distinct().size > 1, "Fixture must actually be VFR")
                val selected = selectFrame(video, CoverPosition.FrameIndex(3uL)); assertFalse(selected.sample.isSync)
                val core = DefaultLivePhotoCore(found.backend)
                val byIndexRun = core.extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(input)), CoverPosition.FrameIndex(3uL), ImageEncoding(ImageFormat.Jpeg), MemoryOutputTransaction(context, "frame-index"), context))
                val byIndex = assertIs<CoreResult.Success<FrameResult>>(byIndexRun, byIndexRun.toString()).value
                try {
                    assertEquals(selected.time, byIndex.actualTime); assertEquals(3uL, byIndex.actualFrameIndex)
                    val byTimeRun = core.extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(input)), CoverPosition.Timestamp(selected.time, Selection.Exact), ImageEncoding(ImageFormat.Jpeg), MemoryOutputTransaction(context, "frame-time"), context))
                    val byTime = assertIs<CoreResult.Success<FrameResult>>(byTimeRun, byTimeRun.toString()).value
                    try { assertEquals(byIndex.operation.output.assets.single().digest, byTime.operation.output.assets.single().digest) }
                    finally { byTime.operation.output.assets.forEach { it.readableSource?.close() } }
                    val zeroRun = core.extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(input)), CoverPosition.FrameIndex(0uL), ImageEncoding(ImageFormat.Jpeg), MemoryOutputTransaction(context, "frame-zero"), context))
                    val zero = assertIs<CoreResult.Success<FrameResult>>(zeroRun, zeroRun.toString()).value
                    try { assertNotEquals(zero.operation.output.assets.single().digest, byIndex.operation.output.assets.single().digest) }
                    finally { zero.operation.output.assets.forEach { it.readableSource?.close() } }
                    assertTrue(byIndex.operation.execution.any { it.stage == Stage.EncodeImage }); assertTrue(byIndex.operation.execution.none { it.transcoded || it.remuxed })
                    assertEquals(before, sha256Range(reader, range).orThrow())
                } finally { byIndex.operation.output.assets.forEach { it.readableSource?.close() } }
            } finally { input.close() }
        } finally { Files.deleteIfExists(file); Files.delete(directory) }
    }
    @Test fun unknownColorCannotBeSilentlyAssumedSdr(): Unit = runImmediate {
        val found = discovery(); val directory = Files.createTempDirectory("livephoto-frame-unknown-"); val file = directory.resolve("unknown color.mp4")
        try {
            fixture(found, file, colors = false)
            val input = FileBinarySource(file); val output = MemoryOutputTransaction(context, "frame-unknown")
            try {
                val run = DefaultLivePhotoCore(found.backend).extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(input)), CoverPosition.FrameIndex(0uL), ImageEncoding(ImageFormat.Jpeg), output, context))
                assertEquals("HDR_PRESERVATION_UNAVAILABLE", assertIs<CoreResult.Failure>(run).error.code.value)
                assertTrue(output.committedAssets().isEmpty())
            } finally { input.close() }
        } finally { Files.deleteIfExists(file); Files.delete(directory) }
    }
    @Test fun originalReferenceColorAndMetadataMustNotBeGuessed(): Unit = runImmediate {
        val found = discovery()
        val file = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }.map { it.resolve("reference/video.mp4") }.first { Files.isRegularFile(it) }
        val input = FileBinarySource(file); val output = MemoryOutputTransaction(context, "frame-reference")
        try {
            val run = DefaultLivePhotoCore(found.backend).extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(input)), CoverPosition.FrameIndex(0uL), ImageEncoding(ImageFormat.Jpeg), output, context))
            val error = assertIs<CoreResult.Failure>(run).error.code.value
            assertTrue(error in setOf("UNSAFE_METADATA_REWRITE", "HDR_PRESERVATION_UNAVAILABLE", "CAPABILITY_UNSUPPORTED"), error)
            assertTrue(output.committedAssets().isEmpty())
        } finally { input.close() }
    }
}
