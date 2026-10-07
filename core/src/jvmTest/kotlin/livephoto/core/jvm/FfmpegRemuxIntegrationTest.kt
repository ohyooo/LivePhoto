package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.memory.MemoryOutputTransaction
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Real streamcopy on an explicitly generated AVC fixture; original reference assets stay untouched. */
class FfmpegRemuxIntegrationTest {
    private val context = Context(Limits(128_000_000uL, 128_000_000uL))
    private fun discovery(): BackendDiscovery {
        val found = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(found.ffmpegPath, "Required FFmpeg is absent")
        assumeTrue("No existing FFmpeg; real remux was not run", found.ffmpegPath != null)
        return found
    }
    private fun reference(): Path = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
        .map { it.resolve("reference/video.mp4") }.first { Files.isRegularFile(it) }

    @Test fun realVideoOnlyMp4MovMp4RemuxKeepsEverySampleAndSource(): Unit = runImmediate {
        val found = discovery()
        val directory = Files.createTempDirectory("livephoto-remux-fixture-")
        val fixture = directory.resolve("video only.mp4")
        try {
            // Deliberate synthetic fixture preparation, not an operation on the user's original asset:
            // explicitly encode four small AVC frames, with no audio/color/container annotations.
            // This fixture generation is separate from the subsequent policy-forbidden-encoding remux.
            val prepared = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror",
                "-f", "lavfi", "-i", "color=c=black:s=16x16:r=25", "-frames:v", "4", "-c:v", "libx264", "-preset", "ultrafast",
                "-bf", "0", "-g", "2", "-pix_fmt", "yuv420p",
                "-map_metadata", "-1", "-map_chapters", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", fixture.toString()), 60_000L)
            assertEquals(0, prepared.code, prepared.output)
            assertFalse(prepared.ioFailed)
            val input = FileBinarySource(fixture)
            try {
                val before = sha256Range(BinaryReader(input, context), ByteRange(0uL, input.size().orThrow())).orThrow()
                val core = DefaultLivePhotoCore(found.backend)
                val policy = MutationPolicy(preservation = PreservationPolicy.Strict, requiredGuarantees = listOf(Guarantee.BitstreamPreserving, Guarantee.MetadataPreserving))
                val run = core.remux(RemuxRequest(ResourceRef(SourceSet.Single(input)), VideoContainer.Mov, policy, MemoryOutputTransaction(context, "real-mov"), context))
                val first = assertIs<CoreResult.Success<OperationResult>>(run, run.toString()).value
                try {
                    assertEquals(VideoContainer.Mov, first.output.assets.single().videoContainer)
                    assertTrue(first.execution.none { it.transcoded })
                    val rerun = core.remux(RemuxRequest(ResourceRef(SourceSet.Single(first.output.assets.single().readableSource!!)), VideoContainer.Mp4, policy, MemoryOutputTransaction(context, "real-mp4"), context))
                    val second = assertIs<CoreResult.Success<OperationResult>>(rerun, rerun.toString()).value
                    try {
                        assertEquals(VideoContainer.Mp4, second.output.assets.single().videoContainer)
                        val left = BinaryReader(input, context)
                        val right = BinaryReader(second.output.assets.single().readableSource!!, context)
                        RemuxVerification.verify(left, BmffVideoProbe(left).probe(ByteRange(0uL, left.identity().orThrow().size)).orThrow(),
                            right, BmffVideoProbe(right).probe(ByteRange(0uL, right.identity().orThrow().size)).orThrow())
                        assertEquals(before, sha256Range(left, ByteRange(0uL, left.identity().orThrow().size)).orThrow())
                    } finally { second.output.assets.forEach { it.readableSource?.close() } }
                } finally { first.output.assets.forEach { it.readableSource?.close() } }
            } finally { input.close() }
        } finally { Files.deleteIfExists(fixture); Files.delete(directory) }
    }

    @Test fun originalAudioBearingReferenceIsRefusedWithoutDroppingItsAudio(): Unit = runImmediate {
        val found = discovery()
        val input = FileBinarySource(reference())
        val output = MemoryOutputTransaction(context, "real-audio-gate")
        try {
            val result = DefaultLivePhotoCore(found.backend).remux(RemuxRequest(ResourceRef(SourceSet.Single(input)), VideoContainer.Mov, output = output, context = context))
            assertTrue(result is CoreResult.Failure && result.error.code.value in setOf("UNSAFE_METADATA_REWRITE", "CAPABILITY_UNSUPPORTED"))
            assertTrue(output.committedAssets().isEmpty())
        } finally { input.close() }
    }

    @Test fun realMp4AacRemuxPreservesBothTrackOrdersEverySampleAndSource(): Unit = runImmediate {
        val found = discovery()
        val directory = Files.createTempDirectory("livephoto-aac-remux-fixture-")
        val video = directory.resolve("encoded synthetic video.mp4")
        val owned = mutableListOf(video)
        suspend fun prepare(arguments: List<String>) {
            val result = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror") + arguments, 60_000L)
            assertEquals(0, result.code, result.output); assertFalse(result.ioFailed)
        }
        try {
            // Explicit synthetic fixture encodes only. The later operation policy forbids encoding.
            prepare(listOf("-f", "lavfi", "-i", "color=c=black:s=16x16:r=25", "-frames:v", "4", "-c:v", "libx264", "-preset", "ultrafast",
                "-bf", "0", "-g", "2", "-pix_fmt", "yuv420p", "-map_metadata", "-1", "-metadata:s:v", "encoder=",
                "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", video.toString()))
            for (audioFirst in listOf(false, true)) for (rate in listOf(44100, 48000)) {
                val audio = directory.resolve("encoded audio $audioFirst $rate.aac"); owned.add(audio)
                val fixture = directory.resolve("muxed $audioFirst $rate.mp4"); owned.add(fixture)
                // ADTS -> MP4 fixture assembly yields the independently supported AAC-LC ASC,
                // not an unclassified SBR/private extradata extension. No user's media is edited.
                prepare(listOf("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=$rate:duration=0.16", "-c:a", "aac", "-b:a", "96k", "-ac", if (audioFirst) "2" else "1",
                    "-flags:a", "+bitexact", "-f", "adts", audio.toString()))
                val order = if (audioFirst) listOf("-map", "1:a:0", "-map", "0:v:0", "-streamid", "0:4", "-streamid", "1:17")
                    else listOf("-map", "0:v:0", "-map", "1:a:0", "-streamid", "0:17", "-streamid", "1:4")
                prepare(listOf("-i", video.toString(), "-i", audio.toString()) + order + listOf("-c", "copy", "-bsf:a", "aac_adtstoasc", "-map_metadata", "-1",
                    "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-write_btrt", "0", "-use_stream_ids_as_track_ids", "1", fixture.toString()))
                val source = FileBinarySource(fixture)
                try {
                    val reader = BinaryReader(source, context)
                    val before = BmffVideoProbe(reader).probe(ByteRange(0uL, source.size().orThrow())).orThrow()
                    assertEquals(if (audioFirst) listOf(4u, 17u) else listOf(17u, 4u), before.tracks.map { it.trackId })
                    assertEquals(rate.toUInt(), before.tracks.single { it.handler == "soun" }.audioActualSampleRate)
                    val digest = sha256Range(reader, before.range).orThrow()
                    val tx = MemoryOutputTransaction(context, "real-aac-$audioFirst-$rate")
                    val result = DefaultLivePhotoCore(found.backend).remux(RemuxRequest(ResourceRef(SourceSet.Single(source)), VideoContainer.Mp4,
                        policy = MutationPolicy(preservation = PreservationPolicy.Strict, requiredGuarantees = listOf(Guarantee.BitstreamPreserving, Guarantee.MetadataPreserving)), output = tx, context = context))
                    val operation = assertIs<CoreResult.Success<OperationResult>>(result, result.toString()).value
                    try {
                        assertTrue(operation.execution.none { it.transcoded }); assertTrue(operation.execution.any { it.remuxed })
                        assertTrue(operation.preservation.records.filter { it.guarantee in setOf(Guarantee.BitstreamPreserving, Guarantee.MetadataPreserving) }.all { it.outcome == GuaranteeOutcome.Verified })
                        val output = BinaryReader(operation.output.assets.single().readableSource!!, context)
                        val after = BmffVideoProbe(output).probe(ByteRange(0uL, output.identity().orThrow().size)).orThrow()
                        assertEquals(2, after.tracks.size)
                        RemuxVerification.verify(reader, before, output, after)
                        RemuxVerification.verifyMetadata(RemuxVerification.metadata(reader, before), RemuxVerification.metadata(output, after))
                        assertTrue(DefaultLivePhotoCore(found.backend).probe(ProbeRequest(ResourceRef(SourceSet.Single(output.source)), true, context)).orThrow().issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                        assertEquals(digest, sha256Range(reader, before.range).orThrow())
                    } finally { operation.output.assets.forEach { it.readableSource?.close() } }
                    val refused = MemoryOutputTransaction(context, "real-aac-mov-gate-$audioFirst-$rate")
                    val unsupported = DefaultLivePhotoCore(found.backend).remux(RemuxRequest(ResourceRef(SourceSet.Single(source)), VideoContainer.Mov, output = refused, context = context))
                    assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(unsupported).error.code.value)
                    assertTrue(refused.committedAssets().isEmpty())
                } finally { source.close() }
            }
        } finally { owned.asReversed().forEach { Files.deleteIfExists(it) }; Files.delete(directory) }
    }

    @Test fun referenceVideoColorDeclarationsCannotBeSilentlyChangedAcrossContainers(): Unit = runImmediate {
        val found = discovery()
        val directory = Files.createTempDirectory("livephoto-color-remux-fixture-")
        val fixture = directory.resolve("color video.mp4")
        try {
            val prepared = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror",
                "-i", reference().toString(), "-map", "0:v:0", "-c", "copy", "-map_metadata", "-1", "-map_chapters", "-1",
                "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-write_btrt", "0", "-tag:v", "hvc1", fixture.toString()), 60_000L)
            assertEquals(0, prepared.code, prepared.output)
            val source = FileBinarySource(fixture)
            val output = MemoryOutputTransaction(context, "real-color-gate")
            try {
                val result = DefaultLivePhotoCore(found.backend).remux(RemuxRequest(ResourceRef(SourceSet.Single(source)), VideoContainer.Mov, output = output, context = context))
                val error = assertIs<CoreResult.Failure>(result).error
                assertEquals("POSTCONDITION_FAILED", error.code.value)
                assertTrue(error.location?.selector?.endsWith("/hvc1") == true, error.toString())
                assertTrue(output.committedAssets().isEmpty())
            } finally { source.close() }
        } finally { Files.deleteIfExists(fixture); Files.delete(directory) }
    }
}
