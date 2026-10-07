package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

/** Proves only bufferSizeDB/maxBitrate/avgBitrate differ; never changes ASC or ES identity. */
internal object AacDescriptorHints {
    fun patchRange(before: Bytes, after: Bytes, budget: ParseBudget): CoreResult<ByteRange> = attemptNow {
        val left = validateEsds(before, budget); val right = validateEsds(after, budget)
        if (left.esDescriptorFlags != 0 || right.esDescriptorFlags != 0)
            fail("CAPABILITY_UNSUPPORTED", "Referenced/URL/OCR ES descriptors have no remux hint restoration profile", Stage.Plan)
        val start = left.descriptorHintsOffset
        if (before.size != after.size || start != right.descriptorHintsOffset ||
            (0 until before.size).any { budget.poll(); it !in start until start + 11 && before[it] != after[it] })
            fail("POSTCONDITION_FAILED", "AAC remux changed configuration outside the parsed descriptor hint fields", Stage.Verify)
        ByteRange(start.toULong(), 11uL)
    }
}
