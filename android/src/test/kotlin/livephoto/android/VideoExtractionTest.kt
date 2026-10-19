package livephoto.android

import kotlinx.coroutines.runBlocking
import livephoto.core.*
import livephoto.ui.*
import kotlin.test.*

class VideoExtractionTest {
    @Test fun unknownInputCannotBecomeAnExportedVideo() = runBlocking {
        val input = byteArrayOf(0, 1, 2, 3)
        val before = input.copyOf()
        assertIs<CoreResult.Failure>(prepareExtractedVideo(AndroidCoreFactory.create(), input))
        assertContentEquals(before, input)
    }
    @Test fun extractionBudgetIsCheckedBeforeParsing() = runBlocking {
        val failure = assertIs<CoreResult.Failure>(prepareExtractedVideo(AndroidCoreFactory.create(), ByteArray(ANALYSIS_INPUT_LIMIT + 1)))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", failure.error.code.value)
        assertEquals(Stage.Read, failure.error.stage)
    }
    @Test fun malformedJpegIsNotExportedOrMutated() = runBlocking {
        val input = byteArrayOf(-1, -40, -1, -31, 0, 16, 0, 0)
        val before = input.copyOf()
        assertIs<CoreResult.Failure>(prepareExtractedVideo(AndroidCoreFactory.create(), input))
        assertContentEquals(before, input)
    }
    @Test fun exportMessagesDistinguishDownloadAndPlaybackFromExactExtraction() {
        val report = ExtractedVideo(4u, "video/mp4", "mp4", Digest("0".repeat(64)), PreservationReport(), emptyList())
        val text = formatExtractedVideo(report, "已发起下载")
        assertTrue(text.contains("已发起下载"))
        assertTrue(text.contains("ExactExtraction：Verified"))
        assertTrue(text.contains("不代表视频可播放"))
        assertTrue(text.contains("不是 Clean Split"))
        val failure = formatVideoExtractionFailure(CoreError(IssueCode("MOTION_VIDEO_MISSING"), Stage.Extract, "x".repeat(8000)))
        assertTrue(failure.contains("MOTION_VIDEO_MISSING"))
        assertTrue(failure.length < 2300)
    }
}
