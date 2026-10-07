package livephoto.core.jvm

import livephoto.core.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Optional real decoder test: absence is a reported skip, never a successful decode claim. */
class FfmpegIntegrationTest {
    @Test fun existingFfmpegDecodesReferenceJpegAndMp4(): Unit = runImmediate {
        val selected = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(selected.ffmpegPath, "Required existing FFmpeg was not discovered")
        assumeTrue("No existing FFmpeg; real decoder integration was not run", selected.ffmpegPath != null)
        val reference = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .map { it.resolve("reference") }.firstOrNull { Files.isRegularFile(it.resolve("video.mp4")) }
        assertNotNull(reference, "Real decoder verification needs reference assets")
        val context = Context(Limits(128_000_000uL, 128_000_000uL))
        for (name in listOf("video.jpg", "video.mp4")) {
            val source = FileBinarySource(reference.resolve(name))
            try {
                val result = DefaultLivePhotoCore(selected.backend).probe(ProbeRequest(ResourceRef(SourceSet.Single(source)), true, context))
                val facts = assertIs<CoreResult.Success<MediaFacts>>(result).value
                assertEquals(Coverage.Partial, facts.coverage)
                assertTrue(facts.issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
            } finally { source.close() }
        }
    }
}
