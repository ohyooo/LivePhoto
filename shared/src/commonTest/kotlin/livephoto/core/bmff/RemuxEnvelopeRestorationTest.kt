package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class RemuxEnvelopeRestorationTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private fun reader(bytes: ByteArray, context: Context = this.context) = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("restoration-fixture")), context)

    @Test fun classifiedSourceEnvelopeAndVerifiedPacketsAreReassembledExactly(): Unit = runImmediate {
        val source = GoogleFixtures.video().bytes
        val sink = TestSink(maxChunk = 7)
        RemuxEnvelopeRestoration.write(reader(source), reader(source), sink).orThrow()
        assertContentEquals(source, sink.written.toByteArray())
        val before = reader(source); val after = reader(sink.written.toByteArray())
        val left = BmffVideoProbe(before).probe(ByteRange(0uL, source.size.toULong())).orThrow()
        val right = BmffVideoProbe(after).probe(ByteRange(0uL, source.size.toULong())).orThrow()
        RemuxVerification.verify(before, left, after, right)
        RemuxVerification.verifyMetadata(RemuxVerification.metadata(before, left), RemuxVerification.metadata(after, right))
    }

    @Test fun changedPacketOrTimingIsRejectedBeforeWriting(): Unit = runImmediate {
        val source = GoogleFixtures.video().bytes
        val parsed = BmffVideoProbe(reader(source)).probe(ByteRange(0uL, source.size.toULong())).orThrow()
        val changed = source.copyOf().also { it[(parsed.tracks.single().samples.first().range.endExclusive - 1uL).toInt()] = 42 }
        for (candidate in listOf(changed, GoogleFixtures.video(sampleDurations = 20u to 60u).bytes)) {
            val sink = TestSink()
            val result = RemuxEnvelopeRestoration.write(reader(source), reader(candidate), sink)
            assertEquals("POSTCONDITION_FAILED", assertIs<CoreResult.Failure>(result).error.code.value)
            assertEquals(0, sink.calls)
        }
    }

    @Test fun backendGeneratedMetadataIsNotSubstitutedForSourceMetadata(): Unit = runImmediate {
        val source = GoogleFixtures.video().bytes
        val packets = source + GoogleFixtures.box("uuid", ByteArray(16) { 7 })
        val sink = TestSink()
        RemuxEnvelopeRestoration.write(reader(source), reader(packets), sink).orThrow()
        assertContentEquals(source, sink.written.toByteArray(), "Backend-specific headers are not part of the preserved source envelope")
    }

    @Test fun unknownSourceMetadataAndExtraTracksRemainClosed(): Unit = runImmediate {
        val ordinary = GoogleFixtures.video().bytes
        for ((source, code) in listOf(
            (ordinary + GoogleFixtures.box("uuid", ByteArray(16))) to "UNSAFE_METADATA_REWRITE",
            GoogleFixtures.video(aac = true).bytes to "CAPABILITY_UNSUPPORTED")) {
            val sink = TestSink()
            assertEquals(code, assertIs<CoreResult.Failure>(RemuxEnvelopeRestoration.write(reader(source), reader(ordinary), sink)).error.code.value)
            assertEquals(0, sink.calls)
        }
    }

    @Test fun outputBudgetIsCheckedBeforeAnyStagingWrite(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val limited = Context(Limits(8_000_000uL, (bytes.size - 1).toULong()))
        val sink = TestSink()
        assertEquals("RESOURCE_LIMIT_EXCEEDED", assertIs<CoreResult.Failure>(RemuxEnvelopeRestoration.write(reader(bytes, limited), reader(bytes, limited), sink)).error.code.value)
        assertEquals(0, sink.calls)
    }

    @Test fun cancellationBeforePreflightDoesNotWriteOrCloseBorrowedHandles(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        val cancelled = Context(Limits(8_000_000uL, 8_000_000uL), cancellation = Cancellation { true })
        val sink = TestSink()
        assertEquals("CANCELLED", assertIs<CoreResult.Failure>(RemuxEnvelopeRestoration.write(reader(bytes, cancelled), reader(bytes), sink)).error.code.value)
        assertEquals(0, sink.calls)
        assertFalse(sink.closed)
    }
}
