package livephoto.core

import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

/** Synthetic structural writer evidence, not OS/device playback evidence. */
class LosslessTrimWriterTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private fun reader(bytes: ByteArray, context: Context = this.context) = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("trim-writer")), context)
    private suspend fun check(bytes: ByteArray, spec: TrimSpec) {
        val input = reader(bytes); val sink = TestSink(maxChunk = 7)
        val plan = LosslessTrimWriter.write(input, spec, sink).orThrow()
        val output = reader(sink.written.toByteArray())
        val after = BmffVideoProbe(output).probe(ByteRange(0uL, sink.written.size.toULong())).orThrow()
        verifyTrimDurationHeaders(output, after, plan)
        RemuxVerification.verify(input, plan.expected(), output, after)
        RemuxVerification.verifyMetadata(RemuxVerification.metadata(input, plan.source, true), RemuxVerification.metadata(output, after, true))
        assertEquals(plan.samples.size, after.tracks.single().samples.size)
        assertEquals(0uL, after.tracks.single().samples.first().decodeTime)
        assertNull(after.tracks.single().edit)
        assertContentEquals(bytes, input.readBuffer(0uL, bytes.size.toUInt()).orThrow().toByteArray())
        assertFalse(sink.closed)
    }
    @Test fun shortenedPrefixRebuildsHeadersAndRetainsOnlySelectedBytes(): Unit = runImmediate {
        for (co64 in listOf(false, true)) check(GoogleFixtures.video(co64 = co64).bytes, TrimSpec(TimeRange(Time.Zero, Time(40, 1000u)), TrimMode.LosslessOnly))
    }
    @Test fun nonzeroIdrStartIsRebasedAndIdentityEditIsRemoved(): Unit = runImmediate {
        val edit = GoogleFixtures.fullBox("elst", GoogleFixtures.u32(1u) + GoogleFixtures.u32(80u) + GoogleFixtures.u32(0u) + byteArrayOf(0, 1, 0, 0))
        val fixture = GoogleFixtures.video(editList = edit)
        val bytes = fixture.bytes.copyOf()
        bytes[fixture.sampleOffset.toInt() + 6 + 4] = 0x65
        val syncType = (4 until bytes.size - 4).single { bytes.copyOfRange(it, it + 4).contentEquals("stss".encodeToByteArray()) }
        GoogleFixtures.u32(2u).copyInto(bytes, syncType + 12) // one sync entry: second sample
        check(bytes, TrimSpec(TimeRange(Time(40, 1000u), Time(80, 1000u)), TrimMode.Exact))
    }
    @Test fun vfrZeroCompositionSurvivesSelection(): Unit = runImmediate {
        val composition = GoogleFixtures.fullBox("ctts", GoogleFixtures.u32(1u) + GoogleFixtures.u32(2u) + GoogleFixtures.u32(0u))
        check(GoogleFixtures.video(composition = composition, sampleDurations = 20u to 60u).bytes, TrimSpec(TimeRange(Time.Zero, Time(20, 1000u)), TrimMode.LosslessOnly))
    }
    @Test fun unknownMetadataExtraTracksAndUnsafeBoundariesNeverWrite(): Unit = runImmediate {
        val normal = GoogleFixtures.video().bytes
        for ((bytes, spec, code) in listOf(
            Triple(normal + GoogleFixtures.box("uuid", ByteArray(16)), TrimSpec(TimeRange(Time.Zero, Time(40, 1000u))), "UNSAFE_METADATA_REWRITE"),
            Triple(GoogleFixtures.video(aac = true).bytes, TrimSpec(TimeRange(Time.Zero, Time(40, 1000u))), "CAPABILITY_UNSUPPORTED"),
            Triple(normal, TrimSpec(TimeRange(Time(20, 1000u), Time(40, 1000u)), TrimMode.LosslessOnly), "LOSSLESS_TRIM_UNAVAILABLE"))) {
            val sink = TestSink()
            assertEquals(code, assertIs<CoreResult.Failure>(LosslessTrimWriter.write(reader(bytes), spec, sink)).error.code.value)
            assertEquals(0, sink.calls)
        }
    }
    @Test fun budgetsAndCancellationAreCheckedBeforeWriting(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        for (limited in listOf(context.copy(limits = context.limits.copy(maxOutputBytes = 1uL)),
            context.copy(limits = context.limits.copy(maxMetadataBytes = 1uL)), context.copy(cancellation = Cancellation { true }))) {
            val sink = TestSink()
            assertEquals(if (limited.cancellation != null) "CANCELLED" else "RESOURCE_LIMIT_EXCEEDED", assertIs<CoreResult.Failure>(
                LosslessTrimWriter.write(reader(bytes, limited), TrimSpec(TimeRange(Time.Zero, Time(40, 1000u))), sink)).error.code.value)
            assertEquals(0, sink.calls); assertFalse(sink.closed)
        }
    }
}
