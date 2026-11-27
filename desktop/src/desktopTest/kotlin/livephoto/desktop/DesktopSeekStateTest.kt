package livephoto.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DesktopSeekStateTest {
    @Test fun staleTicksAndOldTimeoutCannotSettleNewSeek() {
        val seek = DesktopSeekState()
        assertEquals(0.4, seek.observe(0.4))
        val first = seek.request(0.8)
        assertNull(seek.observe(0.41))
        val latest = seek.request(1.5)
        assertNull(seek.observe(0.81))
        assertNull(seek.expire(first, 0.81))
        assertSame(latest, seek.pending)
        assertEquals(1.48, seek.observe(1.48))
        assertNull(seek.pending)
        assertNull(seek.expire(latest, 0.81))
        assertEquals(1.55, seek.observe(1.55))
    }

    @Test fun timeoutUsesNativeClockOrLastConfirmedValueAndNeverClosesCancelledSeek() {
        val seek = DesktopSeekState()
        assertEquals(0.3, seek.observe(0.3))
        val invalidClock = seek.request(4.0)
        assertNull(seek.observe(Double.NaN))
        assertEquals(0.3, seek.expire(invalidClock, Double.POSITIVE_INFINITY))
        val snapped = seek.request(5.0)
        assertEquals(4.6, seek.expire(snapped, 4.6))
        val cancelled = seek.request(8.0)
        seek.cancel()
        assertNull(seek.expire(cancelled, 8.0))
        assertNull(seek.observe(-1.0))
    }
}
