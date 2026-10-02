package livephoto.core.google

import livephoto.core.*
import livephoto.core.jvm.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Local fixture integration tests. Neither byte identity to another writer nor device certification is implied. */
class UserReferenceCreationTest {
    private val context = Context(Limits(128_000_000uL, 128_000_000uL))
    private fun <T> value(result: CoreResult<T>): T = when (result) {
        is CoreResult.Success -> result.value
        is CoreResult.Failure -> fail("${result.error.code.value}: ${result.error.message}")
    }
    private fun fixtures(): Path {
        val root = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .map { it.resolve("reference") }.firstOrNull { Files.isRegularFile(it.resolve("video.jpg")) && Files.isRegularFile(it.resolve("video.mp4")) }
        if (System.getenv("LIVEPHOTO_REQUIRE_REFERENCE") == "true") assertNotNull(root, "Required CI reference fixtures are missing")
        else assumeTrue("Reference files absent; this test is not a device compatibility claim", root != null)
        return requireNotNull(root)
    }
    private fun digest(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun jpegAndMp4CreateBothGoogleProfilesAndExtractOriginalVideoExactly() {
        val reference = fixtures()
        val paths = listOf("video.jpg", "video.mp4", "livephoto.jpg").map(reference::resolve).filter(Files::exists)
        val hashes = paths.associateWith(::digest)
        val temporary = Files.createTempDirectory("livephoto-reference-create-")
        try { runImmediate {
            val core = DefaultLivePhotoCore()
            for ((index, protocol) in listOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2).withIndex()) {
                val image = FileBinarySource(reference.resolve("video.jpg"))
                val video = FileBinarySource(reference.resolve("video.mp4"))
                val output = DirectoryOutputTransaction(temporary.resolve("create-$index"), context)
                val created = value(core.create(CreateRequest(image, video, ProtocolSelector(protocol), output = output, context = context)))
                val live = SourceSet.Single(created.output.assets.single().readableSource!!)
                assertEquals(protocol, value(core.detect(ReadRequest(live, context))).primaryProtocol?.protocol)
                val validation = value(core.validate(ValidationRequest(live, layers = listOf(Layer.Structure, Layer.Protocol), context = context)))
                assertEquals(Coverage.Complete, validation.coverage)
                assertNotEquals(Verdict.Invalid, validation.verdict)
                val extracted = DirectoryOutputTransaction(temporary.resolve("extract-$index"), context)
                val result = value(core.extract(ExtractRequest(live, emptyList(), output = extracted, context = context)))
                assertContentEquals(Files.readAllBytes(reference.resolve("video.mp4")), Files.readAllBytes(extracted.assetPath(result.output.assets.single().id)))
                assertTrue(created.execution.none { it.transcoded })
                created.output.assets.forEach { it.readableSource?.close() }; result.output.assets.forEach { it.readableSource?.close() }
                image.close(); video.close()
            }
        } } finally { Files.walk(temporary).use { pathsToDelete -> pathsToDelete.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
        for ((path, hash) in hashes) assertEquals(hash, digest(path), "Reference input changed")
    }

    @Test fun videoCannotBeSilentlyUsedAsThePrimaryImage() {
        val reference = fixtures()
        val temporary = Files.createTempDirectory("livephoto-invalid-image-")
        try { runImmediate {
            val output = DirectoryOutputTransaction(temporary.resolve("must-not-publish"), context)
            val image = FileBinarySource(reference.resolve("video.mp4"))
            val video = FileBinarySource(reference.resolve("video.mp4"))
            assertIs<CoreResult.Failure>(DefaultLivePhotoCore().create(CreateRequest(image, video, ProtocolSelector(ProtocolIds.GoogleV2), output = output, context = context)))
            assertFalse(Files.exists(output.root))
            image.close(); video.close()
        } } finally { Files.delete(temporary) }
    }
}
