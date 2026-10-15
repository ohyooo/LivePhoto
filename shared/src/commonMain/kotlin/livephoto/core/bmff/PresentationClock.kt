package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

/** Exact edited PTS clock. Decode times and sample deltas remain in the media clock. */
internal class PresentationClock private constructor(
    val timescale: UInt,
    private val factor: Long,
    private val emptyTicks: Long,
    private val mediaStart: Long,
) {
    fun map(mediaPts: Long): Long {
        if (mediaPts < Long.MIN_VALUE + mediaStart) fail("INTEGER_OVERFLOW", "Edited presentation time underflows")
        val shifted = mediaPts - mediaStart
        if (shifted > Long.MAX_VALUE / factor || shifted < Long.MIN_VALUE / factor)
            fail("INTEGER_OVERFLOW", "Exact edited presentation clock overflows")
        val scaled = shifted * factor
        if (scaled > Long.MAX_VALUE - emptyTicks) fail("INTEGER_OVERFLOW", "Edited presentation time overflows")
        return scaled + emptyTicks
    }

    /** Inverse for decoder selection: never round an edited PTS to a media tick. */
    fun mediaTime(pts: Long): Long {
        if (pts < Long.MIN_VALUE + emptyTicks) fail("INTEGER_OVERFLOW", "Frame media time underflows", Stage.Plan)
        val shifted = pts - emptyTicks
        if (shifted % factor != 0L) fail("VALUE_NOT_REPRESENTABLE", "Frame does not map to an exact media tick", Stage.Plan)
        val media = shifted / factor
        if (media > Long.MAX_VALUE - mediaStart) fail("INTEGER_OVERFLOW", "Frame media time overflows", Stage.Plan)
        return media + mediaStart
    }

    companion object {
        fun create(mediaScale: UInt, edit: VideoEdit?): PresentationClock {
            if (edit == null) return PresentationClock(mediaScale, 1, 0, 0)
            // Only enlarge the media clock as much as this particular empty edit
            // requires. A full movie/media LCM can unnecessarily exceed UInt32.
            val divisor = edit.movieTimescale.toULong() / gcd(edit.movieTimescale.toULong(), mediaScale.toULong())
            val factor = divisor / gcd(edit.emptyDuration % divisor, divisor)
            val scale = checkedMultiply(mediaScale.toULong(), factor)
            if (scale > UInt.MAX_VALUE.toULong())
                fail("VALUE_NOT_REPRESENTABLE", "Exact edited presentation timescale exceeds UInt32")
            val common = gcd(edit.movieTimescale.toULong(), scale)
            val empty = checkedMultiply(edit.emptyDuration / (edit.movieTimescale.toULong() / common), scale / common)
            if (empty > Long.MAX_VALUE.toULong()) fail("INTEGER_OVERFLOW", "Empty edit exceeds the signed presentation clock")
            return PresentationClock(scale.toUInt(), factor.toLong(), empty.toLong(), edit.mediaStart)
        }

        private fun gcd(left: ULong, right: ULong): ULong {
            var a = left; var b = right
            while (b != 0uL) { val rest = a % b; a = b; b = rest }
            return a
        }
    }
}
