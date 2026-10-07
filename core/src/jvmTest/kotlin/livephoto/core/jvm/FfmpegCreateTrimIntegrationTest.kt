package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.*

class FfmpegCreateTrimIntegrationTest {
    @Test fun realCreateAndConvertUseActualTrimmedLengthAndPreserveSourceKeyMapping(): Unit = runImmediate {
        val found = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(found.ffmpegPath)
        assumeTrue("No existing FFmpeg; real Create/Convert trim not run", found.ffmpegPath != null)
        val directory = Files.createTempDirectory("livephoto-create-trim-fixture-"); val file = directory.resolve("source.mp4")
        val context = Context(Limits(128_000_000uL, 128_000_000uL))
        try {
            val generated = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror", "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "12",
                "-c:v", "libx264", "-preset", "ultrafast", "-bf", "0", "-g", "3", "-sc_threshold", "0", "-pix_fmt", "yuv420p", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", file.toString()), 60_000L)
            assertEquals(0, generated.code, generated.output)
            val originalVideo = Files.readAllBytes(file); val source = FileBinarySource(file); val sourceReader = BinaryReader(source, context)
            val before = BmffVideoProbe(sourceReader).probe(ByteRange(0uL, originalVideo.size.toULong())).orThrow()
            val originalHash = sha256Range(sourceReader, before.range).orThrow()
            val spec = TrimSpec(TimeRange(Time(120, 1000u), Time(280, 1000u)), TrimMode.LosslessOnly)
            val plan = planLosslessTrim(sourceReader, before, spec); val core = DefaultLivePhotoCore(found.backend)
            val image = MemoryBinarySource(Bytes(GoogleFixtures.jpeg()), SourceId("real-trim-create-image"))
            val originalLive = MemoryBinarySource(Bytes(GoogleFixtures.v1Photo(originalVideo, timestamp = "160000")), SourceId("real-trim-convert-source"))
            for (convert in listOf(false, true)) {
                val tx = MemoryOutputTransaction(context, "real-create-trim-$convert")
                val result = (if (convert) core.convert(ConvertRequest(SourceSet.Single(originalLive), ProtocolSelector(ProtocolIds.GoogleV2), edits = EditSpec(trim = spec), policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = tx, context = context))
                    else core.create(CreateRequest(image, source, ProtocolSelector(ProtocolIds.GoogleV2), edits = EditSpec(spec, CoverPosition.FrameIndex(4uL)), policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = tx, context = context))).orThrow()
                try {
                    val after = SourceSession.open(SourceSet.Single(result.output.assets.single().readableSource!!), context, ParseBudget(context)).orThrow()
                    val video = after.videos.values.single(); assertEquals(4, video.tracks.single().samples.size)
                    assertTrue(after.bindings.single().video!!.length < originalVideo.size.toULong())
                    assertEquals(0, after.inspection.keyPhoto.position!!.compareTo(Time(40, 1000u)))
                    verifyTrimDurationHeaders(after.reader, video, plan); RemuxVerification.verify(sourceReader, plan.expected(), after.reader, video)
                    assertEquals(GuaranteeOutcome.Verified, result.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }.outcome)
                    assertTrue(result.execution.any { it.stage == Stage.Trim }); assertTrue(result.execution.none { it.transcoded })
                    assertTrue(core.probe(ProbeRequest(ResourceRef(SourceSet.Single(result.output.assets.single().readableSource!!), videoId(ProtocolIds.GoogleV2)), true, context)).orThrow().issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                } finally { result.output.assets.forEach { it.readableSource?.close() } }
            }
            val outside = MemoryBinarySource(Bytes(GoogleFixtures.v1Photo(originalVideo, timestamp = "280000")), SourceId("real-trim-half-open-key"))
            val clamped = core.convert(ConvertRequest(SourceSet.Single(outside), ProtocolSelector(ProtocolIds.GoogleV2), edits = EditSpec(trim = spec.copy(keyOutside = KeyOutsidePolicy.ClampExplicitly)), output = MemoryOutputTransaction(context, "real-trim-clamped"), context = context)).orThrow()
            assertEquals(0, clamped.keyPhoto!!.position!!.compareTo(Time(120, 1000u))) // last retained source frame=240ms, not end=280ms
            clamped.output.assets.forEach { it.readableSource?.close() }; outside.close()
            assertEquals(originalHash, sha256Range(sourceReader, before.range).orThrow()); source.close(); image.close(); originalLive.close()
        } finally { Files.deleteIfExists(file); Files.delete(directory) }
    }
}
