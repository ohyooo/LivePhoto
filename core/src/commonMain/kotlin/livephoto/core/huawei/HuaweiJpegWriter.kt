package livephoto.core.huawei

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.*
import livephoto.core.jpeg.*

internal data class HuaweiJpegPlan(val image: JpegRewritePlan, val tail: Bytes)

/** Fixed-tail construction copies both input media byte strings without brand or codec patches. */
internal object HuaweiJpegWriter {
    fun createPlan(session: SourceSession, video: VideoStructure, videoLength: ULong, strip: Boolean,
        key: CoverPosition?, budget: ParseBudget): CoreResult<HuaweiJpegPlan> = attemptNow {
        val jpeg = session.jpeg ?: fail("UNSUPPORTED_CONTAINER", "Huawei writer implements JPEG only")
        if (key != null) fail("CAPABILITY_UNSUPPORTED", "Huawei key units cannot be reliably expressed by this wire profile")
        if (session.bindings.isNotEmpty()) fail(if (strip) "CAPABILITY_UNSUPPORTED" else "SOURCE_ALREADY_LIVE", "Huawei create does not replace existing live bindings")
        if (jpeg.trailing.length != 0uL) fail("UNSAFE_METADATA_REWRITE", "Huawei create cannot discard existing extensions")
        val track = video.tracks.singleOrNull { it.handler == "vide" } ?: fail("CAPABILITY_UNSUPPORTED", "Huawei writer requires one presentation video track")
        var count = 0uL
        for (sample in track.samples) {
            budget.poll()
            if (sample.presentationTime >= 0 && Time(sample.presentationTime, track.timescale) < track.presentationDuration) count++
        }
        if (count == 0uL) fail("FRAME_INDEX_UNAVAILABLE", "Huawei video has no presented samples")
        val tail = HuaweiTail.create(videoLength, "v6_f0", "0:$count", budget).orThrow()
        HuaweiJpegPlan(JpegRewrite.plan(jpeg, emptyList()).orThrow(), tail)
    }

    fun cleanPlan(session: SourceSession): CoreResult<JpegRewritePlan> = attemptNow {
        val jpeg = session.jpeg ?: fail("UNSUPPORTED_CONTAINER", "Huawei cleanup implements JPEG only")
        val binding = session.bindings.singleOrNull { it.protocol == ProtocolIds.Huawei } ?: fail("UNSUPPORTED_PROTOCOL", "Huawei cleanup needs one trusted binding")
        if (binding.profile != ProfileId("basic60") || !binding.structurallyValid || binding.protocol !in session.videos || binding.video?.offset != jpeg.primary.endExclusive || binding.trailer?.length != 60uL) fail("UNSAFE_METADATA_REWRITE", "Unknown Huawei/Honor extensions cannot authorize cleanup")
        if (session.bindings.any { it.protocol != ProtocolIds.Huawei }) fail("CAPABILITY_UNSUPPORTED", "Huawei cleanup cannot remove another protocol's bindings implicitly")
        JpegRewrite.plan(jpeg, emptyList()).orThrow()
    }
}
