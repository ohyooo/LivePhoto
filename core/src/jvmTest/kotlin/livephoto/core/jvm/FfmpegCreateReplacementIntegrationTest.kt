package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.*

class FfmpegCreateReplacementIntegrationTest {
    @Test fun originalFrameIsSelectedBeforeTrimAndOnlyFinalCompositeIsPublished(): Unit = runImmediate {
        val found = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(found.ffmpegPath)
        assumeTrue("No existing FFmpeg; real Create replacement not run", found.ffmpegPath != null)
        val directory = Files.createTempDirectory("livephoto-create-replacement-"); val file = directory.resolve("source.mp4")
        val context = Context(Limits(128_000_000uL, 128_000_000uL))
        try {
            val generated = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror", "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "12",
                "-vf", "setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709", "-c:v", "libx264", "-preset", "ultrafast", "-bf", "0", "-g", "3", "-sc_threshold", "0", "-pix_fmt", "yuv420p",
                "-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", file.toString()), 60_000L)
            assertEquals(0, generated.code, generated.output)
            val bytes = Files.readAllBytes(file); val source = FileBinarySource(file); val core = DefaultLivePhotoCore(found.backend)
            val image = MemoryBinarySource(Bytes(GoogleFixtures.jpeg()), SourceId("real-create-replacement-image"))
            val carrier = MemoryBinarySource(Bytes(GoogleFixtures.v1Photo(bytes, "160000")), SourceId("real-convert-replacement-carrier"))
            val expectedFrame = core.extractFrame(ExtractFrameRequest(ResourceRef(SourceSet.Single(source)), CoverPosition.FrameIndex(1uL), ImageEncoding(ImageFormat.Jpeg),
                MemoryOutputTransaction(context, "independent-original-frame"), context)).orThrow()
            val expectedReader = BinaryReader(expectedFrame.operation.output.assets.single().readableSource!!, context)
            val expectedCoding = codingDigest(SourceSession.open(SourceSet.Single(expectedReader.source), context, ParseBudget(context)).orThrow())
            val trim = TrimSpec(TimeRange(Time(120, 1000u), Time(280, 1000u)), TrimMode.LosslessOnly)
            for (convert in listOf(false, true)) for (trimmed in listOf(false, true)) {
                val tx = MemoryOutputTransaction(context, "real-replacement-$convert-$trimmed")
                var commits = 0
                val output = object : OutputTransaction by tx {
                    override suspend fun commit(): CoreResult<Receipt> { commits++; return tx.commit() }
                }
                val edits = EditSpec(trim = if (trimmed) trim else null, keyPosition = if (convert) null else CoverPosition.FrameIndex(4uL), replacementFrame = CoverPosition.FrameIndex(1uL))
                val result = (if (convert) core.convert(ConvertRequest(SourceSet.Single(carrier), ProtocolSelector(ProtocolIds.GoogleV2), edits = edits, output = output, context = context))
                    else core.create(CreateRequest(image, source, ProtocolSelector(ProtocolIds.GoogleV2), edits = edits, output = output, context = context))).orThrow()
                try {
                    assertEquals(1, commits); assertEquals(1, tx.committedAssets().size)
                    val after = SourceSession.open(SourceSet.Single(result.output.assets.single().readableSource!!), context, ParseBudget(context)).orThrow()
                    assertEquals(expectedCoding, codingDigest(after)) // source frame 1@40ms, outside retained [120,280)
                    assertEquals(0, result.keyPhoto!!.position!!.compareTo(Time(if (trimmed) 40 else 160, 1000u)))
                    assertEquals(if (trimmed) 4 else 12, after.videos.values.single().tracks.single().samples.size)
                    assertEquals(GuaranteeOutcome.Changed, result.preservation.records.single { it.guarantee == Guarantee.ImageDataPreserving }.outcome)
                    assertTrue(result.execution.none { it.transcoded }); assertTrue(after.gainMaps.isEmpty())
                    assertTrue(core.probe(ProbeRequest(ResourceRef(SourceSet.Single(result.output.assets.single().readableSource!!)), true, context)).orThrow().issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                    assertTrue(core.probe(ProbeRequest(ResourceRef(SourceSet.Single(result.output.assets.single().readableSource!!), videoId(ProtocolIds.GoogleV2)), true, context)).orThrow().issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                } finally { result.output.assets.forEach { it.readableSource?.close() } }
            }
            assertContentEquals(bytes, Files.readAllBytes(file)); expectedFrame.operation.output.assets.forEach { it.readableSource?.close() }
            source.close(); image.close(); carrier.close()
        } finally { Files.deleteIfExists(file); Files.delete(directory) }
    }
}
