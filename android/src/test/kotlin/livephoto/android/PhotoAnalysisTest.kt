package livephoto.android

import kotlinx.coroutines.runBlocking
import livephoto.core.*
import livephoto.ui.*
import kotlin.test.*

class PhotoAnalysisTest {
    @Test fun sharedAnalysisPreservesUnknownInputAndDoesNotInventAProtocol() = runBlocking {
        val input = byteArrayOf(0, 1, 2, 3)
        val before = input.copyOf()
        val result = assertIs<CoreResult.Success<AnalysisResult>>(analyzeSelectedPhoto(AndroidCoreFactory.create(), input)).value
        assertEquals(Disposition.Unknown, result.inspection.detection.disposition)
        assertNull(result.inspection.detection.primaryProtocol)
        assertContentEquals(before, input)
        assertTrue(formatPhotoAnalysis(result).contains("不代表"))
    }

    @Test fun sharedAnalysisRejectsOversizedInputBeforeParsing() = runBlocking {
        val result = assertIs<CoreResult.Failure>(analyzeSelectedPhoto(AndroidCoreFactory.create(), ByteArray(ANALYSIS_INPUT_LIMIT + 1)))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", result.error.code.value)
        assertEquals(Stage.Read, result.error.stage)
    }

    @Test fun androidInspectionRaisesOnlyTheBorrowedInputLimit() = runBlocking {
        val requests = mutableListOf<AnalyzeRequest>()
        val sentinel = CoreResult.Failure(CoreError(IssueCode("TEST_ANALYZE"), Stage.Inspect, "Recorded"))
        val cancellation = Cancellation { false }
        val core = object : LivePhotoCore by AndroidCoreFactory.create() {
            override suspend fun analyze(request: AnalyzeRequest): CoreResult<AnalysisResult> {
                requests += request
                return sentinel
            }
        }
        fun sizeOnly(bytes: Int) = object : BinarySource {
            override suspend fun size(): CoreResult<ULong> = CoreResult.Success(bytes.toULong())
            override suspend fun identity(): CoreResult<SourceIdentity> = error("Only the parser can read an identity")
            override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = error("Only the parser can read bytes")
            override suspend fun close() = error("The inspection entry only borrows the source")
        }

        val original = sizeOnly(ANALYSIS_INPUT_LIMIT)
        val extended = sizeOnly(ANDROID_INSPECTION_INPUT_LIMIT)
        assertSame(sentinel, inspectAndroidPhoto(core, original, cancellation))
        assertSame(sentinel, inspectAndroidPhoto(core, extended, cancellation))
        val rejected = assertIs<CoreResult.Failure>(inspectAndroidPhoto(core, sizeOnly(ANDROID_INSPECTION_INPUT_LIMIT + 1), cancellation))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", rejected.error.code.value)
        assertEquals(Stage.Read, rejected.error.stage)
        assertEquals(2, requests.size)
        val normal = requests[0]
        assertSame(original, assertIs<SourceSet.Single>(normal.input).source)
        assertEquals(ANALYSIS_INPUT_LIMIT.toULong(), normal.context.limits.maxSpoolBytes)
        val large = requests[1]
        assertSame(extended, assertIs<SourceSet.Single>(large.input).source)
        assertEquals(ANDROID_INSPECTION_INPUT_LIMIT.toULong(), large.context.limits.maxSpoolBytes)
        assertEquals(ANALYSIS_INPUT_LIMIT.toULong(), large.context.limits.maxMetadataBytes)
        assertEquals(0uL, large.context.limits.maxOutputBytes)
        assertEquals(normal.layers, large.layers)
        assertSame(cancellation, large.context.cancellation)
        assertEquals(32 * 1024 * 1024, AndroidCreateController.MAX_INPUT_BYTES)
    }

    @Test fun presentationKeepsUnknownSeparateFromZeroAndShowsPartialCoverage() = runBlocking {
        val result = assertIs<CoreResult.Success<AnalysisResult>>(analyzeSelectedPhoto(AndroidCoreFactory.create(), byteArrayOf(0))).value
        assertTrue(formatPhotoAnalysis(result).contains("关键照片位置：未知（不等于 0）"))
        val changed = result.copy(
            inspection = result.inspection.copy(keyPhoto = KeyPhotoResult(Time(0, 1000u), source = KeySource.ProtocolField)),
            validation = result.validation.copy(coverage = Coverage.Partial,
                checks = listOf(CheckResult("decoder", Layer.Media, Verdict.Warning, Coverage.NotRun))),
            capabilities = CapabilitySet(Availability.Conditional, emptyList()))
        val text = formatPhotoAnalysis(changed)
        assertTrue(text.contains("关键照片位置：0/1000 秒"))
        assertTrue(text.contains("关键照片来源：ProtocolField"))
        assertTrue(text.contains("覆盖：Partial"))
        assertTrue(text.contains("Media / decoder：Warning / NotRun"))
        assertTrue(text.contains("能力可用性：Conditional"))
        assertTrue(text.contains("不是完整播放或设备相册兼容证明"))
    }

    @Test fun failurePresentationIsBoundedAndDoesNotCallParseFailureNonLive() {
        val text = formatPhotoAnalysisFailure(CoreError(IssueCode("CORRUPTED_CONTAINER"), Stage.Parse, "x".repeat(8000)))
        assertTrue(text.contains("错误码：CORRUPTED_CONTAINER"))
        assertTrue(text.contains("阶段：Parse"))
        assertTrue(text.contains("检查失败不等于普通照片"))
        assertTrue(text.length < 2400)
    }
}
