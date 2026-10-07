package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** Encoding is a separate, explicitly authorized plan, never a failed-process fallback. */
internal data class PlannedTrim(val boundaries: LosslessTrimPlan, val encoded: Boolean = false) {
    val trackTrim: TrackTrim get() = boundaries.trackTrim.copy(transcoded = encoded, samplesPreserved = !encoded)
}

internal suspend fun planTrim(reader: BinaryReader, video: VideoStructure, spec: TrimSpec, policy: MutationPolicy): PlannedTrim {
    try { return PlannedTrim(planLosslessTrim(reader, video, spec)) }
    catch (fault: CoreFault) {
        if (spec.mode != TrimMode.Exact || fault.error.code.value !in setOf("EXACT_TRIM_UNAVAILABLE", "LOSSLESS_TRIM_UNAVAILABLE")) throw fault
        if (policy.transcode == TranscodePolicy.Forbid) throw fault
    }
    if (policy.preservation == PreservationPolicy.Strict || policy.requiredGuarantees.isNotEmpty())
        fail("PRESERVATION_REQUIREMENT_FAILED", "Encoded trim cannot promise strict/exact/bitstream/full metadata preservation", Stage.Plan)
    val track = transcodeProfile(video, VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4))
    // Only exact sample boundaries; arbitrary subframe clipping is not implemented or rounded.
    val start = track.samples.firstOrNull { Time(it.decodeTime.toLong(), track.timescale).compareTo(spec.range.start) == 0 }?.decodeTime
        ?: fail("EXACT_TRIM_UNAVAILABLE", "Encoded trim start must be an exactly representable presentation frame boundary", Stage.Plan)
    val end = (track.samples.map { it.decodeTime } + track.duration).firstOrNull { Time(it.toLong(), track.timescale).compareTo(spec.range.end) == 0 }
        ?: fail("EXACT_TRIM_UNAVAILABLE", "Encoded trim end must be an exactly representable presentation boundary", Stage.Plan)
    if (end <= start) fail("EXACT_TRIM_UNAVAILABLE", "No frames in exact trim range", Stage.Plan)
    val selected = track.samples.filter { it.decodeTime >= start && it.decodeTime < end }
    val plan = LosslessTrimPlan(video, track, selected, Time(start.toLong(), track.timescale), Time(end.toLong(), track.timescale), start, end - start)
    plan.expected()
    reader.validateIdentity().orThrow()
    return PlannedTrim(plan, true)
}

internal suspend fun verifyEncodedTrim(input: BinaryReader, plan: LosslessTrimPlan, output: BinaryReader, actual: VideoStructure) {
    val expected = plan.expected()
    val a = expected.tracks.single()
    val b = transcodeProfile(actual, VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4))
    if (actual.movieTimescale != expected.movieTimescale || actual.movieDuration != expected.movieDuration ||
        a.trackId != b.trackId || a.timescale != b.timescale || a.duration != b.duration || a.presentationDuration.compareTo(b.presentationDuration) != 0 ||
        a.width != b.width || a.height != b.height || a.samples.size != b.samples.size || !b.samples.first().isSync ||
        a.samples.zip(b.samples).any { (left, right) -> left.decodeTime != right.decodeTime || left.presentationTime != right.presentationTime || left.duration != right.duration })
        fail("POSTCONDITION_FAILED", "Encoded trim changed the exact selected sample timeline or dimensions", Stage.Verify)
    verifyTrimDurationHeaders(output, actual, plan)
    // Do not trust stss alone: prove the encoded output begins with an actual closed IDR.
    planLosslessTrim(output, actual, TrimSpec(TimeRange(Time.Zero, plan.duration), TrimMode.Exact))
    RemuxVerification.verifyMetadata(RemuxVerification.metadata(input, plan.source, trimDurationsVerifiedSeparately = true, transcodeAvcConfiguration = true),
        RemuxVerification.metadata(output, actual, trimDurationsVerifiedSeparately = true, transcodeAvcConfiguration = true))
    input.validateIdentity().orThrow(); output.validateIdentity().orThrow()
}
