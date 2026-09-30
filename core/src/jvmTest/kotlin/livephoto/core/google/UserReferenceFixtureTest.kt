package livephoto.core.google

import java.io.File
import java.security.MessageDigest
import livephoto.core.*
import livephoto.core.memory.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Local-only user generated reference. No phone/device test is performed here. */
class UserReferenceFixtureTest {
    @Test
    fun generatedReferenceHasSafeExactMovExtractionWithNonCanonicalProtocolReported(): Unit {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "reference") }
            .firstOrNull { directory -> listOf("video.mp4", "video.jpg", "livephoto.jpg").all { File(directory, it).isFile } }
        assumeTrue("Skipped: local user reference fixtures are absent; this is not a device compatibility test", root != null)
        val directory = requireNotNull(root)
        val originalVideo = File(directory, "video.mp4").readBytes()
        val cover = File(directory, "video.jpg").readBytes()
        val generated = File(directory, "livephoto.jpg").readBytes()
        assertEquals(8_121_397, originalVideo.size)
        assertEquals(293_848, cover.size)
        assertEquals(8_416_383, generated.size)
        assertEquals("d334d2c36a821dfa82c2d0f1bb09948125596bf77fd8eff7034ae6c53b739b64", sha(originalVideo))
        assertEquals("18d7fe28bff459d41fb75fe8358bb6df0f7c5164c04158e8b120789af7da76a9", sha(cover))
        assertEquals("fc4be53da351b60fa22086d9816871ab3fcec01ec9e166a72fbf82a3f6ba96cb", sha(generated))
        val expectedMov = generated.copyOfRange(294_932, 8_416_383)
        assertEquals("233e954e8b53e8b707b6e38fcdea554ef987a0bfb7d4bc8413c68a4d3d69d0e7", sha(expectedMov))
        assertFalse(originalVideo.contentEquals(expectedMov))
        assertContentEquals(cover.copyOfRange(2, cover.size), generated.copyOfRange(1086, 294_932))
        runImmediate {
            val context = Context(Limits(32_000_000uL, 32_000_000uL, maxMetadataBytes = 8_000_000uL))
            val core: LivePhotoCore = DefaultLivePhotoCore()
            val input = SourceSet.Single(MemoryBinarySource(Bytes(generated), SourceId("user-generated-reference")))
            val inspected = value(core.inspect(ReadRequest(input, context)))
            val resource = inspected.layout.resources.single { it.kind == ResourceKind.Video }
            assertEquals(ByteRange(294_932uL, 8_121_451uL), resource.extents.single().range)
            assertEquals(Time(0, 1_000_000u), inspected.keyPhoto.position)
            val protocol = value(core.validateProtocol(ValidationRequest(input,
                target = ProtocolSelector(ProtocolId("google.motionphoto.v2"), ProfileId("jpeg")), context = context)))
            assertEquals(Verdict.Invalid, protocol.verdict, "Secondary Padding=0 is outside canonical Google V2")
            val transaction = MemoryOutputTransaction(context, "reference-extract")
            val result = value(core.extract(ExtractRequest(input, listOf(resource.id), inspected.snapshot, output = transaction, context = context)))
            assertEquals(Bytes(expectedMov), transaction.committedAssets().values.single())
            assertEquals("233e954e8b53e8b707b6e38fcdea554ef987a0bfb7d4bc8413c68a4d3d69d0e7", result.output.assets.single().digest.value)
            assertTrue(core.getProtocolCapabilities(ProtocolSelector(ProtocolId("google.motionphoto.v2"))).operations.none { Verification.DeviceTested in it.verification })
        }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
