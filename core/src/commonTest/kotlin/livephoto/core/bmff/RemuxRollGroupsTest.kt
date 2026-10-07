package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class RemuxRollGroupsTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private fun description(type: String = "roll", distance: Int = 0xffff, version: Int = 1): ByteArray = GoogleFixtures.box("sgpd",
        GoogleFixtures.bytes(version, 0, 0, 0) + type.encodeToByteArray() + GoogleFixtures.u32(2u) + GoogleFixtures.u32(1u) + GoogleFixtures.bytes(distance ushr 8, distance and 255))
    private fun mapping(runs: List<Pair<UInt, UInt>> = listOf(2u to 1u)): ByteArray = GoogleFixtures.fullBox("sbgp", "roll".encodeToByteArray() + GoogleFixtures.u32(runs.size.toUInt()) +
        runs.fold(byteArrayOf()) { bytes, run -> bytes + GoogleFixtures.u32(run.first) + GoogleFixtures.u32(run.second) })
    private fun reader(groups: ByteArray): BinaryReader = BinaryReader(MemoryBinarySource(Bytes(GoogleFixtures.video(aac = true, audioGroups = groups, audioSampleCount = 2u).bytes), SourceId("roll-group-fixture")), context)
    private suspend fun facts(reader: BinaryReader) = BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()

    @Test fun knownAacRollGraphIsClassifiedWithoutDiscardingItsBytes(): Unit = runImmediate {
        for (runs in listOf(listOf(2u to 1u), listOf(1u to 0u, 1u to 1u))) {
            val reader = reader(description() + mapping(runs))
            val metadata = RemuxVerification.metadata(reader, facts(reader))
            assertEquals(2, metadata.fields.keys.count { it.endsWith("/sgpd[0]") || it.endsWith("/sbgp[0]") })
        }
    }

    @Test fun unknownOrOrphanOrInvalidRecoveryGraphsRemainRejected(): Unit = runImmediate {
        val bad = listOf(description("xxxx") + mapping(), description(distance = 0xfffe) + mapping(), description(version = 0) + mapping(),
            description(), mapping(), description() + description() + mapping(), description() + mapping(listOf(2u to 2u)),
            description() + mapping(listOf(0u to 1u)), description() + mapping(listOf(UInt.MAX_VALUE to 1u)), description() + mapping(listOf(1u to 1u)),
            description() + mapping(listOf(1u to 1u, 1u to 0u)))
        for (groups in bad) {
            val reader = reader(groups)
            val result = attempt { RemuxVerification.metadata(reader, facts(reader)) }
            assertEquals("UNSAFE_METADATA_REWRITE", assertIs<CoreResult.Failure>(result).error.code.value)
        }
    }

    @Test fun evenAValidChangedRecoveryMappingCannotPassMetadataPreservation(): Unit = runImmediate {
        val before = reader(description() + mapping(listOf(1u to 0u, 1u to 1u)))
        val after = reader(description() + mapping())
        val left = facts(before); val right = facts(after)
        RemuxVerification.verify(before, left, after, right) // Same sample bytes, order, configuration, and timeline.
        val result = attempt { RemuxVerification.verifyMetadata(RemuxVerification.metadata(before, left), RemuxVerification.metadata(after, right)) }
        assertEquals("POSTCONDITION_FAILED", assertIs<CoreResult.Failure>(result).error.code.value)
    }
}
