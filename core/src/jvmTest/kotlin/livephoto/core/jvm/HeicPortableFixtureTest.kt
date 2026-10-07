package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.*
import livephoto.core.heif.*
import livephoto.core.memory.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

/** Export only synthetic framing fixtures into test build outputs for packaged CLI conformance.
 * No device compatibility or decoder evidence; real HEVC decoding has its own integration test. */
class HeicPortableFixtureTest {
    @Test fun exportSyntheticFixturesOnlyAfterIndependentCoreProof(): Unit = runImmediate {
        val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
        val core = DefaultLivePhotoCore()
        val target = ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("heic"))
        fun source(bytes: Bytes, name: String) = MemoryBinarySource(bytes, SourceId(name))
        val image = Bytes(HeifFixtures.plain(idat = true, multiple = true))
        val video = Bytes(GoogleFixtures.video(aac = true).bytes)
        val tx = MemoryOutputTransaction(context, "portable-heic-fixture-create")
        core.create(CreateRequest(source(image, "fixture-image"), source(video, "fixture-video"), target,
            edits = EditSpec(keyPosition = CoverPosition.FrameIndex(0uL)), output = tx, context = context)).orThrow()
        val motion = tx.committedAssets().values.single()
        val reader = BinaryReader(source(motion, "fixture-inspection"), context)
        val roots = BmffReader(reader).readBoxes(ByteRange(0uL, motion.size.toULong())).orThrow()
        val graph = HeifItemGraphReader.read(reader, roots).orThrow()
        val extent = graph.locations.items.single { it.id != graph.primary }.extents.single().data
        val damagedXml = GoogleDirectoryWriter.heic(video.size.toULong() - 1uL, 0L, context)
        assertEquals(extent.length, damagedXml.size.toULong())
        val damaged = motion.toByteArray(); damagedXml.copyInto(damaged, extent.offset.toInt())
        val repairTx = MemoryOutputTransaction(context, "portable-heic-fixture-repair")
        core.repair(RepairRequest(SourceSet.Single(source(Bytes(damaged), "fixture-negative")), dryRun = false,
            output = repairTx, context = context)).orThrow()
        assertEquals(motion, repairTx.committedAssets().values.single())
        val directory = Path.of("build", "portable-heic-fixtures")
        Files.createDirectories(directory)
        val fixtures = linkedMapOf("primary.heic" to image, "motion.mp4" to video, "motion.heic" to motion, "damaged.heic" to Bytes(damaged))
        val manifest = StringBuilder("scope=synthetic-protocol-framing-not-decoder-or-device-proof\nrunId=${UUID.randomUUID()}\n")
        for ((name, bytes) in fixtures) {
            Files.write(directory.resolve(name), bytes.toByteArray())
            val hash = Sha256().also { it.update(bytes) }.finish().value
            manifest.append("$name=$hash\n")
        }
        // Last: consumers must check all file hashes rather than trusting stale/mixed files.
        Files.writeString(directory.resolve("manifest.txt"), manifest.toString())
    }
}
