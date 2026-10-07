package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** Finite AVC/no-audio/no-reorder profile. All retained dependencies start at a verified IDR. */
internal data class LosslessTrimPlan(val source: VideoStructure, val track: VideoTrack, val samples: List<VideoSample>, val start: Time, val end: Time, val startTicks: ULong, val durationTicks: ULong) {
    val duration: Time get() = Time(durationTicks.toLong(), track.timescale)
    val mapping: List<TimelineSegment> get() = listOf(TimelineSegment(TimeRange(start, end), TimeRange(Time.Zero, duration)))
    val trackTrim: TrackTrim get() = TrackTrim(TrackId(track.trackId.toString()), TimeRange(start, end), TimeRange(start, end), mapping, false, true)
    fun expected(): VideoStructure {
        val product = checkedMultiply(durationTicks, source.movieTimescale.toULong())
        if (product % track.timescale.toULong() != 0uL) fail("VALUE_NOT_REPRESENTABLE", "Trim duration cannot be represented in source movie timescale", Stage.Plan)
        return source.copy(movieDuration = product / track.timescale.toULong(), tracks = listOf(track.copy(duration = durationTicks,
            presentationDuration = duration, edit = null, samples = samples.map { it.copy(decodeTime = it.decodeTime - startTicks, presentationTime = it.presentationTime - startTicks.toLong()) })))
    }
}

internal suspend fun planLosslessTrim(reader: BinaryReader, video: VideoStructure, spec: TrimSpec): LosslessTrimPlan {
    val track = video.tracks.singleOrNull()?.takeIf { it.handler == "vide" && it.codec == VideoCodec.Avc && it.sampleEntry == "avc1" }
        ?: fail("LOSSLESS_TRIM_UNAVAILABLE", "This trim profile requires one AVC video track and no audio; no tracks may be dropped", Stage.Plan)
    if (track.samples.any { it.decodeTime > Long.MAX_VALUE.toULong() || it.presentationTime != it.decodeTime.toLong() } ||
        track.edit?.let { it.emptyDuration != 0uL || it.mediaStart != 0L || Time(it.segmentDuration.toLong(), it.movieTimescale).compareTo(Time(track.duration.toLong(), track.timescale)) != 0 } == true)
        fail("LOSSLESS_TRIM_UNAVAILABLE", "Reordered frames and nontrivial edits need a different dependency/timeline proof", Stage.Plan)
    val sourceEnd = Time(track.duration.toLong(), track.timescale)
    if (spec.range.end > sourceEnd) fail("INVALID_PRESENTATION_TIMESTAMP", "Trim range exceeds the source presentation domain", Stage.Plan)
    // Reject parameter sets hidden in an earlier sample and dependency structures outside this proof.
    val nalWidth = (track.codecConfiguration[4].toInt() and 3) + 1
    val idr = mutableSetOf<ULong>()
    for (sample in track.samples) {
        checkCancelled(reader.context)
        var offset = sample.range.offset
        var firstVcl: Int? = null
        while (offset < sample.range.endExclusive) {
            val length = readUnsigned(reader.readBuffer(offset, nalWidth.toUInt()).orThrow(), Endian.Big)
            offset += nalWidth.toULong()
            checkedRange(offset, length, sample.range.endExclusive)
            val type = reader.readBuffer(offset, 1u).orThrow()[0].toInt() and 31
            if (type !in setOf(1, 5, 6, 9, 12)) fail("LOSSLESS_TRIM_UNAVAILABLE", "In-band parameter sets or unknown AVC dependencies cannot be trimmed by this profile", Stage.Plan)
            if (firstVcl == null && type in setOf(1, 5)) firstVcl = type
            offset += length
        }
        if (firstVcl == 5 && sample.isSync) idr += sample.decodeTime
    }
    val points = track.samples.map { it.decodeTime } + track.duration
    fun time(ticks: ULong): Time = Time(ticks.toLong(), track.timescale)
    fun exactOrWithin(target: Time, candidates: List<ULong>): ULong? = candidates.firstOrNull { time(it).compareTo(target) == 0 }
        ?: candidates.firstOrNull { absoluteDifference(time(it), target) <= spec.exactTolerance }
    var start = exactOrWithin(spec.range.start, points.dropLast(1).filter { it in idr })
    var end = exactOrWithin(spec.range.end, points.drop(1))
    if (start == null || end == null || start >= end) {
        if (spec.mode != TrimMode.LosslessPreferred) fail(if (spec.mode == TrimMode.Exact) "EXACT_TRIM_UNAVAILABLE" else "LOSSLESS_TRIM_UNAVAILABLE", "Requested boundaries cannot be satisfied without encoding", Stage.Plan)
        if (spec.boundary != BoundaryPolicy.CoverRequestedRange) fail("LOSSLESS_TRIM_UNAVAILABLE", "Only covering lossless boundary selection is implemented", Stage.Plan)
        start = points.dropLast(1).lastOrNull { it in idr && time(it) <= spec.range.start }
        end = points.firstOrNull { time(it) >= spec.range.end }
        if (start == null || end == null || start >= end) fail("LOSSLESS_TRIM_UNAVAILABLE", "No safe covering IDR/frame boundaries", Stage.Plan)
    }
    val first = start; val last = end
    val deviation = spec.maxBoundaryDeviation
    if (deviation != null && (absoluteDifference(time(first), spec.range.start) > deviation || absoluteDifference(time(last), spec.range.end) > deviation))
        fail("TRIM_BOUNDARY_DEVIATION_EXCEEDED", "Actual lossless boundaries exceed the explicitly permitted deviation", Stage.Plan)
    val selected = track.samples.filter { it.decodeTime >= first && it.decodeTime < last }
    val plan = LosslessTrimPlan(video, track, selected, time(first), time(last), first, last - first)
    plan.expected()
    reader.validateIdentity().orThrow()
    return plan
}

/** Check tkhd's exact duration too: the generic structural parser permits one movie tick of rounding. */
internal suspend fun verifyTrimDurationHeaders(reader: BinaryReader, video: VideoStructure, plan: LosslessTrimPlan) {
    if (video.container != plan.source.container) fail("POSTCONDITION_FAILED", "Trim changed the source container", Stage.Verify)
    val boxes = BmffReader(reader)
    val moov = boxes.readBoxes(video.range).orThrow().single { it.type == "moov" }
    val trak = boxes.readBoxes(moov.payload, 1u).orThrow().single { it.type == "trak" }
    val header = boxes.readBoxes(trak.payload, 2u).orThrow().single { it.type == "tkhd" }
    val version = reader.readBuffer(header.payload.offset, 1u).orThrow()[0].toInt() and 255
    val offset = if (version == 0) 20uL else 28uL
    val duration = readUnsigned(reader.readBuffer(header.payload.offset + offset, if (version == 0) 4u else 8u).orThrow(), Endian.Big)
    if (duration > Long.MAX_VALUE.toULong() || Time(duration.toLong(), video.movieTimescale).compareTo(plan.duration) != 0)
        fail("POSTCONDITION_FAILED", "Trim track header duration differs from the actual disclosed range", Stage.Verify)
}

/** Bounded profile: require exactly representable microseconds only for boundary distance, not frame selection. */
private fun absoluteDifference(first: Time, second: Time): Time {
    if (first.compareTo(second) == 0) return Time.Zero
    val a = microseconds(first); val b = microseconds(second)
    return Time(if (a >= b) a - b else b - a, 1_000_000u)
}
