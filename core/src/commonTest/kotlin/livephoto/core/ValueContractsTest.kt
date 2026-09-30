package livephoto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValueContractsTest {
    @Test
    fun immutableBytesOwnBothInputAndReturnedStorage() {
        val input = byteArrayOf(0, 1, -1)
        val bytes = Bytes(input)
        input[0] = 99
        val copy = bytes.toByteArray()
        copy[1] = 99
        assertEquals(Bytes(byteArrayOf(0, 1, -1)), bytes)
        assertEquals(Bytes(byteArrayOf(0, 1, -1)).hashCode(), bytes.hashCode())
        assertEquals(3, bytes.size)
    }

    @Test
    fun immutableByteViewsRespectTheirOwnBoundsAndValueEquality() {
        val original = Bytes(byteArrayOf(1, 2, 3, 4, 5))
        val view = original.slice(1, 4).slice(1)
        assertEquals(Bytes(byteArrayOf(3, 4)), view)
        assertEquals(Bytes(byteArrayOf(3, 4)).hashCode(), view.hashCode())
        val returned = view.toByteArray()
        returned[0] = 99
        assertEquals(3.toByte(), view[0])
        assertFailsWith<IllegalArgumentException> { view[-1] }
        assertFailsWith<IllegalArgumentException> { view[2] }
        assertFailsWith<IllegalArgumentException> { original.slice(-1) }
        assertFailsWith<IllegalArgumentException> { original.slice(4, 3) }
        assertFailsWith<IllegalArgumentException> { original.slice(0, 6) }
        assertEquals(Bytes(byteArrayOf()), original.slice(5))
    }

    @Test
    fun byteRangesRejectOverflowAndUseHalfOpenEndpoints() {
        val range = ByteRange(ULong.MAX_VALUE - 2uL, 2uL)
        assertEquals(ULong.MAX_VALUE, range.endExclusive)
        assertTrue(ULong.MAX_VALUE - 2uL in range)
        assertTrue(ULong.MAX_VALUE - 1uL in range)
        assertFalse(ULong.MAX_VALUE in range)
        assertTrue(range.fitsWithin(ULong.MAX_VALUE))
        assertFalse(range.fitsWithin(ULong.MAX_VALUE - 1uL))
        assertFailsWith<IllegalArgumentException> { ByteRange(ULong.MAX_VALUE, 1uL) }
        assertFailsWith<IllegalArgumentException> { ByteRange(1uL, ULong.MAX_VALUE) }
    }

    @Test
    fun emptyByteRangeAtEndIsValidButHasNoMembers() {
        val range = ByteRange(ULong.MAX_VALUE, 0uL)
        assertTrue(range.fitsWithin(ULong.MAX_VALUE))
        assertFalse(range.fitsWithin(ULong.MAX_VALUE - 1uL))
        assertFalse(ULong.MAX_VALUE in range)
        assertFalse(0uL in range)
    }

    @Test
    fun rationalTimeComparisonDoesNotOverflowAtSignedLimits() {
        assertTrue(Time(Long.MIN_VALUE, 1u) < Time(Long.MIN_VALUE, UInt.MAX_VALUE))
        assertTrue(Time(Long.MAX_VALUE, 1u) > Time(Long.MAX_VALUE, UInt.MAX_VALUE))
        assertTrue(Time(Long.MIN_VALUE, UInt.MAX_VALUE) < Time(-1, UInt.MAX_VALUE))
        assertTrue(Time(Long.MAX_VALUE, UInt.MAX_VALUE) > Time(1, UInt.MAX_VALUE))
        assertTrue(Time(-1, 2u) < Time(-1, 3u))
        assertTrue(Time(-1, UInt.MAX_VALUE) < Time.Zero)
        assertEquals(0, Time(-2, 6u).compareTo(Time(-1, 3u)))
        assertEquals(0, Time(48_000, 48_000u).compareTo(Time(1, 1u)))
    }

    @Test
    fun rationalComparisonDistinguishesNumbersBeyondDoublePrecision() {
        val exactInteger = 9_007_199_254_740_992L
        assertTrue(Time(exactInteger + 1, 1u) > Time(exactInteger, 1u))
        assertTrue(Time(-exactInteger - 1, 1u) < Time(-exactInteger, 1u))
        // Independent fractions immediately below one, with cross-products near UInt64's limit.
        assertTrue(Time(4_294_967_294L, 4_294_967_295u) > Time(4_294_967_293L, 4_294_967_294u))
    }

    @Test
    fun signedRationalComparisonMatchesIndependentSmallIntegerOracle() {
        for (leftValue in -9L..9L) {
            for (rightValue in -9L..9L) {
                for (leftScale in 1u..7u) {
                    for (rightScale in 1u..7u) {
                        // This bounded oracle can cross-multiply directly without overflow.
                        val expected = (leftValue * rightScale.toLong()).compareTo(rightValue * leftScale.toLong())
                        val actual = Time(leftValue, leftScale).compareTo(Time(rightValue, rightScale))
                        assertEquals(expected, actual, "$leftValue/$leftScale versus $rightValue/$rightScale")
                    }
                }
            }
        }
    }

    @Test
    fun rationalRangesAreHalfOpenAcrossTimescales() {
        val range = TimeRange(Time(1, 3u), Time(2, 3u))
        assertTrue(Time(2, 6u) in range)
        assertTrue(Time(1, 2u) in range)
        assertFalse(Time(4, 6u) in range)
        assertFalse(Time(1, 4u) in range)
        assertFalse(Time.Zero in TimeRange(Time.Zero, Time.Zero))
        assertFailsWith<IllegalArgumentException> { TimeRange(Time(2, 1u), Time(1, 1u)) }
    }

    @Test
    fun invalidTimescalesRatesAndToleranceAreRejected() {
        assertFailsWith<IllegalArgumentException> { Time(1, 0u) }
        assertFailsWith<IllegalArgumentException> { RationalRate(0u, 1u) }
        assertFailsWith<IllegalArgumentException> { RationalRate(1u, 0u) }
        assertFailsWith<IllegalArgumentException> {
            CoverPosition.Timestamp(Time.Zero, tolerance = Time(-1, 1000u))
        }
        val timestamp = CoverPosition.Timestamp(Time.Zero)
        assertEquals(Selection.AtOrBefore, timestamp.selection)
        assertEquals(Time.Zero, timestamp.tolerance)
        assertEquals(0uL, CoverPosition.FrameIndex(0uL).index)
    }

    @Test
    fun exactJsonNumberSpellingRetainsPrecisionAndRejectsNonJsonNumbers() {
        val integer = "18446744073709551615"
        assertEquals(integer, Value.Number(integer).decimal)
        assertEquals("-1.250e+12", Value.Number("-1.250e+12").decimal)
        for (invalid in listOf("NaN", "Infinity", "01", "+1", "1.", ".5", " 1", "1\n")) {
            assertFailsWith<IllegalArgumentException>(invalid) { Value.Number(invalid) }
        }
    }

    @Test
    fun snapshotRejectsAmbiguousDuplicateSourceIdentities() {
        val id = SourceId("input")
        val first = SourceIdentity(id, GenerationToken("first"), 10uL)
        val changed = SourceIdentity(id, GenerationToken("changed"), 11uL)
        assertFailsWith<IllegalArgumentException> {
            Snapshot(listOf(first, changed), GenerationToken("snapshot"))
        }
    }

    @Test
    fun snapshotFactsCannotBeChangedThroughCallerOrReturnedLists() {
        val first = SourceIdentity(SourceId("input"), GenerationToken("generation"), 10uL)
        val supplied = mutableListOf(first)
        val snapshot = Snapshot(supplied, GenerationToken("snapshot"))
        supplied.clear()
        assertEquals(listOf(first), snapshot.identities)
        try {
            (snapshot.identities as? MutableList<SourceIdentity>)?.clear()
        } catch (_: UnsupportedOperationException) {
            // An immutable view is as valid as a defensive copy.
        }
        assertEquals(listOf(first), snapshot.identities)
    }

    @Test
    fun defaultDiagnosticsRedactGenerationAndReceiptTokens() {
        val secret = "private-token-123"
        assertFalse(GenerationToken(secret).toString().contains(secret))
        val receipt = Receipt(secret, TransactionState.Indeterminate, emptyList(), Atomicity.AssetSetRequired, "unknown")
        assertFalse(receipt.toString().contains(secret))
    }
}
