package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.*
import livephoto.core.memory.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.*

class FfmpegTranscodeIntegrationTest {
    @Test fun realExplicitAvcTranscodePreservesEveryVfrBoundaryAndRefusesUnknownColor(): Unit = runImmediate {
        val found = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(found.ffmpegPath)
        assumeTrue("No existing FFmpeg; real explicit transcode not run", found.ffmpegPath != null)
        val directory = Files.createTempDirectory("livephoto-transcode-fixture-"); val file = directory.resolve("VFR source.mp4"); val unknown = directory.resolve("unknown color.mp4")
        val privateSei = directory.resolve("private SEI.mp4")
        val context = Context(Limits(128_000_000uL, 128_000_000uL))
        fun generate(path: Path, colors: Boolean) {
            val run = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror", "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "8",
                "-vf", "setpts='if(lt(N,4),N,4+(N-4)*2)/(25*TB)'" + (if (colors) ",setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709" else ""),
                "-fps_mode", "passthrough", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "30", "-bf", "0", "-g", "3", "-pix_fmt", "yuv420p", "-use_editlist", "0",
                "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", path.toString()), 60_000L)
            assertEquals(0, run.code, run.output); assertFalse(run.ioFailed)
        }
        try {
            generate(file, true); generate(unknown, false)
            val inserted = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror", "-i", file.toString(), "-map", "0", "-c", "copy",
                "-bsf:v", "h264_metadata=sei_user_data=01234567-89ab-cdef-0123-456789abcdef+private-note", "-use_editlist", "0", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-write_btrt", "0", privateSei.toString()), 60_000L)
            assertEquals(0, inserted.code, inserted.output)
            val source = FileBinarySource(file); val reader = BinaryReader(source, context); val size = reader.identity().orThrow().size
            val original = BmffVideoProbe(reader).probe(ByteRange(0uL, size)).orThrow(); val hash = sha256Range(reader, ByteRange(0uL, size)).orThrow()
            assertTrue(original.tracks.single().samples.map { it.duration }.distinct().size > 1)
            val core = DefaultLivePhotoCore(found.backend); val tx = MemoryOutputTransaction(context, "real-transcode")
            val run = core.transcode(TranscodeRequest(ResourceRef(SourceSet.Single(source)), VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4), MutationPolicy(transcode = TranscodePolicy.Explicit), tx, context))
            val result = assertIs<CoreResult.Success<OperationResult>>(run, run.toString()).value
            try {
                val outputReader = BinaryReader(result.output.assets.single().readableSource!!, context)
                val actual = BmffVideoProbe(outputReader).probe(ByteRange(0uL, outputReader.identity().orThrow().size)).orThrow()
                verifyTranscode(reader, original, outputReader, actual, VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4))
                assertNotEquals(original.tracks.single().codecConfiguration, actual.tracks.single().codecConfiguration)
                assertEquals(GuaranteeOutcome.Changed, result.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }.outcome)
                assertTrue(result.execution.any { it.stage == Stage.Transcode && it.transcoded && it.hardwareUsed == false })
                assertTrue(core.probe(ProbeRequest(ResourceRef(SourceSet.Single(result.output.assets.single().readableSource!!)), true, context)).orThrow().issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
            } finally { result.output.assets.forEach { it.readableSource?.close() } }
            assertEquals(hash, sha256Range(reader, ByteRange(0uL, size)).orThrow()); source.close()
            val badSource = FileBinarySource(unknown); val badTx = MemoryOutputTransaction(context, "unknown-color-transcode")
            try {
                assertEquals("HDR_PRESERVATION_UNAVAILABLE", assertIs<CoreResult.Failure>(core.transcode(TranscodeRequest(ResourceRef(SourceSet.Single(badSource)), VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4), MutationPolicy(transcode = TranscodePolicy.Explicit), badTx, context))).error.code.value)
                assertTrue(badTx.committedAssets().isEmpty())
            } finally { badSource.close() }
            val privateSource = FileBinarySource(privateSei); val privateTx = MemoryOutputTransaction(context, "private-sei-transcode")
            try {
                assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(core.transcode(TranscodeRequest(ResourceRef(SourceSet.Single(privateSource)), VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4), MutationPolicy(transcode = TranscodePolicy.Explicit), privateTx, context))).error.code.value)
                assertTrue(privateTx.committedAssets().isEmpty())
            } finally { privateSource.close() }
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(unknown); Files.deleteIfExists(privateSei); Files.delete(directory) }
    }
}
