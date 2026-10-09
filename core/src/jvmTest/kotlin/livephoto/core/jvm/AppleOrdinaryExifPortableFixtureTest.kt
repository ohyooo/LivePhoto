package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.apple.*
import livephoto.core.binary.*
import livephoto.core.memory.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.Base64
import kotlin.test.*

class AppleOrdinaryExifPortableFixtureTest {
    @Test fun exportOrdinaryExifFixturesAfterStrictBothContainerPairValidation(): Unit = runImmediate {
        val context = Context(Limits(16_000_000uL, 16_000_000uL, maxMetadataBytes = 8_000_000uL))
        val mov = Base64.getDecoder().decode(javaClass.getResourceAsStream("/windows-media/frame-high.mov.base64")!!
            .use { it.readNBytes(40_000).toString(Charsets.US_ASCII).trim() })
        assertEquals("5929ddf215c236013abb1dff65c2c3537a878fe4c2ad40fa0af7e46ada2efe19",
            Sha256().also { it.update(Bytes(mov)) }.finish().value)
        val fixtures = linkedMapOf<String, ByteArray>(
            "ordinary-big.jpg" to AppleOrdinaryExifTest().image(Endian.Big),
            "ordinary-little.jpg" to AppleOrdinaryExifTest().image(Endian.Little),
            "private-note.jpg" to AppleFixtures.image(tag = 1),
            "motion.mp4" to WindowsEncodedFixtures.bytes("remux-main"),
            "motion.mov" to mov)
        for (endian in listOf("big", "little")) for (container in listOf("mp4", "mov")) {
            val image = MemoryBinarySource(Bytes(fixtures.getValue("ordinary-$endian.jpg")), SourceId("ordinary-$endian"))
            val video = MemoryBinarySource(Bytes(fixtures.getValue("motion.$container")), SourceId("ordinary-$container"))
            val result = DefaultLivePhotoCore().create(CreateRequest(image, video,
                ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-$container")),
                policy = MutationPolicy(preservation = PreservationPolicy.Strict),
                output = MemoryOutputTransaction(context, "ordinary-$endian-$container"), context = context)).orThrow()
            assertEquals(Verdict.Valid, result.validation.verdict)
            assertTrue(result.execution.none { it.remuxed || it.transcoded })
            result.output.assets.forEach { it.readableSource?.close() }; image.close(); video.close()
        }
        val directory = Path.of("build", "portable-apple-ordinary-exif-fixtures")
        Files.createDirectories(directory)
        val manifest = StringBuilder("scope=synthetic-ordinary-exif-pair-not-device-proof\nrunId=${UUID.randomUUID()}\n")
        for ((name, bytes) in fixtures) {
            Files.write(directory.resolve(name), bytes)
            manifest.append("$name=${Sha256().also { it.update(Bytes(bytes)) }.finish().value}\n")
        }
        Files.writeString(directory.resolve("manifest.txt"), manifest.toString())
    }
}
