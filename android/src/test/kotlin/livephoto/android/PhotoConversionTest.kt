package livephoto.android

import kotlinx.coroutines.runBlocking
import livephoto.core.*
import livephoto.ui.*
import kotlin.test.*

class PhotoConversionTest {
    @Test fun unknownAndMalformedInputsCannotBecomeConvertedPhotos() = runBlocking {
        for (bytes in listOf(byteArrayOf(0, 1, 2, 3), byteArrayOf(-1, -40, -1, -31, 0, 16, 0, 0))) {
            val before = bytes.copyOf()
            assertIs<CoreResult.Failure>(prepareConvertedPhoto(AndroidCoreFactory.create(), bytes, ConversionTarget.Oplus.selector))
            assertContentEquals(before, bytes)
        }
    }
    @Test fun conversionBudgetIsCheckedBeforeParsing() = runBlocking {
        val failure = assertIs<CoreResult.Failure>(prepareConvertedPhoto(AndroidCoreFactory.create(), ByteArray(ANALYSIS_INPUT_LIMIT + 1), ConversionTarget.GoogleV2.selector))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", failure.error.code.value)
        assertEquals(Stage.Read, failure.error.stage)
    }
    @Test fun unsupportedConversionTargetsAreNotEnabledByCoreCapabilities() = runBlocking {
        for (target in listOf(ProtocolSelector(ProtocolIds.Apple), ProtocolSelector(ProtocolIds.Oplus, ProfileId("unknown")))) {
            val failure = assertIs<CoreResult.Failure>(prepareConvertedPhoto(AndroidCoreFactory.create(), byteArrayOf(0), target))
            assertEquals("CAPABILITY_UNSUPPORTED", failure.error.code.value)
        }
    }
    @Test fun conversionReportDistinguishesWholeCarrierCopyAndUnknownMetadata() {
        val report = ConvertedPhoto(ConversionTarget.GoogleV2.selector, 4u, Digest("0".repeat(64)), false,
            PreservationReport(listOf(GuaranteeRecord(AssetId("image"), Guarantee.MetadataPreserving, GuaranteeOutcome.Unknown))), emptyList())
        val text = formatConvertedPhoto(report, "已发起下载")
        assertTrue(text.contains("已发起下载")); assertTrue(text.contains("不宣称整个文件字节一致"))
        assertTrue(text.contains("MetadataPreserving: Unknown"))
        assertTrue(formatConvertedPhoto(report.copy(carrierExact = true), "已保存").contains("整个载体字节一致"))
        val failure = formatConversionFailure(CoreError(IssueCode("UNSAFE_METADATA_REWRITE"), Stage.Plan, "x".repeat(8000)))
        assertTrue(failure.contains("UNSAFE_METADATA_REWRITE")); assertTrue(failure.length < 2300)
    }
}
