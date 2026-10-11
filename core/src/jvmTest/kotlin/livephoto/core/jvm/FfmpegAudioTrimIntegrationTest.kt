package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Actual FFmpeg packet copy, never a synthetic writer standing in for a media backend. */
class FfmpegAudioTrimIntegrationTest {
    private val context = Context(Limits(128_000_000uL, 128_000_000uL))
    private fun discovery(): BackendDiscovery {
        val found = JvmMediaBackends.discover(System.getenv("LIVEPHOTO_FFMPEG")?.let(Path::of))
        if (System.getenv("LIVEPHOTO_REQUIRE_FFMPEG") == "true") assertNotNull(found.ffmpegPath)
        assumeTrue("No existing FFmpeg; audio trim was not executed", found.ffmpegPath != null)
        return found
    }
    private suspend fun fixture(found: BackendDiscovery, twoAudio: Boolean = true, body: suspend (BinarySource, VideoStructure, BinaryReader, DefaultLivePhotoCore) -> Unit) {
        val directory = Files.createTempDirectory("livephoto-audio-trim-")
        val file = directory.resolve("video with two audio tracks.mp4")
        val videoOnly = directory.resolve("video.mp4")
        val audio = listOf(directory.resolve("mono.aac"), directory.resolve("stereo.aac"))
        fun generate(arguments: List<String>) {
            val process = ExternalProcess.run(listOf(found.ffmpegPath.toString(), "-nostdin", "-n", "-hide_banner", "-loglevel", "error", "-xerror") + arguments, 60_000L)
            assertEquals(0, process.code, process.output)
        }
        try {
            generate(listOf("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25", "-frames:v", "40",
                "-c:v", "libx264", "-preset", "ultrafast", "-bf", "0", "-g", "3", "-sc_threshold", "0", "-pix_fmt", "yuv420p",
                "-use_editlist", "0", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-flags:v", "+bitexact", "-write_btrt", "0", videoOnly.toString()))
            for ((index, path) in audio.withIndex()) generate(listOf("-f", "lavfi", "-i", "sine=frequency=${440 + index * 440}:sample_rate=${if (index == 0) 44100 else 48000}:duration=1.6",
                "-c:a", "aac", "-b:a", "64k", "-ac", (index + 1).toString(), "-flags:a", "+bitexact", "-f", "adts", path.toString()))
            generate(listOf("-i", videoOnly.toString(), "-i", audio[0].toString(), "-i", audio[1].toString(), "-map", "1:a", "-map", "0:v") + (if (twoAudio) listOf("-map", "2:a") else emptyList()) + listOf(
                "-c", "copy", "-bsf:a", "aac_adtstoasc", "-use_editlist", "0", "-map_metadata", "-1", "-metadata:s:v", "encoder=", "-fflags", "+bitexact", "-write_btrt", "0",
                "-streamid", "0:4", "-streamid", "1:17") + (if (twoAudio) listOf("-streamid", "2:29") else emptyList()) + listOf("-use_stream_ids_as_track_ids", "1", file.toString()))
            val source = FileBinarySource(file)
            try {
                val reader = BinaryReader(source, context)
                val range = ByteRange(0uL, reader.identity().orThrow().size)
                val hash = sha256Range(reader, range).orThrow()
                val video = BmffVideoProbe(reader).probe(range).orThrow()
                assertEquals(if (twoAudio) listOf(4u, 17u, 29u) else listOf(4u, 17u), video.tracks.map { it.trackId })
                assertEquals(if (twoAudio) listOf(44100u, 48000u) else listOf(44100u), video.tracks.filter { it.handler == "soun" }.map { it.timescale })
                body(source, video, reader, DefaultLivePhotoCore(found.backend))
                assertEquals(hash, sha256Range(reader, range).orThrow())
            } finally { source.close() }
        } finally { (listOf(file, videoOnly) + audio).forEach { Files.deleteIfExists(it) }; Files.delete(directory) }
    }

    @Test fun nonzeroPreferredTrimCopiesBothAudioTracksAndDisclosesTheirExactPacketRanges(): Unit = runImmediate {
        val found = discovery()
        fixture(found) { source, video, input, core ->
            val spec = TrimSpec(TimeRange(Time(130, 1000u), Time(270, 1000u)))
            val plan = planLosslessTrim(input, video, spec)
            val tx = MemoryOutputTransaction(context, "audio-trim-preferred")
            val request = TrimRequest(ResourceRef(SourceSet.Single(source)), spec, output = tx, context = context)
            core.plan(request).orThrow()
            assertEquals(TransactionState.Open, tx.query().orThrow().state)
            val run = core.trim(request)
            val result = assertIs<CoreResult.Success<TrimResult>>(run, run.toString()).value
            try {
                assertEquals(0, result.actualStart.compareTo(Time(120, 1000u)))
                assertEquals(0, result.actualEnd.compareTo(Time(280, 1000u)))
                assertEquals(plan.trackTrims, result.tracks)
                assertEquals(listOf("4", "17", "29"), result.tracks.map { it.trackId.value })
                assertFalse(result.wasTranscoded); assertTrue(result.wasRemuxed); assertTrue(result.wasBitstreamPreserved); assertFalse(result.retainedHiddenContent)
                assertTrue(result.operation.issues.any { it.code.value == "AUDIO_PACKET_BOUNDARY_GAP" })
                assertTrue(result.operation.issues.any { it.code.value == "AUDIO_RECOVERY_BOUNDARY_CHANGED" })
                assertEquals(GuaranteeOutcome.Unknown, result.operation.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }.outcome)
                val output = result.operation.output.assets.single().readableSource!!
                val reader = BinaryReader(output, context)
                val actual = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
                RemuxVerification.verify(input, plan.expected(), reader, actual)
                for (track in actual.tracks.filter { it.handler == "soun" }) {
                    assertNotNull(track.edit); assertEquals(0L, track.edit.mediaStart)
                    assertTrue(track.samples.first().presentationTime > 0L)
                }
                assertTrue(core.probe(ProbeRequest(ResourceRef(SourceSet.Single(output)), true, context)).orThrow().issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
            } finally { result.operation.output.assets.forEach { it.readableSource?.close() } }
            for ((mode, policy, code) in listOf(
                Triple(TrimMode.LosslessOnly, MutationPolicy(), "LOSSLESS_TRIM_UNAVAILABLE"),
                Triple(TrimMode.Exact, MutationPolicy(transcode = TranscodePolicy.Explicit), "EXACT_TRIM_UNAVAILABLE"),
                Triple(TrimMode.LosslessPreferred, MutationPolicy(preservation = PreservationPolicy.Strict), "PRESERVATION_REQUIREMENT_FAILED"),
            )) {
                val rejected = MemoryOutputTransaction(context, "audio-trim-reject-$mode-$code")
                val result = core.trim(request.copy(spec = spec.copy(mode = mode), policy = policy, output = rejected))
                assertEquals(code, assertIs<CoreResult.Failure>(result).error.code.value)
                assertTrue(rejected.committedAssets().isEmpty())
            }
        }
    }

    @Test fun prefixCopyPreservesRecoveryAndStrictOrdinaryMetadata(): Unit = runImmediate {
        fixture(discovery()) { source, video, input, core ->
            val spec = TrimSpec(TimeRange(Time.Zero, Time(280, 1000u)))
            val plan = planLosslessTrim(input, video, spec)
            val result = core.trim(TrimRequest(ResourceRef(SourceSet.Single(source)), spec,
                MutationPolicy(preservation = PreservationPolicy.Strict), MemoryOutputTransaction(context, "audio-prefix"), context)).orThrow()
            try {
                assertFalse(plan.audioRecoveryChanged)
                assertEquals(GuaranteeOutcome.Verified, result.operation.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }.outcome)
                assertEquals(3, result.tracks.size)
                assertFalse(result.wasTranscoded)
            } finally { result.operation.output.assets.forEach { it.readableSource?.close() } }
        }
    }

    @Test fun createAndConvertUsePrivateAudioTrimAndRebaseTheKeyWithoutDroppingTracks(): Unit = runImmediate {
        // Protocol writers retain their existing single-audio constraint; trim does not expand it.
        fixture(discovery(), twoAudio = false) { source, _, _, core ->
            val image = MemoryBinarySource(Bytes(GoogleFixtures.jpeg()), SourceId("audio-trim-image"))
            val sourceReader = BinaryReader(source, context)
            val originalVideo = sourceReader.readExactly(0uL, sourceReader.identity().orThrow().size.toUInt()).orThrow().toByteArray()
            val live = MemoryBinarySource(Bytes(GoogleFixtures.v1Photo(originalVideo, timestamp = "160000")), SourceId("audio-trim-live"))
            for (convert in listOf(false, true)) {
                val tx = MemoryOutputTransaction(context, "audio-trim-composite-$convert")
                val edits = EditSpec(trim = TrimSpec(TimeRange(Time(130, 1000u), Time(270, 1000u))),
                    keyPosition = if (convert) null else CoverPosition.FrameIndex(4uL))
                val run = if (convert) core.convert(ConvertRequest(SourceSet.Single(live), ProtocolSelector(ProtocolIds.GoogleV2), edits = edits, output = tx, context = context))
                    else core.create(CreateRequest(image, source, ProtocolSelector(ProtocolIds.Oplus), edits = edits, output = tx, context = context))
                val result = assertIs<CoreResult.Success<OperationResult>>(run, run.toString()).value
                try {
                    assertEquals(0, result.keyPhoto!!.position!!.compareTo(Time(40, 1000u)))
                    val protocol = if (convert) ProtocolIds.GoogleV2 else ProtocolIds.Oplus
                    val output = SourceSet.Single(result.output.assets.single().readableSource!!)
                    assertEquals(Verdict.Valid, core.validate(ValidationRequest(output, layers = listOf(Layer.Structure, Layer.Protocol), context = context)).orThrow().verdict)
                    val isolated = VideoResource.open(ResourceRef(output, videoId(protocol)), context)
                    assertEquals(listOf(4u, 17u), isolated.video.tracks.map { it.trackId })
                    assertTrue(core.probe(ProbeRequest(ResourceRef(output, videoId(protocol)), true, context)).orThrow().issues.any { it.code.value == "MEDIA_DECODE_COMPLETED" })
                } finally { result.output.assets.forEach { it.readableSource?.close() } }
            }
        }
    }
}
