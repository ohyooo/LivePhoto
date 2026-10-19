package livephoto.android

import kotlinx.coroutines.runBlocking
import livephoto.core.*
import livephoto.ui.*
import kotlin.test.*

class PhotoRepairTest {
    @Test fun malformedAndUnknownInputsNeverProduceRepairFiles() = runBlocking {
        for (bytes in listOf(byteArrayOf(0, 1, 2, 3), byteArrayOf(-1, -40, -1, -31, 0, 16, 0, 0))) {
            val before = bytes.copyOf()
            assertIs<CoreResult.Failure>(preparePhotoRepair(AndroidCoreFactory.create(), bytes))
            assertContentEquals(before, bytes)
        }
    }
    @Test fun repairBudgetIsCheckedBeforeParsing() = runBlocking {
        val result = assertIs<CoreResult.Failure>(preparePhotoRepair(AndroidCoreFactory.create(), ByteArray(ANALYSIS_INPUT_LIMIT + 1)))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", result.error.code.value)
        assertEquals(Stage.Read, result.error.stage)
    }
    @Test fun applyingDifferentBytesRequiresANewPreview() = runBlocking {
        val bytes = byteArrayOf(0, 1, 2, 3)
        val review = RepairReview(Digest("0".repeat(64)), listOf(Change("test", reason = "test", requested = true)))
        val result = assertIs<CoreResult.Failure>(preparePhotoRepair(AndroidCoreFactory.create(), bytes, review))
        assertEquals("SOURCE_CHANGED", result.error.code.value)
        assertEquals(Stage.Read, result.error.stage)
        assertContentEquals(byteArrayOf(0, 1, 2, 3), bytes)
    }
    @Test fun repairReportsDistinguishPreviewNoOpBlockedAndAppliedWithoutPrivateValues() {
        val digest = Digest("0".repeat(64))
        val change = Change("test:Length", Value.Text("PRIVATE_BEFORE"), Value.Text("PRIVATE_AFTER"), "reason", true)
        val base = PhotoRepairReport(digest, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(formatPhotoRepair(base, "SAVED").startsWith("无需修改（未写入）"))
        val blocked = base.copy(blocked = listOf(Issue(IssueCode("REPAIR_AMBIGUOUS"), Severity.Error, Layer.Protocol)))
        assertTrue(formatPhotoRepair(blocked, "SAVED").startsWith("修复被阻止（未写入）"))
        val preview = base.copy(review = RepairReview(digest, listOf(change)), proposedChanges = listOf(change))
        val text = formatPhotoRepair(preview, "SAVED")
        assertTrue(text.startsWith("修复预览完成（未写入）")); assertTrue(text.contains("当前问题（未写入）"))
        assertFalse(text.contains("PRIVATE_")); assertFalse(text.contains("SAVED"))
        assertTrue(formatPhotoRepair(base.copy(changesApplied = listOf(change), outputDigest = digest, byteLength = 4u), "SAVED").startsWith("SAVED"))
        val failure = formatRepairFailure(CoreError(IssueCode("REPAIR_AMBIGUOUS"), Stage.Plan, "x".repeat(8000)))
        assertTrue(failure.contains("REPAIR_AMBIGUOUS")); assertTrue(failure.length < 2300)
    }
}
