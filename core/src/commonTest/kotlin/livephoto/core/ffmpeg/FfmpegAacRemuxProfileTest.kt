package livephoto.core.ffmpeg

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class FfmpegAacRemuxProfileTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private val noRuntime = object : FfmpegRuntime {
        override suspend fun execute(arguments: List<String>, timeoutMillis: Long, context: Context): FfmpegProcessResult =
            error("Preflight must never execute FFmpeg")
        override suspend fun openFiles(source: BinarySource, context: Context, outputName: String,
            producesOutput: Boolean): CoreResult<FfmpegFiles> = error("Preflight must never allocate storage")
    }
    private fun groups(runs: List<Pair<UInt, UInt>>): ByteArray = GoogleFixtures.box("sgpd",
        GoogleFixtures.bytes(1, 0, 0, 0) + "roll".encodeToByteArray() + GoogleFixtures.u32(2u) + GoogleFixtures.u32(1u) + GoogleFixtures.bytes(255, 255)) +
        GoogleFixtures.fullBox("sbgp", "roll".encodeToByteArray() + GoogleFixtures.u32(runs.size.toUInt()) +
            runs.fold(byteArrayOf()) { bytes, run -> bytes + GoogleFixtures.u32(run.first) + GoogleFixtures.u32(run.second) })
    private fun source(groups: ByteArray = byteArrayOf(), audio: Boolean = true): BinarySource = MemoryBinarySource(
        Bytes(GoogleFixtures.video(aac = audio, audioGroups = groups, audioSampleCount = 2u).bytes), SourceId("aac-preflight"))
    private val noStaging = object : StagingArea {
        override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = error("Preflight must never create staging")
        override suspend fun openForRead(id: AssetId): CoreResult<BinarySource> = error("Preflight must never read staging")
    }

    @Test fun grouplessAndValidNoncanonicalMapsAreRefusedBeforeAnyProcessOrStaging(): Unit = runImmediate {
        for (groups in listOf(byteArrayOf(), groups(listOf(1u to 0u, 1u to 1u)))) {
            val source = source(groups)
            val reader = BinaryReader(source, context)
            val facts = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
            // Generic Core metadata proof remains available; this is an external muxer limitation only.
            RemuxVerification.metadata(reader, facts)
            val before = sha256Range(reader, facts.range).orThrow()
            val result = FfmpegRemux.run("nonexistent-ffmpeg-preflight-must-not-execute.exe", noRuntime, BackendJob(
                Operation.Remux, listOf(ResourceRef(SourceSet.Single(source))), remuxContainer = VideoContainer.Mp4,
                context = context, destination = noStaging))
            val error = assertIs<CoreResult.Failure>(result).error
            assertEquals("CAPABILITY_UNSUPPORTED", error.code.value)
            assertEquals(Stage.Plan, error.stage)
            assertEquals(before, sha256Range(reader, facts.range).orThrow())
        }
    }

    @Test fun secondAudioTrackNeedsItsOwnRecoveryGroupProof(): Unit = runImmediate {
        val reader = BinaryReader(source(groups(listOf(2u to 1u))), context)
        val facts = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
        val metadata = RemuxVerification.metadata(reader, facts)
        val second = facts.tracks.single { it.handler == "soun" }.copy(trackId = 29u)
        val two = facts.copy(tracks = facts.tracks + second)
        val firstPrefix = "/moov[0]/trak[1]/mdia[0]/minf[0]/stbl[0]"
        val secondPrefix = "/moov[0]/trak[2]/mdia[0]/minf[0]/stbl[0]"
        val fields = metadata.fields + metadata.fields.filterKeys { it.startsWith(firstPrefix) }.mapKeys { it.key.replace(firstPrefix, secondPrefix) }
        FfmpegAacRemuxProfile.validate(two, metadata.copy(fields = fields))
        for (missing in listOf("sgpd", "sbgp")) {
            val result = attemptNow { FfmpegAacRemuxProfile.validate(two, metadata.copy(fields = fields - "$secondPrefix/$missing[0]")) }
            assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(result).error.code.value)
        }
    }

    @Test fun grouplessAudioCannotReachExplicitVideoEncoderOrStaging(): Unit = runImmediate {
        val source = source()
        val reader = BinaryReader(source, context)
        val before = sha256Range(reader, ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
        val result = FfmpegTranscode.run("nonexistent-ffmpeg-transcode-preflight-must-not-execute.exe", noRuntime, BackendJob(
            Operation.Transcode, listOf(ResourceRef(SourceSet.Single(source))), videoEncoding = VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4),
            policy = MutationPolicy(transcode = TranscodePolicy.Explicit), context = context, destination = noStaging))
        val error = assertIs<CoreResult.Failure>(result).error
        assertEquals("CAPABILITY_UNSUPPORTED", error.code.value); assertEquals(Stage.Plan, error.stage)
        assertEquals(before, sha256Range(reader, ByteRange(0uL, reader.identity().orThrow().size)).orThrow())
    }

    @Test fun canonicalMapAndVideoOnlyRemainEligibleButMissingProofDoesNot(): Unit = runImmediate {
        for (audio in listOf(false, true)) {
            val reader = BinaryReader(source(groups(listOf(2u to 1u)), audio), context)
            val facts = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
            val metadata = RemuxVerification.metadata(reader, facts)
            FfmpegAacRemuxProfile.validate(facts, metadata)
            if (audio) {
                val fields = metadata.fields.filterKeys { !it.endsWith("/sgpd[0]") }
                val result = attemptNow { FfmpegAacRemuxProfile.validate(facts, metadata.copy(fields = fields)) }
                assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(result).error.code.value)
            }
        }
    }
}
