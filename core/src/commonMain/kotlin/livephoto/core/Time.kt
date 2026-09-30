package livephoto.core

/** Exact rational presentation time; no floating-point conversion is used for comparison. */
public data class Time(public val value: Long, public val timescale: UInt) : Comparable<Time> {
    init { require(timescale > 0u) { "Time timescale must be positive" } }

    override fun compareTo(other: Time): Int {
        val divisor = timescale.toLong()
        val otherDivisor = other.timescale.toLong()
        val quotient = value / divisor
        val remainder = value % divisor
        val otherQuotient = other.value / otherDivisor
        val otherRemainder = other.value % otherDivisor
        val floor = quotient - if (remainder < 0) 1 else 0
        val otherFloor = otherQuotient - if (otherRemainder < 0) 1 else 0
        val integerComparison = floor.compareTo(otherFloor)
        if (integerComparison != 0) return integerComparison
        val positiveRemainder = if (remainder < 0) remainder + divisor else remainder
        val otherPositiveRemainder = if (otherRemainder < 0) otherRemainder + otherDivisor else otherRemainder
        // Each factor is at most UInt.MAX_VALUE, so both products fit in ULong.
        return (positiveRemainder.toULong() * other.timescale.toULong())
            .compareTo(otherPositiveRemainder.toULong() * timescale.toULong())
    }

    public companion object {
        public val Zero: Time = Time(0, 1u)
    }
}

public typealias RationalTime = Time

public data class RationalRate(public val numerator: UInt, public val denominator: UInt) {
    init { require(numerator > 0u && denominator > 0u) { "Rate components must be positive" } }
}

/** Half-open presentation interval [start, end). Empty intervals are representable. */
public data class TimeRange(public val start: Time, public val end: Time) {
    init { require(start <= end) { "Time range is reversed" } }
    public operator fun contains(time: Time): Boolean = time >= start && time < end
}

public data class TimelineSegment(public val input: TimeRange, public val output: TimeRange)

/** A half-open byte range whose end is representable in the UInt64 domain. */
public data class ByteRange(public val offset: ULong, public val length: ULong) {
    init { require(length <= ULong.MAX_VALUE - offset) { "Byte range overflows UInt64" } }
    public val endExclusive: ULong get() = offset + length
    public fun fitsWithin(size: ULong): Boolean = offset <= size && length <= size - offset
    public operator fun contains(position: ULong): Boolean = position >= offset && position < endExclusive
}

public sealed interface CoverPosition {
    public data class Timestamp(
        public val time: Time,
        public val selection: Selection = Selection.AtOrBefore,
        public val tolerance: Time = Time.Zero,
    ) : CoverPosition {
        init { require(tolerance.value >= 0) { "Tolerance must be nonnegative" } }
    }

    /** Zero-based presentation order after edits; never decode order or an average-FPS estimate. */
    public data class FrameIndex(public val index: ULong, public val trackId: TrackId? = null) : CoverPosition
}
