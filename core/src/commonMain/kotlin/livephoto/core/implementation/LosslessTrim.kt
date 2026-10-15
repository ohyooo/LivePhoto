package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** Pure planning/verification; the desktop backend delegates the actual packet selection/mux to FFmpeg. */
internal data class AudioTrimSelection(val track: VideoTrack, val samples: List<VideoSample>, val startTicks: ULong, val endTicks: ULong, val originTicks: ULong)

internal data class LosslessTrimPlan(val source: VideoStructure, val track: VideoTrack, val samples: List<VideoSample>, val start: Time, val end: Time, val startTicks: ULong, val durationTicks: ULong,
    val audio: List<AudioTrimSelection> = emptyList(), val movieTimescale: UInt = source.movieTimescale) {
    val duration: Time get() = Time(durationTicks.toLong(), track.timescale)
    val mapping: List<TimelineSegment> get() = listOf(TimelineSegment(TimeRange(start, end), TimeRange(Time.Zero, duration)))
    val trackTrim: TrackTrim get() = TrackTrim(TrackId(track.trackId.toString()), TimeRange(start, end), TimeRange(start, end), mapping, false, true)
    val trackTrims: List<TrackTrim> get() = source.tracks.map { entry ->
        if (entry.trackId == track.trackId) trackTrim else audio.single { it.track.trackId == entry.trackId }.let {
            val range = TimeRange(Time(it.startTicks.toLong(), entry.timescale), Time(it.endTicks.toLong(), entry.timescale))
            val output = TimeRange(Time((it.startTicks - it.originTicks).toLong(), entry.timescale), Time((it.endTicks - it.originTicks).toLong(), entry.timescale))
            TrackTrim(TrackId(entry.trackId.toString()), range, range, listOf(TimelineSegment(range, output)), false, true)
        }
    }
    val audioRecoveryChanged: Boolean get() = audio.any { it.samples.first() != it.track.samples.first() }
    val issues: List<Issue> get() = buildList {
        if (audioRecoveryChanged) add(Issue(IssueCode("AUDIO_RECOVERY_BOUNDARY_CHANGED"), Severity.Warning, Layer.Media))
        if (trackTrims.any { it.presented.start.compareTo(start) != 0 || it.presented.end.compareTo(end) != 0 })
            add(Issue(IssueCode("AUDIO_PACKET_BOUNDARY_GAP"), Severity.Warning, Layer.Media))
    }
    fun expected(): VideoStructure {
        val product = checkedMultiply(durationTicks, movieTimescale.toULong())
        if (product % track.timescale.toULong() != 0uL) fail("VALUE_NOT_REPRESENTABLE", "Trim duration cannot be represented in source movie timescale", Stage.Plan)
        val video = track.copy(duration = durationTicks, presentationDuration = duration, edit = null,
            samples = samples.map { it.copy(decodeTime = it.decodeTime - startTicks, presentationTime = it.presentationTime - startTicks.toLong()) })
        return source.copy(movieTimescale = movieTimescale, movieDuration = product / track.timescale.toULong(), tracks = source.tracks.map { entry ->
            if (entry.trackId == track.trackId) video else audio.single { it.track.trackId == entry.trackId }.let {
                val gap = it.startTicks - it.originTicks
                val length = it.endTicks - it.startTicks
                val empty = trimTicks(Time(gap.toLong(), entry.timescale), movieTimescale)
                val segment = trimTicks(Time(length.toLong(), entry.timescale), movieTimescale)
                entry.copy(duration = length, presentationDuration = Time((gap + length).toLong(), entry.timescale),
                    edit = if (gap == 0uL) null else VideoEdit(movieTimescale, empty, segment, 0L),
                    samples = it.samples.map { sample -> sample.copy(decodeTime = sample.decodeTime - it.startTicks,
                        presentationTime = sample.presentationTime - it.originTicks.toLong()) })
            }
        })
    }
}

internal suspend fun planLosslessTrim(reader: BinaryReader, video: VideoStructure, spec: TrimSpec): LosslessTrimPlan {
    val track = video.tracks.singleOrNull { it.handler == "vide" }?.takeIf { it.codec == VideoCodec.Avc && it.sampleEntry == "avc1" }
        ?: fail("LOSSLESS_TRIM_UNAVAILABLE", "This trim profile requires one AVC video track; no tracks may be dropped", Stage.Plan)
    val audio = video.tracks.filter { it.handler == "soun" }
    if (audio.size > 2 || video.tracks.any { it.handler !in setOf("vide", "soun") } ||
        audio.any { it.audioCodec != AudioCodec.Aac || it.sampleEntry != "mp4a" } || audio.isNotEmpty() && video.container != VideoContainer.Mp4)
        fail("LOSSLESS_TRIM_UNAVAILABLE", "Audio trim currently requires MP4 and at most two AAC-LC tracks; no track may be dropped", Stage.Plan)
    if (audio.isNotEmpty()) RemuxRollGroups.canonical(reader, video, "LOSSLESS_TRIM_UNAVAILABLE")
    for (entry in audio) {
        if (entry.headerDuration != entry.duration || entry.presentationTimescale != entry.timescale || entry.samples.any { it.decodeTime > Long.MAX_VALUE.toULong() || it.presentationTime != it.decodeTime.toLong() } ||
            entry.edit?.let { it.emptyDuration != 0uL || it.mediaStart != 0L || Time(it.segmentDuration.toLong(), it.movieTimescale).compareTo(Time(entry.duration.toLong(), entry.timescale)) != 0 } == true ||
            validateEsds(entry.codecConfiguration, ParseBudget(reader.context)).esDescriptorFlags != 0)
            fail("LOSSLESS_TRIM_UNAVAILABLE", "AAC trim cannot discard existing priming edits or referenced descriptors", Stage.Plan)
    }
    if (track.headerDuration != track.duration || track.presentationTimescale != track.timescale || track.samples.any { it.decodeTime > Long.MAX_VALUE.toULong() || it.presentationTime != it.decodeTime.toLong() } ||
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
    val audioSelections = audio.map { entry ->
        val origin = trimTicks(time(first), entry.timescale)
        val limit = trimTicks(time(last), entry.timescale)
        val kept = entry.samples.filter { it.decodeTime >= origin && checkedAdd(it.decodeTime, it.duration.toULong()) <= limit }
        if (kept.isEmpty()) fail("LOSSLESS_TRIM_UNAVAILABLE", "Trim would leave an empty audio track; dropping it is forbidden", Stage.Plan)
        val begin = kept.first().decodeTime
        val finish = checkedAdd(kept.last().decodeTime, kept.last().duration.toULong())
        val actualStart = Time(begin.toLong(), entry.timescale); val actualEnd = Time(finish.toLong(), entry.timescale)
        if (spec.mode != TrimMode.LosslessPreferred && (absoluteDifference(actualStart, spec.range.start) > spec.exactTolerance || absoluteDifference(actualEnd, spec.range.end) > spec.exactTolerance))
            fail(if (spec.mode == TrimMode.Exact) "EXACT_TRIM_UNAVAILABLE" else "LOSSLESS_TRIM_UNAVAILABLE", "Audio packet boundaries cannot satisfy the requested exact range without encoding", Stage.Plan)
        if (deviation != null && (absoluteDifference(actualStart, spec.range.start) > deviation || absoluteDifference(actualEnd, spec.range.end) > deviation))
            fail("TRIM_BOUNDARY_DEVIATION_EXCEEDED", "Audio packet boundaries exceed the explicitly permitted deviation", Stage.Plan)
        AudioTrimSelection(entry, kept, begin, finish, origin)
    }
    var movieScale = video.movieTimescale.toULong()
    if (audio.isNotEmpty()) for (entry in video.tracks) {
        var a = movieScale; var b = entry.timescale.toULong()
        while (b != 0uL) { val rest = a % b; a = b; b = rest }
        movieScale = checkedMultiply(movieScale / a, entry.timescale.toULong())
        if (movieScale > Int.MAX_VALUE.toULong()) fail("VALUE_NOT_REPRESENTABLE", "Exact audio gaps exceed the muxer movie timescale domain", Stage.Plan)
    }
    val plan = LosslessTrimPlan(video, track, selected, time(first), time(last), first, last - first, audioSelections, movieScale.toUInt())
    plan.expected()
    reader.validateIdentity().orThrow()
    return plan
}

/** Check tkhd's exact duration too: the generic structural parser permits one movie tick of rounding. */
internal suspend fun verifyTrimDurationHeaders(reader: BinaryReader, video: VideoStructure, plan: LosslessTrimPlan) {
    if (video.container != plan.source.container) fail("POSTCONDITION_FAILED", "Trim changed the source container", Stage.Verify)
    val boxes = BmffReader(reader)
    val moov = boxes.readBoxes(video.range).orThrow().single { it.type == "moov" }
    val tracks = boxes.readBoxes(moov.payload, 1u).orThrow().filter { it.type == "trak" }
    val expected = plan.expected().tracks
    if (tracks.size != expected.size) fail("POSTCONDITION_FAILED", "Trim changed the track count", Stage.Verify)
    for ((trak, track) in tracks.zip(expected)) {
        val header = boxes.readBoxes(trak.payload, 2u).orThrow().single { it.type == "tkhd" }
        val version = reader.readBuffer(header.payload.offset, 1u).orThrow()[0].toInt() and 255
        val offset = if (version == 0) 20uL else 28uL
        val duration = readUnsigned(reader.readBuffer(header.payload.offset + offset, if (version == 0) 4u else 8u).orThrow(), Endian.Big)
        if (duration > Long.MAX_VALUE.toULong() || Time(duration.toLong(), video.movieTimescale).compareTo(track.presentationDuration) != 0)
            fail("POSTCONDITION_FAILED", "Trim track header duration differs from the actual disclosed range", Stage.Verify)
    }
}

internal fun trimTicks(time: Time, scale: UInt): ULong {
    if (time.value < 0) fail("VALUE_NOT_REPRESENTABLE", "Negative trim time", Stage.Plan)
    val product = checkedMultiply(time.value.toULong(), scale.toULong())
    if (product % time.timescale.toULong() != 0uL || product / time.timescale.toULong() > Long.MAX_VALUE.toULong())
        fail("VALUE_NOT_REPRESENTABLE", "Trim boundary cannot be expressed exactly in a retained track's ticks", Stage.Plan)
    return product / time.timescale.toULong()
}

/** Compare boundary distances exactly, including AAC clocks that do not divide microseconds. */
private fun absoluteDifference(first: Time, second: Time): Time {
    if (first.compareTo(second) == 0) return Time.Zero
    var a = first.timescale.toULong(); var b = second.timescale.toULong()
    while (b != 0uL) { val rest = a % b; a = b; b = rest }
    val scale = checkedMultiply(first.timescale.toULong() / a, second.timescale.toULong())
    if (scale > UInt.MAX_VALUE.toULong()) fail("VALUE_NOT_REPRESENTABLE", "Boundary distance timescale exceeds exact domain", Stage.Plan)
    val left = trimTicks(first, scale.toUInt()); val right = trimTicks(second, scale.toUInt())
    return Time((if (left >= right) left - right else right - left).toLong(), scale.toUInt())
}
