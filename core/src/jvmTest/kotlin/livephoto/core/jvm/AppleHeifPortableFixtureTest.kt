package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.apple.*
import livephoto.core.binary.*
import livephoto.core.memory.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

/** Independently framed HEIF/Exif and MOV, not camera samples or decode evidence. */
class AppleHeifPortableFixtureTest {
    @Test fun exportOnlyAfterPairAssociationPhysicalCidAndWholeAssetProof(): Unit = runImmediate {
        val context = Context(Limits(2_000_000uL, 2_000_000uL))
        val core = DefaultLivePhotoCore()
        val movie = Bytes(AppleFixtures.movie(payload = 0, timescale = 25u, delay = 30u))
        val fixtures = linkedMapOf("motion.mov" to movie,
            "other.mov" to Bytes(AppleFixtures.movie("11112233-4455-6677-8899-aabbccddeeff")),
            "primary-mdat.heic" to Bytes(AppleHeifFixtures.image()),
            "primary-idat.heic" to Bytes(AppleHeifFixtures.image(idat = true)))
        for (name in listOf("primary-mdat.heic", "primary-idat.heic")) {
            val image = fixtures.getValue(name)
            val input = SourceSet.Pair(MemoryBinarySource(image, SourceId(name)), MemoryBinarySource(movie, SourceId("motion.mov")))
            val inspection = core.inspect(ReadRequest(input, context)).orThrow()
            assertEquals(Disposition.Live, inspection.detection.disposition)
            assertEquals(true, inspection.pairing?.matches); assertEquals(Time(30, 25u), inspection.keyPhoto.position)
            assertEquals(1, inspection.layout.relationships.count { it.kind == RelationshipKind.Describes })
            assertEquals(Verdict.Valid, core.validateProtocol(ValidationRequest(input, context = context)).orThrow().verdict)
            val result = core.extract(ExtractRequest(input, emptyList(), output = MemoryOutputTransaction(context, "portable-$name"), context = context)).orThrow()
            try {
                assertEquals(listOf(AssetRole.PrimaryImage, AssetRole.MotionVideo), result.output.assets.map { it.role })
                for ((index, expected) in listOf(image, movie).withIndex()) {
                    val reader = BinaryReader(result.output.assets[index].readableSource!!, context)
                    assertEquals(expected, reader.readExactly(0uL, expected.size.toUInt()).orThrow())
                }
            } finally { result.output.assets.forEach { it.readableSource?.close() } }
        }
        val badPair = SourceSet.Pair(MemoryBinarySource(fixtures.getValue("primary-mdat.heic"), SourceId("negative-image")),
            MemoryBinarySource(fixtures.getValue("other.mov"), SourceId("negative-movie")))
        assertEquals("INVALID_PAIR_IDENTIFIER", assertIs<CoreResult.Failure>(core.inspect(ReadRequest(badPair, context))).error.code.value)
        val directory = Path.of("build", "portable-apple-heif-fixtures")
        Files.createDirectories(directory)
        val manifest = StringBuilder("scope=synthetic-apple-heif-pair-framing-not-decoder-or-device-proof\nrunId=${UUID.randomUUID()}\n")
        for ((name, bytes) in fixtures) {
            Files.write(directory.resolve(name), bytes.toByteArray())
            manifest.append("$name=${Sha256().also { it.update(bytes) }.finish().value}\n")
        }
        Files.writeString(directory.resolve("manifest.txt"), manifest.toString())
    }
}
