package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.MemoryBinarySource
import livephoto.core.memory.MemoryOutputTransaction
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.*

class FfmpegTrimIntegrationTest {
    private val context = Context(Limits(128_000_000uL, 128_000_000uL))
    private fun discovery(): BackendDiscovery {
        val found = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(found.ffmpegPath)
        assumeTrue("No existing FFmpeg; real lossless trim was not run", found.ffmpegPath != null)
        return found
    }
    @Test fun realExactNonSyncTrimRequiresExplicitAuthorizationAndFullyDecodes(): Unit = runImmediate {
        val found = discovery(); val directory = Files.createTempDirectory("livephoto-exact-trim-"); val file = directory.resolve("source.mp4")
        try {
            val generated = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror",
                "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "12", "-vf", "setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709",
                "-c:v", "libx264", "-preset", "ultrafast", "-bf", "0", "-g", "3", "-sc_threshold", "0", "-pix_fmt", "yuv420p", "-use_editlist", "0",
                "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", file.toString()), 60_000L)
            assertEquals(0, generated.code, generated.output)
            val input = FileBinarySource(file)
            try {
                val reader = BinaryReader(input, context); val range = ByteRange(0uL, reader.identity().orThrow().size); val hash = sha256Range(reader, range).orThrow()
                val before = BmffVideoProbe(reader).probe(range).orThrow(); assertFalse(before.tracks.single().samples[1].isSync)
                val core = DefaultLivePhotoCore(found.backend)
                val spec = TrimSpec(TimeRange(Time(40, 1000u), Time(280, 1000u)), TrimMode.Exact)
                val forbidden = MemoryOutputTransaction(context, "exact-forbid")
                assertEquals("EXACT_TRIM_UNAVAILABLE", assertIs<CoreResult.Failure>(core.trim(TrimRequest(ResourceRef(SourceSet.Single(input)), spec, output = forbidden, context = context))).error.code.value)
                assertTrue(forbidden.committedAssets().isEmpty())
                val output = MemoryOutputTransaction(context, "exact-encoded")
                val request = TrimRequest(ResourceRef(SourceSet.Single(input)), spec, MutationPolicy(transcode = TranscodePolicy.Explicit), output, context)
                core.plan(request).orThrow(); assertEquals(TransactionState.Open, output.query().orThrow().state)
                val run = core.trim(request)
                val result = assertIs<CoreResult.Success<TrimResult>>(run, run.toString()).value
                try {
                    assertTrue(result.wasTranscoded); assertFalse(result.wasBitstreamPreserved); assertFalse(result.wasRemuxed); assertFalse(result.retainedHiddenContent)
                    assertEquals(0, result.actualStart.compareTo(spec.range.start)); assertEquals(0, result.actualEnd.compareTo(spec.range.end))
                    assertEquals(GuaranteeOutcome.Changed, result.operation.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }.outcome)
                    val asset = result.operation.output.assets.single(); val out = BinaryReader(asset.readableSource!!, context)
                    val after = BmffVideoProbe(out).probe(ByteRange(0uL, out.identity().orThrow().size)).orThrow()
                    assertEquals(6, after.tracks.single().samples.size); assertTrue(after.tracks.single().samples.first().isSync)
                    assertTrue(core.probe(ProbeRequest(ResourceRef(SourceSet.Single(asset.readableSource)), true, context)).orThrow().issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                } finally { result.operation.output.assets.forEach { it.readableSource?.close() } }
                val image = MemoryBinarySource(Bytes(GoogleFixtures.jpeg()), SourceId("exact-composite-image"))
                val live = MemoryBinarySource(Bytes(GoogleFixtures.v1Photo(Files.readAllBytes(file), timestamp = "160000")), SourceId("exact-composite-live"))
                for (target in listOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2)) for (convert in listOf(false, true)) for (replacement in listOf(false, true)) {
                    val tx = MemoryOutputTransaction(context, "real-exact-composite-$target-$convert-$replacement")
                    val edits = EditSpec(trim = spec, keyPosition = if (convert) null else CoverPosition.FrameIndex(4uL),
                        replacementFrame = if (replacement) CoverPosition.FrameIndex(10uL) else null)
                    val policy = MutationPolicy(transcode = TranscodePolicy.Explicit)
                    val created = if (convert) core.convert(ConvertRequest(SourceSet.Single(live), ProtocolSelector(target), edits = edits, sameTarget = SameTargetPolicy.Normalize, policy = policy, output = tx, context = context))
                        else core.create(CreateRequest(image, input, ProtocolSelector(target), edits = edits, policy = policy, output = tx, context = context))
                    val composite = assertIs<CoreResult.Success<OperationResult>>(created, created.toString()).value
                    try {
                        assertEquals(0, composite.keyPhoto!!.position!!.compareTo(Time(120, 1000u)))
                        assertEquals(GuaranteeOutcome.Changed, composite.preservation.records.single { it.guarantee == Guarantee.BitstreamPreserving }.outcome)
                        assertEquals(GuaranteeOutcome.Unknown, composite.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }.outcome)
                        if (replacement) assertEquals(GuaranteeOutcome.Changed, composite.preservation.records.single { it.guarantee == Guarantee.ImageDataPreserving }.outcome)
                        val source = SourceSet.Single(composite.output.assets.single().readableSource!!)
                        assertEquals(Verdict.Valid, core.validate(ValidationRequest(source, layers = listOf(Layer.Structure, Layer.Protocol), context = context)).orThrow().verdict)
                        assertTrue(core.probe(ProbeRequest(ResourceRef(source, videoId(target)), true, context)).orThrow().issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                    } finally { composite.output.assets.forEach { it.readableSource?.close() } }
                }
                assertEquals(hash, sha256Range(reader, range).orThrow())
            } finally { input.close() }
        } finally { Files.deleteIfExists(file); Files.delete(directory) }
    }
    @Test fun realClosedGopTrimKeepsSelectedSamplesWithoutEncodingOrHiddenContent(): Unit = runImmediate {
        val found = discovery(); val directory = Files.createTempDirectory("livephoto-trim-fixture-"); val file = directory.resolve("closed gop.mp4")
        try {
            val process = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror",
                "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "12", "-c:v", "libx264", "-preset", "ultrafast", "-bf", "0", "-g", "3", "-sc_threshold", "0", "-pix_fmt", "yuv420p",
                "-map_metadata", "-1", "-map_chapters", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", file.toString()), 60_000L)
            assertEquals(0, process.code, process.output)
            val input = FileBinarySource(file)
            try {
                val reader = BinaryReader(input, context); val range = ByteRange(0uL, reader.identity().orThrow().size); val beforeHash = sha256Range(reader, range).orThrow()
                val before = BmffVideoProbe(reader).probe(range).orThrow(); assertEquals(12, before.tracks.single().samples.size)
                val core = DefaultLivePhotoCore(found.backend)
                for ((mode, start, end, actualStart, actualEnd) in listOf(
                    listOf(TrimMode.LosslessOnly, 120L, 280L, 120L, 280L),
                    listOf(TrimMode.LosslessPreferred, 130L, 270L, 120L, 280L),
                    listOf(TrimMode.Exact, 120L, 280L, 120L, 280L))) {
                    val spec = TrimSpec(TimeRange(Time(start as Long, 1000u), Time(end as Long, 1000u)), mode as TrimMode)
                    val output = MemoryOutputTransaction(context, "real-trim-$mode")
                    val run = core.trim(TrimRequest(ResourceRef(SourceSet.Single(input)), spec, policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = output, context = context))
                    val result = assertIs<CoreResult.Success<TrimResult>>(run, run.toString()).value
                    try {
                        assertEquals(0, result.actualStart.compareTo(Time(actualStart as Long, 1000u))); assertEquals(0, result.actualEnd.compareTo(Time(actualEnd as Long, 1000u)))
                        assertFalse(result.wasTranscoded); assertFalse(result.retainedHiddenContent); assertTrue(result.wasBitstreamPreserved)
                        val asset = result.operation.output.assets.single(); val actualReader = BinaryReader(asset.readableSource!!, context)
                        val actual = BmffVideoProbe(actualReader).probe(ByteRange(0uL, actualReader.identity().orThrow().size)).orThrow()
                        assertEquals(4, actual.tracks.single().samples.size)
                        for ((left, right) in before.tracks.single().samples.subList(3, 7).zip(actual.tracks.single().samples))
                            assertEquals(sha256Range(reader, left.range).orThrow(), sha256Range(actualReader, right.range).orThrow())
                        // Real full decoding of the shortened output, distinct from structural/sample verification.
                        val decoded = core.probe(ProbeRequest(ResourceRef(SourceSet.Single(asset.readableSource)), true, context)).orThrow()
                        assertTrue(decoded.issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                    } finally { result.operation.output.assets.forEach { it.readableSource?.close() } }
                }
                val rejected = MemoryOutputTransaction(context, "real-trim-not-idr")
                val result = core.trim(TrimRequest(ResourceRef(SourceSet.Single(input)), TrimSpec(TimeRange(Time(130, 1000u), Time(270, 1000u)), TrimMode.LosslessOnly), output = rejected, context = context))
                assertEquals("LOSSLESS_TRIM_UNAVAILABLE", assertIs<CoreResult.Failure>(result).error.code.value); assertTrue(rejected.committedAssets().isEmpty())
                assertEquals(beforeHash, sha256Range(reader, range).orThrow())
            } finally { input.close() }
        } finally { Files.deleteIfExists(file); Files.delete(directory) }
    }
}
