package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

/** Independent descriptor bytes: a known negative SBR flag never grants HE-AAC support. */
class AacSyncExtensionTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private fun descriptor(asc: ByteArray): Bytes = Bytes(byteArrayOf(0, 0, 0, 0, 3, (23 + asc.size).toByte(), 0, 2, 0,
        4, (15 + asc.size).toByte(), 0x40, 0x15) + ByteArray(11) + byteArrayOf(5, asc.size.toByte()) + asc + byteArrayOf(6, 1, 2))
    private val extension = byteArrayOf(0x56, 0xe5.toByte(), 0)
    private val stereo = byteArrayOf(0x11, 0x90.toByte())

    @Test fun indexedMonoAndStereoWithExplicitAbsentSbrKeepTheirOriginalRateAndChannels() {
        for ((bytes, rate, channels) in listOf(Triple(stereo, 48000u, 2u), Triple(byteArrayOf(0x12, 0x08), 44100u, 1u))) {
            val basic = validateEsds(descriptor(bytes))
            val extended = validateEsds(descriptor(bytes + extension))
            assertEquals(basic, extended)
            assertEquals(rate, extended.actualSampleRate); assertEquals(channels, extended.channelCount)
        }
    }

    @Test fun explicitFrequencyUsesTheSameKnownExtensionWithoutChangingItsClock() {
        val packed = (2uL shl 35) or (15uL shl 31) or (48000uL shl 7) or (2uL shl 3)
        val basic = ByteArray(5) { (packed shr ((4 - it) * 8)).toByte() }
        assertEquals(validateEsds(descriptor(basic)), validateEsds(descriptor(basic + extension)))
        assertEquals(48000u, validateEsds(descriptor(basic + extension)).actualSampleRate)
    }

    @Test fun sbrEnabledUnknownTypesAndExtraExtensionsRemainUnsupported() {
        for (suffix in listOf(byteArrayOf(0x56, 0xe5.toByte(), 0x80.toByte()), byteArrayOf(0x56, 0xe4.toByte(), 0),
            byteArrayOf(0x55, 0xe5.toByte(), 0), extension + byteArrayOf(0))) {
            val result = attemptNow { validateEsds(descriptor(stereo + suffix)) }
            assertEquals("UNSUPPORTED_CONTAINER", assertIs<CoreResult.Failure>(result).error.code.value)
        }
    }

    @Test fun partialExtensionAndNonzeroPaddingAreMalformed() {
        for (suffix in listOf(extension.take(1).toByteArray(), extension.take(2).toByteArray(), byteArrayOf(0x56, 0xe5.toByte(), 1))) {
            assertEquals("CORRUPTED_CONTAINER", assertIs<CoreResult.Failure>(attemptNow { validateEsds(descriptor(stereo + suffix)) }).error.code.value)
        }
    }

    @Test fun hintRestorationCannotDropOrSubstituteTheSyncExtension() {
        val before = descriptor(stereo + extension)
        val raw = before.toByteArray(); val start = validateEsds(before).descriptorHintsOffset
        raw[start] = 1
        assertEquals(ByteRange(start.toULong(), 11uL), AacDescriptorHints.patchRange(before, Bytes(raw), ParseBudget(context)).orThrow())
        for (other in listOf(descriptor(stereo), descriptor(byteArrayOf(0x12, 0x10) + extension)))
            assertEquals("POSTCONDITION_FAILED", assertIs<CoreResult.Failure>(AacDescriptorHints.patchRange(before, other, ParseBudget(context))).error.code.value)
    }

    @Test fun structuralProbeAcceptsKnownExtensionButNeverClaimsDecoderValidation(): Unit = runImmediate {
        val source = MemoryBinarySource(Bytes(GoogleFixtures.video(aac = true, audioConfig = stereo + extension).bytes), SourceId("aac-sync"))
        val video = BmffVideoProbe(BinaryReader(source, context)).probe(ByteRange(0uL, source.size().orThrow())).orThrow()
        val audio = video.tracks.single { it.handler == "soun" }
        assertEquals(48000u, audio.audioActualSampleRate); assertEquals(2u, audio.audioActualChannelCount)
        assertEquals(descriptor(stereo + extension), audio.codecConfiguration)
        assertFalse(video.decoderValidated)
    }
}
