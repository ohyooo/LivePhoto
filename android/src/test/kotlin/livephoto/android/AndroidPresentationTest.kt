package livephoto.android

import livephoto.core.*
import kotlin.test.*

class AndroidPresentationTest {
    @Test fun fileSizeDistinguishesUnknownZeroAndBinaryUnits() {
        assertTrue(formatMediaSize(null).startsWith("未知"))
        assertTrue(formatMediaSize(-1).startsWith("未知"))
        assertEquals("0 B", formatMediaSize(0))
        assertEquals("1023 B", formatMediaSize(1023))
        assertEquals("1.00 KiB（1024 字节）", formatMediaSize(1024))
        assertEquals("1.00 MiB（1048576 字节）", formatMediaSize(1048576))
        assertTrue(formatMediaSize(Long.MAX_VALUE).contains(Long.MAX_VALUE.toString()))
    }

    @Test fun coreFailurePreservesActualReasonStageLocationAndDetails() {
        val text = formatCoreFailure(CoreError(IssueCode("UNSUPPORTED_CONTAINER"), Stage.Parse,
            "Multiple sample descriptions are not implemented",
            Location(source = SourceId("selected-video"), range = ByteRange(12u, 30u), track = TrackId("2")),
            mapOf("codec" to Value.Text("hvc1"), "fields" to Value.ArrayValue(listOf(Value.Number("2"), Value.BooleanValue(true))))))
        listOf("UNSUPPORTED_CONTAINER", "Parse", "Multiple sample descriptions are not implemented", "selected-video", "12 + 30", "hvc1", "[2, true]").forEach {
            assertTrue(text.contains(it), "Missing actual failure information: $it")
        }
        assertTrue(text.contains("不会自动转码"))
    }

    @Test fun failureRenderingBoundsUntrustedDiagnosticText() {
        val text = formatCoreFailure(CoreError(IssueCode("PARSE_FAILED"), Stage.Parse, "x".repeat(20000),
            details = mapOf("reason" to Value.Text("y".repeat(20000)))))
        assertTrue(text.length < 6000)
        assertTrue(text.contains("PARSE_FAILED"))
    }

    @Test fun androidFailuresRetainCauseAndActionWithoutStackTrace() {
        val permission = formatAndroidFailure(SecurityException("Read grant expired"))
        assertTrue(permission.contains("Read grant expired"))
        assertTrue(permission.contains("重新选择"))
        assertFalse(permission.contains("at livephoto"))
        val budget = formatAndroidFailure(IllegalArgumentException("Input byte budget exceeded"))
        assertTrue(budget.contains("Input byte budget exceeded"))
        assertTrue(budget.contains("32 MiB"))
    }
}
