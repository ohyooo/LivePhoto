package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.memory.*
import livephoto.core.samsung.SamsungFixtures
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Actual streamcopy and full decode of synthetic AVC, not camera or device compatibility. */
class SamsungRemuxRepairIntegrationTest {
    @Test fun realMovCarrierIsCorrectedWithoutEncodingAndExportsVerifiedPortableFixture(): Unit = runImmediate {
        val found = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(found.ffmpegPath)
        assumeTrue("No existing FFmpeg; real remux was not run", found.ffmpegPath != null)
        val context = Context(Limits(128_000_000uL, 128_000_000uL))
        val directory = Files.createTempDirectory("livephoto-samsung-remux-")
        val mp4 = directory.resolve("synthetic source.mp4")
        val policy = MutationPolicy(preservation = PreservationPolicy.Strict,
            requiredGuarantees = listOf(Guarantee.BitstreamPreserving, Guarantee.MetadataPreserving))
        try {
            val prepared = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror",
                "-f", "lavfi", "-i", "color=c=black:s=16x16:r=25", "-frames:v", "4", "-c:v", "libx264", "-preset", "ultrafast",
                "-bf", "0", "-g", "2", "-pix_fmt", "yuv420p", "-map_metadata", "-1", "-map_chapters", "-1", "-metadata:s:v", "encoder=",
                "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", mp4.toString()), 60_000L)
            assertEquals(0, prepared.code, prepared.output); assertFalse(prepared.ioFailed)
            val core = DefaultLivePhotoCore(found.backend)
            val file = FileBinarySource(mp4)
            try {
                val mov = core.remux(RemuxRequest(ResourceRef(SourceSet.Single(file)), VideoContainer.Mov, policy,
                    MemoryOutputTransaction(context, "fixture-mov"), context)).orThrow()
                try {
                    val movie = mov.output.assets.single().readableSource!!
                    val movieBytes = BinaryReader(movie, context).readExactly(0uL, movie.size().orThrow().toUInt()).orThrow().toByteArray()
                    val carrierBytes = SamsungFixtures.photo(movieBytes, xmp = true, timestamp = "40000").bytes
                    val carrier = MemoryBinarySource(Bytes(carrierBytes), SourceId("synthetic-sef-mov"))
                    val hash = sha256Range(BinaryReader(carrier, context), ByteRange(0uL, carrier.size().orThrow())).orThrow()
                    val tx = MemoryOutputTransaction(context, "real-repair")
                    val request = RepairRequest(SourceSet.Single(carrier), RepairMode.ExplicitRemux,
                        allowedIssueCodes = listOf(IssueCode("UNSUPPORTED_CONTAINER")), dryRun = false, policy = policy, output = tx, context = context)
                    val repaired = core.repair(request).orThrow()
                    val operation = assertNotNull(repaired.operation)
                    try {
                        assertEquals(1, operation.output.assets.size)
                        assertTrue(operation.execution.any { it.stage == Stage.Remux && it.remuxed && !it.transcoded })
                        assertTrue(operation.execution.none { it.transcoded })
                        assertTrue(repaired.issuesAfter.none { it.code.value == "UNSUPPORTED_CONTAINER" && it.layer == Layer.Protocol })
                        val output = operation.output.assets.single().readableSource!!
                        val raw = core.extract(ExtractRequest(SourceSet.Single(output), emptyList(),
                            output = MemoryOutputTransaction(context, "real-repaired-raw"), context = context)).orThrow()
                        try {
                            val video = raw.output.assets.single().readableSource!!
                            val left = BinaryReader(movie, context); val right = BinaryReader(video, context)
                            val after = BmffVideoProbe(right).probe(ByteRange(0uL, video.size().orThrow())).orThrow()
                            assertEquals(VideoContainer.Mp4, after.container)
                            RemuxVerification.verify(left, BmffVideoProbe(left).probe(ByteRange(0uL, movie.size().orThrow())).orThrow(), right, after)
                            assertTrue(core.probe(ProbeRequest(ResourceRef(SourceSet.Single(video)), true, context)).orThrow().issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                        } finally { raw.output.assets.forEach { it.readableSource?.close() } }
                        assertEquals(hash, sha256Range(BinaryReader(carrier, context), ByteRange(0uL, carrier.size().orThrow())).orThrow())
                        val again = DefaultLivePhotoCore().repair(request.copy(input = SourceSet.Single(output),
                            output = MemoryOutputTransaction(context, "real-repair-noop"))).orThrow()
                        assertTrue(again.proposedChanges.isEmpty()); assertNull(again.operation)
                        val reports = Path.of("build/reports/samsung-remux-fixtures")
                        Files.createDirectories(reports)
                        Files.write(reports.resolve("carrier.jpg"), carrierBytes)
                        Files.writeString(reports.resolve("manifest.txt"), "scope=synthetic-real-avc-streamcopy-not-device-proof\nrun=${UUID.randomUUID()}\ncarrier=${hash.value}\nkey=40000\n")
                    } finally { operation.output.assets.forEach { it.readableSource?.close() } }
                } finally { mov.output.assets.forEach { it.readableSource?.close() } }
            } finally { file.close() }
        } finally { Files.deleteIfExists(mp4); Files.delete(directory) }
    }
}
