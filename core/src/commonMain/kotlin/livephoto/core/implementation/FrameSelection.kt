package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** Exact source-domain selection. Equal PTS is ordered by DTS, then original sample ordinal. */
internal data class SelectedFrame(val track: VideoTrack, val sample: VideoSample, val sampleOrdinal: Int, val index: ULong) {
    val time: Time get() = Time(sample.presentationTime, track.timescale)
}

internal fun selectFrame(video: VideoStructure, position: CoverPosition): SelectedFrame {
    val requestedTrack = (position as? CoverPosition.FrameIndex)?.trackId
    val track = video.tracks.filter { it.handler == "vide" && (requestedTrack == null || TrackId(it.trackId.toString()) == requestedTrack) }.singleOrNull()
        ?: fail("FRAME_INDEX_UNAVAILABLE", "Select one unambiguous presentation video track", Stage.Plan)
    val visibleStart = track.edit?.let { Time(it.emptyDuration.toLong(), it.movieTimescale) } ?: Time.Zero
    val presented = track.samples.withIndex().filter { it.value.presentationTime >= 0 && Time(it.value.presentationTime, track.timescale) >= visibleStart && Time(it.value.presentationTime, track.timescale) < track.presentationDuration }
        .sortedWith(compareBy<IndexedValue<VideoSample>> { it.value.presentationTime }.thenBy { it.value.decodeTime }.thenBy { it.index })
    if (presented.isEmpty()) fail("FRAME_INDEX_UNAVAILABLE", "Video has no presented samples", Stage.Plan)
    val index = when (position) {
        is CoverPosition.FrameIndex -> {
            if (position.index >= presented.size.toULong()) fail("FRAME_INDEX_OUT_OF_RANGE", "Presentation frame index exceeds track", Stage.Plan)
            position.index.toInt()
        }
        is CoverPosition.Timestamp -> {
            if (position.time < Time.Zero || position.time >= track.presentationDuration) fail("INVALID_PRESENTATION_TIMESTAMP", "Position is outside the presentation domain", Stage.Plan)
            val chosen = when (position.selection) {
                Selection.Exact -> presented.indexOfFirst { Time(it.value.presentationTime, track.timescale).compareTo(position.time) == 0 }
                Selection.AtOrBefore -> {
                    val last = presented.indexOfLast { Time(it.value.presentationTime, track.timescale) <= position.time }
                    if (last < 0) -1 else presented.indexOfFirst { it.value.presentationTime == presented[last].value.presentationTime }
                }
                Selection.Nearest -> presented.indices.minWithOrNull { left, right ->
                    distance(Time(presented[left].value.presentationTime, track.timescale), position.time)
                        .compareTo(distance(Time(presented[right].value.presentationTime, track.timescale), position.time))
                } ?: -1
            }
            if (chosen < 0) fail("INVALID_PRESENTATION_TIMESTAMP", "No presentation frame satisfies selection", Stage.Plan)
            if (distance(Time(presented[chosen].value.presentationTime, track.timescale), position.time) > distance(position.tolerance, Time.Zero))
                fail("INVALID_PRESENTATION_TIMESTAMP", "Selected frame exceeds the explicit tolerance", Stage.Plan)
            chosen
        }
    }
    return SelectedFrame(track, presented[index].value, presented[index].index, index.toULong())
}

/** Fraction comparison by Euclid, without overflowing a cross-product or rounding to microseconds. */
private data class Distance(val whole: ULong, val numerator: ULong, val denominator: ULong) : Comparable<Distance> {
    override fun compareTo(other: Distance): Int {
        val integer = whole.compareTo(other.whole)
        if (integer != 0) return integer
        var a = numerator; var b = denominator; var c = other.numerator; var d = other.denominator
        var direction = 1
        while (true) {
            val comparison = (a / b).compareTo(c / d)
            if (comparison != 0) return direction * comparison
            val r = a % b; val s = c % d
            if (r == 0uL || s == 0uL) return direction * r.compareTo(s)
            a = b; b = r; c = d; d = s; direction = -direction
        }
    }
}

private fun distance(first: Time, second: Time): Distance {
    val high = if (first >= second) first else second
    val low = if (first >= second) second else first
    // Callers supply nonnegative source positions/tolerances; UInt x UInt fits UInt64.
    val denominator = high.timescale.toULong() * low.timescale.toULong()
    val highFraction = (high.value % high.timescale.toLong()).toULong() * low.timescale.toULong()
    val lowFraction = (low.value % low.timescale.toLong()).toULong() * high.timescale.toULong()
    val whole = (high.value / high.timescale.toLong() - low.value / low.timescale.toLong()).toULong()
    return if (highFraction >= lowFraction) Distance(whole, highFraction - lowFraction, denominator)
        else Distance(whole - 1uL, denominator - (lowFraction - highFraction), denominator)
}
