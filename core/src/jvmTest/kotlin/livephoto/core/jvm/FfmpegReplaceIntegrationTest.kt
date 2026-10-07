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

class FfmpegReplaceIntegrationTest {
    @Test fun realGooglePrimaryReplacementRetainsVideoAndOnlyOptionallyUpdatesKey(): Unit = runImmediate {
        val found = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(found.ffmpegPath)
        assumeTrue("No existing FFmpeg; real replacement was not run", found.ffmpegPath != null)
        val directory = Files.createTempDirectory("livephoto-replace-fixture-"); val file = directory.resolve("replace video.mp4")
        val context = Context(Limits(128_000_000uL, 128_000_000uL))
        try {
            FfmpegFrameIntegrationTest().fixture(found, file)
            val videoBytes = Files.readAllBytes(file)
            // Explicit synthetic metadata-free JPEG + real generated video, never a device-photo claim.
            val originalBytes = GoogleFixtures.v1Photo(videoBytes)
            val input = MemoryBinarySource(Bytes(originalBytes), SourceId("real-replace-live"))
            val original = SourceSession.open(SourceSet.Single(input), context, ParseBudget(context)).orThrow()
            val codingBefore = codingDigest(original); val hashBefore = sha256Range(original.reader, ByteRange(0uL, originalBytes.size.toULong())).orThrow()
            val core = DefaultLivePhotoCore(found.backend)
            for (updateKey in listOf(false, true)) {
                val output = MemoryOutputTransaction(context, "real-replace-$updateKey")
                val run = core.replacePrimaryImageFromFrame(ReplaceRequest(SourceSet.Single(input), CoverPosition.FrameIndex(3uL), ImageEncoding(ImageFormat.Jpeg), updateKeyPosition = updateKey, output = output, context = context))
                val result = assertIs<CoreResult.Success<OperationResult>>(run, run.toString()).value
                try {
                    val after = SourceSession.open(SourceSet.Single(result.output.assets.single().readableSource!!), context, ParseBudget(context)).orThrow()
                    assertNotEquals(codingBefore, codingDigest(after))
                    assertEquals(Bytes(videoBytes), after.reader.readExactly(after.bindings.single().video!!.offset, videoBytes.size.toUInt()).orThrow())
                    val frameTime = selectFrame(original.videos.values.single(), CoverPosition.FrameIndex(3uL)).time
                    assertEquals(0, after.inspection.keyPhoto.position!!.compareTo(if (updateKey) frameTime else Time.Zero))
                    assertEquals(64u, after.inspection.media.first().width); assertEquals(64u, after.inspection.media.first().height)
                    assertEquals(GuaranteeOutcome.Changed, result.preservation.records.single { it.guarantee == Guarantee.ImageDataPreserving }.outcome)
                    assertTrue(result.execution.none { it.transcoded }); assertTrue(after.gainMaps.isEmpty())
                    // Probe default primary image with the real decoder after rebuilding the live carrier.
                    val decoded = core.probe(ProbeRequest(ResourceRef(SourceSet.Single(result.output.assets.single().readableSource!!)), true, context)).orThrow()
                    assertTrue(decoded.issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                } finally { result.output.assets.forEach { it.readableSource?.close() } }
            }
            assertEquals(hashBefore, sha256Range(original.reader, ByteRange(0uL, originalBytes.size.toULong())).orThrow())
            input.close()
        } finally { Files.deleteIfExists(file); Files.delete(directory) }
    }
}
