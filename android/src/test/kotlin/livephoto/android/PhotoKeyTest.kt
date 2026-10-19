package livephoto.android

import kotlinx.coroutines.runBlocking
import livephoto.core.*
import livephoto.ui.*
import kotlin.test.*

class PhotoKeyTest {
    @Test fun decimalSecondsHaveNoFloatingPointOrLocaleAmbiguity() {
        assertEquals(Time(0, 1_000_000u), parseKeySeconds("0"))
        assertEquals(Time(1_000_001, 1_000_000u), parseKeySeconds("1.000001"))
        for (text in listOf("", "-1", "NaN", "1e3", "1,2", "0.0000001", "1000000", " 1")) assertNull(parseKeySeconds(text))
    }
    @Test fun keyBudgetIsCheckedBeforeParsing() = runBlocking {
        val result = assertIs<CoreResult.Failure>(preparePhotoKey(AndroidCoreFactory.create(), ByteArray(ANALYSIS_INPUT_LIMIT + 1), Time.Zero))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", result.error.code.value)
    }
    @Test fun malformedInputsRemainUnchangedAndNegativeTimeIsRejected() = runBlocking {
        val bytes = byteArrayOf(0, 1, 2, 3)
        assertIs<CoreResult.Failure>(preparePhotoKey(AndroidCoreFactory.create(), bytes, Time.Zero))
        val invalid = assertIs<CoreResult.Failure>(preparePhotoKey(AndroidCoreFactory.create(), bytes, Time(-1, 1u)))
        assertEquals("INVALID_ARGUMENT", invalid.error.code.value)
        assertContentEquals(byteArrayOf(0, 1, 2, 3), bytes)
    }
    @Test fun keyReportDisclosesActualTimeAndBoundedFailure() {
        val text = formatPhotoKey(PhotoKeyReport(Time(1, 2u), Time(40, 100u), Digest("0".repeat(64)), 100u, PreservationReport(), emptyList()), "SAVED")
        assertTrue(text.startsWith("SAVED")); assertTrue(text.contains("实际时间：40/100")); assertTrue(text.contains("不替换封面"))
        assertTrue(formatPhotoKeyFailure(CoreError(IssueCode("VALUE_NOT_REPRESENTABLE"), Stage.Plan, "x".repeat(8000))).length < 2300)
    }
}
