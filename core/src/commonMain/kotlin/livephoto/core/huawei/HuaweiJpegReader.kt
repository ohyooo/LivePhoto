package livephoto.core.huawei

import livephoto.core.*
import livephoto.core.implementation.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

internal object HuaweiJpegReader {
    suspend fun confirmEnvelope(reader: BinaryReader, facts: HuaweiTailFacts, budget: ParseBudget): HuaweiTailFacts {
        val range = facts.videoRange ?: return facts
        try { if (basicMediaEnvelope(reader, range, budget)) return facts }
        catch (fault: CoreFault) {
            if (fault.error.code.value in setOf("CANCELLED", "SOURCE_CHANGED", "IO_READ_FAILED", "RESOURCE_LIMIT_EXCEEDED", "UNEXPECTED_EOF")) throw fault
            return facts.copy(videoRange = null, issues = facts.issues + Issue(fault.error.code, Severity.Error, Layer.Media,
                fault.error.location ?: Location(source = facts.sourceIdentity.id, range = range)))
        }
        return facts.copy(videoRange = null, variant = HuaweiTailVariant.Unknown,
            issues = facts.issues + Issue(IssueCode("UNKNOWN_PROTOCOL_VARIANT"), Severity.Warning, Layer.Protocol,
                Location(source = facts.sourceIdentity.id, range = facts.candidateVideoRange, selector = "huawei:unknown-media-extension")))
    }

    fun bind(facts: HuaweiTailFacts): CarrierBinding = CarrierBinding(ProtocolIds.Huawei, facts.videoRange,
        key = facts.key, issues = facts.issues, profile = ProfileId(if (facts.variant == HuaweiTailVariant.HonorExtended) "honor-extended" else "basic60"),
        trailer = facts.tailRange)
}

/** Unknown boxes remain within the original candidate extent; no moov-end truncation is attempted. */
internal suspend fun basicMediaEnvelope(reader: BinaryReader, range: ByteRange, budget: ParseBudget): Boolean {
    val boxes = BmffReader(reader, budget).readBoxes(range).orThrow()
    return boxes.all { it.type in setOf("ftyp", "moov", "mdat", "free", "skip", "wide") }
}
