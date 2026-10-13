package livephoto.core.media

import livephoto.core.*
import kotlin.test.*

/** Real decoder test, mandatory on Native CI; never a device compatibility claim. */
class FfmpegIntegrationTest {
    @Test fun existingFfmpegDecodesReferenceJpegAndMp4(): Unit = runMediaTest { host ->
        val context = Context(Limits(128_000_000uL, 128_000_000uL))
        for (name in listOf("video.jpg", "video.mp4")) {
            val source = host.source(host.reference(name))
            try {
                val result = DefaultLivePhotoCore(host.backend).probe(ProbeRequest(ResourceRef(SourceSet.Single(source)), true, context))
                val facts = assertIs<CoreResult.Success<MediaFacts>>(result).value
                assertEquals(Coverage.Partial, facts.coverage)
                assertTrue(facts.issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
            } finally { source.close() }
        }
    }
}
