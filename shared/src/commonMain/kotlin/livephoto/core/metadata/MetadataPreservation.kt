package livephoto.core.metadata

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.jpeg.*

internal enum class MetadataRule { MustPreserve, MustDelete, MayRewrite, Unknown, CannotGuarantee }
internal enum class DependencySafety { Independent, OpaqueOffsets, GraphDependent, ExtendedPacket, Unknown }
internal data class MetadataBlock(
    val range: ByteRange,
    val kind: ResourceKind,
    val owner: Ownership,
    val rule: MetadataRule,
    val dependency: DependencySafety,
)
internal data class MetadataInventory(val blocks: List<MetadataBlock>)

/** Planning facts only: preserving bytes does not prove private offsets or resource relationships. */
internal object MetadataPreservation {
    fun jpeg(structure: JpegStructure, context: Context): CoreResult<MetadataInventory> = attemptNow {
        val budget = ParseBudget(context)
        val blocks = mutableListOf<MetadataBlock>()
        for (segment in structure.segments) {
            if (segment.marker !in 0xe0..0xef && segment.marker != 0xfe) continue
            budget.item(); budget.retain(64uL)
            val (kind, dependency) = when (segment.payloadKind) {
                AppPayloadKind.Exif -> ResourceKind.Exif to DependencySafety.OpaqueOffsets
                AppPayloadKind.Xmp -> ResourceKind.Xmp to DependencySafety.Unknown
                AppPayloadKind.ExtendedXmp -> ResourceKind.Xmp to DependencySafety.ExtendedPacket
                AppPayloadKind.Icc -> ResourceKind.Icc to DependencySafety.Independent
                AppPayloadKind.Mpf -> ResourceKind.VendorMetadata to DependencySafety.GraphDependent
                AppPayloadKind.Unknown -> ResourceKind.Unknown to if (segment.marker == 0xfe) DependencySafety.Independent else DependencySafety.Unknown
            }
            val owner = when (segment.payloadKind) {
                AppPayloadKind.Exif, AppPayloadKind.Icc -> Ownership.Ordinary
                AppPayloadKind.Mpf -> Ownership.StandardImage
                else -> if (segment.marker == 0xfe) Ownership.Ordinary else Ownership.Unknown
            }
            // A mixed normal XMP packet may be merged by expanded name; never delete it wholesale.
            val rule = if (segment.payloadKind == AppPayloadKind.Xmp) MetadataRule.MayRewrite else MetadataRule.MustPreserve
            blocks += MetadataBlock(segment.range, kind, owner, rule, dependency)
        }
        if (structure.trailing.length != 0uL) {
            budget.item(); budget.retain(64uL)
            blocks += MetadataBlock(structure.trailing, ResourceKind.Unknown, Ownership.Unknown, MetadataRule.Unknown, DependencySafety.Unknown)
        }
        MetadataInventory(frozenList(blocks))
    }

    /** Planning defaults, never permission to edit beyond the request or a verified field schema. */
    fun fieldRule(entry: MetadataEntry, operation: Operation, verifiedMutableSelectors: Set<String> = emptySet()): MetadataRule {
        if (operation in setOf(Operation.ExtractRaw, Operation.Detect, Operation.Analyze, Operation.Inspect, Operation.Validate, Operation.GetKey, Operation.Probe)) return MetadataRule.MustPreserve
        return when (entry.owner) {
        Ownership.Ordinary, Ownership.StandardImage -> MetadataRule.MustPreserve
        Ownership.Unknown -> MetadataRule.Unknown
        Ownership.TargetProtocol -> if (operation == Operation.SplitClean) MetadataRule.MustDelete else MetadataRule.MayRewrite
        Ownership.SourceProtocol -> when (operation) {
            Operation.SplitClean, Operation.ConvertFrom, Operation.ConvertTo -> MetadataRule.MustDelete
            Operation.Repair, Operation.SetKey -> MetadataRule.MayRewrite
            Operation.ReplaceCover -> if (entry.selector in verifiedMutableSelectors) MetadataRule.MayRewrite else MetadataRule.MustPreserve
            else -> MetadataRule.MustPreserve
        }
        }
    }
}
