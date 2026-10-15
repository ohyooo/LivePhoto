package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.CoreFault
import kotlin.test.*

class PresentationClockTest {
    @Test fun fractionalAudioOffsetUsesAnExactMinimalClock() {
        val clock = PresentationClock.create(48000u, VideoEdit(10000u, 2408uL, 28373uL, 0))
        assertEquals(240000u, clock.timescale)
        assertEquals(57792L, clock.map(0))
        assertEquals(62912L, clock.map(1024))
        assertEquals(0L, clock.mediaTime(57792))
        assertEquals(1024L, clock.mediaTime(62912))
        assertEquals(0, Time(clock.map(0), clock.timescale).compareTo(Time(2408, 10000u)))
    }

    @Test fun integralOffsetsDoNotEnlargeTheExistingClock() {
        val clock = PresentationClock.create(48000u, VideoEdit(10000u, 2405uL, 28373uL, 0))
        assertEquals(48000u, clock.timescale)
        assertEquals(11544L, clock.map(0))
        val plain = PresentationClock.create(90000u, null)
        assertEquals(90000u, plain.timescale)
        assertEquals(-123L, plain.map(-123))
        assertEquals(-123L, plain.mediaTime(-123))
    }

    @Test fun negativePrerollAndNonzeroMediaStartStaySignedAndExact() {
        val clock = PresentationClock.create(600u, VideoEdit(1000u, 1uL, 200uL, 2))
        assertEquals(3000u, clock.timescale)
        assertEquals(-7L, clock.map(0))
        assertEquals(0L, clock.mediaTime(-7))
        assertEquals(-12L, clock.map(-1))
        assertEquals(-1L, clock.mediaTime(-12))
    }

    @Test fun unrepresentableClockIsRejectedWithoutRounding() {
        val error = assertFailsWith<CoreFault> { PresentationClock.create(UInt.MAX_VALUE, VideoEdit(2u, 1uL, 1uL, 0)) }
        assertEquals("VALUE_NOT_REPRESENTABLE", error.error.code.value)
    }

    @Test fun signedTimelineOverflowCannotWrapIntoAValidPosition() {
        val clock = PresentationClock.create(1u, VideoEdit(UInt.MAX_VALUE, 1uL, 1uL, 0))
        assertEquals("INTEGER_OVERFLOW", assertFailsWith<CoreFault> { clock.map(Long.MAX_VALUE) }.error.code.value)
        assertEquals("INTEGER_OVERFLOW", assertFailsWith<CoreFault> { clock.map(Long.MIN_VALUE) }.error.code.value)
        val shifted = PresentationClock.create(1u, VideoEdit(1u, 1uL, 1uL, 1))
        assertEquals("INTEGER_OVERFLOW", assertFailsWith<CoreFault> { shifted.map(Long.MIN_VALUE) }.error.code.value)
        assertEquals("INTEGER_OVERFLOW", assertFailsWith<CoreFault> { shifted.mediaTime(Long.MIN_VALUE) }.error.code.value)
    }

    @Test fun inverseSelectionRejectsFractionalMediaTicks() {
        val clock = PresentationClock.create(600u, VideoEdit(1000u, 1uL, 200uL, 0))
        val error = assertFailsWith<CoreFault> { clock.mediaTime(4) }
        assertEquals("VALUE_NOT_REPRESENTABLE", error.error.code.value)
        assertEquals(Stage.Plan, error.error.stage)
    }
}
