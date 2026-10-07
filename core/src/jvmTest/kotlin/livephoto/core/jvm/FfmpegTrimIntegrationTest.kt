package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.memory.MemoryOutputTransaction
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.*

class FfmpegTrimIntegrationTest {
    private val context = Context(Limits(128_000_000uL, 128_000_000uL))
    private fun discovery(): BackendDiscovery {
        val found = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(found.ffmpegPath)
        assumeTrue("No existing FFmpeg; real lossless trim was not run", found.ffmpegPath != null)
        return found
    }
    @Test fun realClosedGopTrimKeepsSelectedSamplesWithoutEncodingOrHiddenContent(): Unit = runImmediate {
        val found = discovery(); val directory = Files.createTempDirectory("livephoto-trim-fixture-"); val file = directory.resolve("closed gop.mp4")
        try {
            val process = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror",
                "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "12", "-c:v", "libx264", "-preset", "ultrafast", "-bf", "0", "-g", "3", "-sc_threshold", "0", "-pix_fmt", "yuv420p",
                "-map_metadata", "-1", "-map_chapters", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", file.toString()), 60_000L)
            assertEquals(0, process.code, process.output)
            val input = FileBinarySource(file)
            try {
                val reader = BinaryReader(input, context); val range = ByteRange(0uL, reader.identity().orThrow().size); val beforeHash = sha256Range(reader, range).orThrow()
                val before = BmffVideoProbe(reader).probe(range).orThrow(); assertEquals(12, before.tracks.single().samples.size)
                val core = DefaultLivePhotoCore(found.backend)
                for ((mode, start, end, actualStart, actualEnd) in listOf(
                    listOf(TrimMode.LosslessOnly, 120L, 280L, 120L, 280L),
                    listOf(TrimMode.LosslessPreferred, 130L, 270L, 120L, 280L),
                    listOf(TrimMode.Exact, 120L, 280L, 120L, 280L))) {
                    val spec = TrimSpec(TimeRange(Time(start as Long, 1000u), Time(end as Long, 1000u)), mode as TrimMode)
                    val output = MemoryOutputTransaction(context, "real-trim-$mode")
                    val run = core.trim(TrimRequest(ResourceRef(SourceSet.Single(input)), spec, policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = output, context = context))
                    val result = assertIs<CoreResult.Success<TrimResult>>(run, run.toString()).value
                    try {
                        assertEquals(0, result.actualStart.compareTo(Time(actualStart as Long, 1000u))); assertEquals(0, result.actualEnd.compareTo(Time(actualEnd as Long, 1000u)))
                        assertFalse(result.wasTranscoded); assertFalse(result.retainedHiddenContent); assertTrue(result.wasBitstreamPreserved)
                        val asset = result.operation.output.assets.single(); val actualReader = BinaryReader(asset.readableSource!!, context)
                        val actual = BmffVideoProbe(actualReader).probe(ByteRange(0uL, actualReader.identity().orThrow().size)).orThrow()
                        assertEquals(4, actual.tracks.single().samples.size)
                        for ((left, right) in before.tracks.single().samples.subList(3, 7).zip(actual.tracks.single().samples))
                            assertEquals(sha256Range(reader, left.range).orThrow(), sha256Range(actualReader, right.range).orThrow())
                        // Real full decoding of the shortened output, distinct from structural/sample verification.
                        val decoded = core.probe(ProbeRequest(ResourceRef(SourceSet.Single(asset.readableSource)), true, context)).orThrow()
                        assertTrue(decoded.issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                    } finally { result.operation.output.assets.forEach { it.readableSource?.close() } }
                }
                val rejected = MemoryOutputTransaction(context, "real-trim-not-idr")
                val result = core.trim(TrimRequest(ResourceRef(SourceSet.Single(input)), TrimSpec(TimeRange(Time(130, 1000u), Time(270, 1000u)), TrimMode.LosslessOnly), output = rejected, context = context))
                assertEquals("LOSSLESS_TRIM_UNAVAILABLE", assertIs<CoreResult.Failure>(result).error.code.value); assertTrue(rejected.committedAssets().isEmpty())
                assertEquals(beforeHash, sha256Range(reader, range).orThrow())
            } finally { input.close() }
        } finally { Files.deleteIfExists(file); Files.delete(directory) }
    }
}
