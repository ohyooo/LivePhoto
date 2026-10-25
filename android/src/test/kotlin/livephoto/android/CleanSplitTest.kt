package livephoto.android

import kotlinx.coroutines.runBlocking
import livephoto.core.*
import livephoto.ui.*
import kotlin.test.*

class CleanSplitTest {
    @Test fun unknownInputCannotPrepareFiles() = runBlocking {
        val input = byteArrayOf(0, 1, 2, 3)
        val before = input.copyOf()
        assertIs<CoreResult.Failure>(prepareCleanSplit(AndroidCoreFactory.create(), input))
        assertContentEquals(before, input)
    }
    @Test fun splitBudgetIsCheckedBeforeParsing() = runBlocking {
        val result = assertIs<CoreResult.Failure>(prepareCleanSplit(AndroidCoreFactory.create(), ByteArray(ANALYSIS_INPUT_LIMIT + 1)))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", result.error.code.value)
        assertEquals(Stage.Read, result.error.stage)
    }
    @Test fun malformedJpegCannotPrepareFiles() = runBlocking {
        val bytes = byteArrayOf(-1, -40, -1, -31, 0, 16, 0, 0)
        assertIs<CoreResult.Failure>(prepareCleanSplit(AndroidCoreFactory.create(), bytes))
        assertContentEquals(byteArrayOf(-1, -40, -1, -31, 0, 16, 0, 0), bytes)
    }
    @Test fun reportDoesNotClaimMetadataOrExternalPairAtomicity() {
        val image = SplitFile(Bytes(byteArrayOf(1)), "image/jpeg", "jpg", Digest("1".repeat(64)), 1uL)
        val video = SplitFile(Bytes(byteArrayOf(2)), "video/mp4", "mp4", Digest("2".repeat(64)), 1uL)
        val preservation = PreservationReport(listOf(GuaranteeRecord(AssetId("image"), Guarantee.MetadataPreserving, GuaranteeOutcome.Unknown)))
        val report = formatCleanSplit(PreparedCleanSplit(image, video, preservation, emptyList()))
        assertTrue(report.contains("尚未保存"))
        assertTrue(report.contains("image: Unknown"))
        assertTrue(report.contains("不是外部原子成对保存"))
        val failure = formatCleanSplitFailure(CoreError(IssueCode("UNSAFE_METADATA_REWRITE"), Stage.Plan, "x".repeat(8000)))
        assertTrue(failure.contains("UNSAFE_METADATA_REWRITE"))
        assertTrue(failure.length < 2300)
    }
}
