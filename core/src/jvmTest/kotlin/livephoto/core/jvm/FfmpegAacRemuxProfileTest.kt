package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import java.nio.file.Path
import kotlin.test.*

class FfmpegAacRemuxProfileTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
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
            val result = FfmpegRemux.run(Path.of("nonexistent-ffmpeg-preflight-must-not-execute.exe"), BackendJob(
                Operation.Remux, listOf(ResourceRef(SourceSet.Single(source))), remuxContainer = VideoContainer.Mp4,
                context = context, destination = noStaging))
            val error = assertIs<CoreResult.Failure>(result).error
            assertEquals("CAPABILITY_UNSUPPORTED", error.code.value)
            assertEquals(Stage.Plan, error.stage)
            assertEquals(before, sha256Range(reader, facts.range).orThrow())
        }
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
