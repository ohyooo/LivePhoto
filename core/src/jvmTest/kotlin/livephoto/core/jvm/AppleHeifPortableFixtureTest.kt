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
        for (mov in listOf(false, true)) {
            val input = AppleHeifKeyFixtures.pair(context, mov = mov, idat = mov)
            suspend fun read(source: BinarySource) = BinaryReader(source, context).readExactly(0uL, source.size().orThrow().toUInt()).orThrow()
            val primary = read(input.image); val motion = read(input.video)
            val changed = core.setKeyPhotoPosition(SetKeyRequest(input, CoverPosition.FrameIndex(1uL), MutationPolicy(preservation = PreservationPolicy.Strict),
                MemoryOutputTransaction(context, "export-heic-key-$mov"), context)).orThrow()
            try {
                assertEquals(primary, read(changed.output.assets[0].readableSource!!))
                assertEquals(0, changed.keyPhoto!!.position!!.compareTo(Time(40, 1000u)))
                assertEquals(Coverage.Partial, changed.validation.coverage)
            } finally { changed.output.assets.forEach { it.readableSource?.close() } }
            val cleaned = core.split(SplitRequest(input, output = MemoryOutputTransaction(context, "export-heic-clean-$mov"), context = context)).orThrow()
            try {
                val detected = core.detect(ReadRequest(SourceSet.Single(cleaned.output.assets[0].readableSource!!), context)).orThrow()
                assertEquals(Disposition.Unknown, detected.disposition); assertTrue(detected.matches.isEmpty())
                AppleHeifClean.validateRetired(BinaryReader(cleaned.output.assets[0].readableSource!!, context), ParseBudget(context)).orThrow()
                assertTrue(cleaned.preservation.records.any { it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
            } finally { cleaned.output.assets.forEach { it.readableSource?.close() } }
            val suffix = if (mov) "mov" else "mp4"
            fixtures["key-$suffix.heic"] = primary; fixtures["key-motion.$suffix"] = motion
            input.image.close(); input.video.close()
        }
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
