package livephoto.core.media

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.memory.*
import livephoto.core.samsung.SamsungFixtures
import livephoto.core.vivo.VivoFixtures
import livephoto.core.huawei.HuaweiFixtures
import livephoto.core.compatibility.*
import kotlin.test.*

/** Actual streamcopy and full decode of synthetic AVC, not camera or device compatibility. */
class SamsungRemuxRepairIntegrationTest {
    @Test fun realMovProfilesAreCorrectedWithoutEncodingAndExportVerifiedPortableFixtures(): Unit = runMediaTest { host ->
        val context = Context(Limits(128_000_000uL, 128_000_000uL))
        val mp4 = host.path("synthetic source.mp4")
        val policy = MutationPolicy(preservation = PreservationPolicy.Strict,
            requiredGuarantees = listOf(Guarantee.BitstreamPreserving, Guarantee.MetadataPreserving))
        val prepared = host.execute(listOf(host.executable, "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror",
            "-f", "lavfi", "-i", "color=c=black:s=16x16:r=25", "-frames:v", "4", "-c:v", "libx264", "-preset", "ultrafast",
            "-bf", "0", "-g", "2", "-pix_fmt", "yuv420p", "-map_metadata", "-1", "-map_chapters", "-1", "-metadata:s:v", "encoder=",
            "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", mp4.toString()), 60_000L)
        assertEquals(0, prepared.code, prepared.output); assertFalse(prepared.ioFailed)
        val core = DefaultLivePhotoCore(host.backend)
        val file = host.source(mp4)
        try {
            val mov = core.remux(RemuxRequest(ResourceRef(SourceSet.Single(file)), VideoContainer.Mov, policy,
                MemoryOutputTransaction(context, "fixture-mov"), context)).orThrow()
            try {
                val movie = mov.output.assets.single().readableSource!!
                val movieBytes = BinaryReader(movie, context).readExactly(0uL, movie.size().orThrow().toUInt()).orThrow().toByteArray()
                for (profile in listOf("samsung", "vivo", "huawei")) {
                    val carrierBytes = if (profile == "samsung") SamsungFixtures.photo(movieBytes, xmp = true, timestamp = "40000").bytes
                        else if (profile == "vivo") VivoFixtures.photo(movieBytes, timestamp = "40000").bytes
                        else HuaweiFixtures.photo(movieBytes, prefix = "v6_f09", history = "01:0002").bytes
                    val carrier = MemoryBinarySource(Bytes(carrierBytes), SourceId("synthetic-$profile-mov"))
                    val hash = sha256Range(BinaryReader(carrier, context), ByteRange(0uL, carrier.size().orThrow())).orThrow()
                    val tx = MemoryOutputTransaction(context, "real-repair-$profile")
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
                        if (profile == "huawei") {
                            val resultSize = output.size().orThrow()
                            assertEquals(Bytes(carrierBytes.copyOfRange(carrierBytes.size - 60, carrierBytes.size - 20)),
                                BinaryReader(output, context).readExactly(resultSize - 60uL, 40u).orThrow())
                            val key = core.getKeyPhotoPosition(ReadRequest(SourceSet.Single(output), context)).orThrow()
                            assertNull(key.position); assertEquals(KeySource.Unknown, key.source)
                            assertTrue(key.rawFields.all { it.unit == "unknown" })
                        }
                        val raw = core.extract(ExtractRequest(SourceSet.Single(output), emptyList(),
                            output = MemoryOutputTransaction(context, "real-repaired-raw-$profile"), context = context)).orThrow()
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
                            output = MemoryOutputTransaction(context, "real-repair-noop-$profile"))).orThrow()
                        assertTrue(again.proposedChanges.isEmpty()); assertNull(again.operation)
                        exportSyntheticFixture("$profile-remux", "carrier.jpg", carrierBytes)
                        exportSyntheticFixture("$profile-remux", "manifest.txt", "scope=synthetic-real-avc-streamcopy-not-device-proof\nrun=${fixtureRunId()}\ncarrier=${hash.value}\nkey=${if (profile == "huawei") "unknown" else "40000"}\n".encodeToByteArray())
                    } finally { operation.output.assets.forEach { it.readableSource?.close() } }
                }
            } finally { mov.output.assets.forEach { it.readableSource?.close() } }
        } finally { file.close() }
    }
}
