package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class AacDescriptorHintsTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private suspend fun config(): Bytes {
        val source = MemoryBinarySource(Bytes(GoogleFixtures.video(aac = true).bytes), SourceId("aac-hint-fixture"))
        return BmffVideoProbe(BinaryReader(source, context)).probe(ByteRange(0uL, source.size().orThrow())).orThrow().tracks.single { it.handler == "soun" }.codecConfiguration
    }

    @Test fun onlyParsedBufferAndBitrateHintsCanReceiveAPatchProof(): Unit = runImmediate {
        val before = config(); val start = validateEsds(before).descriptorHintsOffset
        val raw = before.toByteArray(); repeat(11) { raw[start + it] = (it + 1).toByte() }
        val after = Bytes(raw)
        val range = AacDescriptorHints.patchRange(before, after, ParseBudget(context)).orThrow()
        assertEquals(ByteRange(start.toULong(), 11uL), range)
        val restored = after.toByteArray()
        before.slice(start, start + 11).copyInto(restored, start)
        assertEquals(before, Bytes(restored))
    }

    @Test fun esIdentityAndAacSpecificConfigurationChangesCannotBeMaskedAsHints(): Unit = runImmediate {
        val before = config(); val start = validateEsds(before).descriptorHintsOffset
        for (offset in listOf(7, start + 13)) {
            val raw = before.toByteArray(); raw[offset] = (raw[offset].toInt() xor 1).toByte()
            val result = AacDescriptorHints.patchRange(before, Bytes(raw), ParseBudget(context))
            assertIs<CoreResult.Failure>(result)
        }
    }

    @Test fun nonzeroEsDescriptorFlagsNeverReceiveAnIndependentHintProof(): Unit = runImmediate {
        val raw = config().toByteArray(); raw[8] = 1 // stream-priority flag; no external-reference guessing
        val flagged = Bytes(raw)
        assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(AacDescriptorHints.patchRange(flagged, flagged, ParseBudget(context))).error.code.value)
    }
}
