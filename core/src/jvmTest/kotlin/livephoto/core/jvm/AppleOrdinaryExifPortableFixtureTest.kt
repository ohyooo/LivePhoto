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
        val ordinaryMov = Base64.getDecoder().decode(javaClass.getResourceAsStream("/apple-media/ordinary-text.mov.base64")!!
            .use { it.readNBytes(40_000).toString(Charsets.US_ASCII).trim() })
        assertEquals("9d2be28350c0c807ac7164c80968ef75ce875fb8d6da04b2a3d89165c82aa6a4",
            Sha256().also { it.update(Bytes(ordinaryMov)) }.finish().value)
        val fixtures = linkedMapOf<String, ByteArray>(
            "ordinary-big.jpg" to AppleOrdinaryExifTest().image(Endian.Big),
            "ordinary-little.jpg" to AppleOrdinaryExifTest().image(Endian.Little),
            "private-note.jpg" to AppleFixtures.image(tag = 1),
            "motion.mp4" to WindowsEncodedFixtures.bytes("remux-main"),
            "ordinary-text.mp4" to OrdinaryMovieTextFixtures.movie(WindowsEncodedFixtures.bytes("remux-main")),
            "ordinary-text.mov" to ordinaryMov,
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
        for (container in listOf("mp4", "mov")) {
            val ordinary = MemoryBinarySource(Bytes(fixtures.getValue("ordinary-text.$container")), SourceId("ordinary-text"))
            val ordinaryResult = DefaultLivePhotoCore().create(CreateRequest(
                MemoryBinarySource(Bytes(fixtures.getValue("ordinary-big.jpg")), SourceId("ordinary-text-image")), ordinary,
                ProtocolSelector(ProtocolIds.Apple, ProfileId("jpeg-$container")), policy = MutationPolicy(preservation = PreservationPolicy.Strict),
                output = MemoryOutputTransaction(context, "ordinary-text"), context = context)).orThrow()
            assertEquals(Verdict.Valid, ordinaryResult.validation.verdict)
            assertEquals(AppleOrdinaryMovieTextTest().envelope(ordinary),
                AppleOrdinaryMovieTextTest().envelope(ordinaryResult.output.assets[1].readableSource!!))
            val clean = DefaultLivePhotoCore().split(SplitRequest(SourceSet.Pair(ordinaryResult.output.assets[0].readableSource!!,
                ordinaryResult.output.assets[1].readableSource!!), output = MemoryOutputTransaction(context, "ordinary-text-clean"), context = context)).orThrow()
            val cleanMovie = clean.output.assets.single { it.role == AssetRole.MotionVideo }.readableSource!!
            assertEquals(AppleOrdinaryMovieTextTest().envelope(ordinary), AppleOrdinaryMovieTextTest().envelope(cleanMovie))
            val repeated = DefaultLivePhotoCore().split(SplitRequest(SourceSet.Single(cleanMovie), policy = MutationPolicy(preservation = PreservationPolicy.Strict),
                output = MemoryOutputTransaction(context, "ordinary-text-repeat"), context = context)).orThrow()
            assertEquals(sha256Range(BinaryReader(cleanMovie, context), ByteRange(0uL, cleanMovie.size().orThrow())).orThrow(),
                sha256Range(BinaryReader(repeated.output.assets.single().readableSource!!, context), ByteRange(0uL, cleanMovie.size().orThrow())).orThrow())
            clean.output.assets.forEach { it.readableSource?.close() }; repeated.output.assets.forEach { it.readableSource?.close() }
            ordinaryResult.output.assets.forEach { it.readableSource?.close() }; ordinary.close()
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
