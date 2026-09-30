package livephoto.core.samsung

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.implementation.*
import livephoto.core.jpeg.*

internal data class SamsungJpegPlan(val image: JpegRewritePlan, val suffix: SefWritePlan)

internal object SamsungJpegWriter {
    suspend fun createPlan(session: SourceSession, videoReader: BinaryReader, videoRange: ByteRange, timestamp: Long,
        strip: Boolean, context: Context, budget: ParseBudget): CoreResult<SamsungJpegPlan> = attempt {
        if (session.bindings.isNotEmpty()) fail(if (strip) "CAPABILITY_UNSUPPORTED" else "SOURCE_ALREADY_LIVE", "Samsung create does not replace existing live bindings")
        val jpeg = session.jpeg ?: fail("UNSUPPORTED_CONTAINER", "Samsung writer requires JPEG")
        if (jpeg.trailing.length != 0uL) fail("CAPABILITY_UNSUPPORTED", "Create from a carrier with existing SEF or unknown suffix is not implemented")
        val suffix = SefWriter.createPlan(videoReader, videoRange, budget).orThrow()
        if (suffix.videoOffset != 24uL) fail("POSTCONDITION_FAILED", "Samsung motion record header is not exactly 24 bytes")
        val image = GoogleJpegWriter.createPlan(session, ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg")),
            suffix.length - 24uL, "video/mp4", timestamp, false, context, primaryPadding = 24uL).orThrow()
        SamsungJpegPlan(image, suffix)
    }

    suspend fun cleanPlan(session: SourceSession, context: Context, budget: ParseBudget): CoreResult<SamsungJpegPlan> = attempt {
        val jpeg = session.jpeg ?: fail("CAPABILITY_UNSUPPORTED", "Samsung HEIC cleanup is not implemented")
        val directory = session.sef ?: fail("SEF_DIRECTORY_INVALID", "Samsung cleanup needs a trusted SEF directory")
        val binding = session.bindings.singleOrNull { it.protocol == ProtocolIds.Samsung }
        if (binding != null && (!binding.structurallyValid || binding.issues.any { it.code.value == "UNKNOWN_PROTOCOL_VARIANT" } || binding.protocol !in session.videos)) fail("UNSAFE_METADATA_REWRITE", "Samsung cleanup needs a verified canonical binding and video")
        if (binding != null && binding.padding != directory.motionRecord?.let { ByteRange(it.range.offset, 24uL) }) fail("SEF_DIRECTORY_INVALID", "Samsung padding does not equal its indexed record header")
        val suffix = SefWriter.cleanPlan(session.reader, directory, budget).orThrow()
        val image = if (session.bindings.none { it.protocol == ProtocolIds.GoogleV2 }) JpegRewrite.plan(jpeg, emptyList()).orThrow()
            else if (binding == null) JpegRewrite.plan(jpeg, emptyList()).orThrow()
            else GoogleJpegWriter.cleanPlan(session, context, verifiedSamsungPadding = true).orThrow()
        SamsungJpegPlan(image, suffix)
    }
}
