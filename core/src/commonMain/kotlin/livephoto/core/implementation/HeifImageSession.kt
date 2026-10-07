package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.heif.*

/** Ordinary HEIC inspection is independent of any motion protocol and requires an actual item graph. */
internal object HeifImageSession {
    suspend fun open(reader: BinaryReader, snapshot: Snapshot, budget: ParseBudget): CoreResult<SourceSession?> = attempt {
        val identity = reader.identity().orThrow()
        val roots = BmffReader(reader, budget).readBoxes(ByteRange(0uL, identity.size)).orThrow()
        if (roots.none { it.type == "meta" }) return@attempt null
        val graph = HeifItemGraphReader.read(reader, roots, budget).orThrow()
        val result = HeifCodedItemProbe.primary(reader, graph, budget)
        val image = when (result) {
            is CoreResult.Success -> result.value
            is CoreResult.Failure -> {
                if (result.error.code.value in setOf("CANCELLED", "SOURCE_CHANGED", "IO_READ_FAILED", "RESOURCE_LIMIT_EXCEEDED", "UNEXPECTED_EOF")) throw CoreFault(result.error)
                null
            }
        }
        val issues = mutableListOf(Issue(IssueCode("CAPABILITY_UNSUPPORTED"), Severity.Warning, Layer.Media,
            Location(source = identity.id), observed = Value.Text("HEIF SPS, display/color interpretation and decoded media checks have not run")))
        val primaryIssues = if (result is CoreResult.Failure) listOf(Issue(result.error.code,
            if (result.error.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNSUPPORTED_CONTAINER")) Severity.Warning else Severity.Error,
            Layer.Media, result.error.location ?: Location(source = identity.id))) else emptyList()
        issues += primaryIssues
        val plain = image != null && graph.infos.size == 1 && graph.infos.single().type == "hvc1" &&
            graph.unknownMeta.isEmpty() && graph.unknownPropertyContainers.isEmpty() && graph.references.isEmpty() &&
            graph.properties.all { it.type in setOf("ispe", "hvcC") } && roots.all { it.type in setOf("ftyp", "meta", "mdat") }
        if (!plain) issues += Issue(IssueCode("CAPABILITY_UNSUPPORTED"), Severity.Warning, Layer.Structure,
            Location(source = identity.id), observed = Value.Text("Private metadata, derived/auxiliary items or other container dependencies remain unclassified"))
        val primaryType = graph.infos.single { it.id == graph.primary }.type
        val format = when (primaryType) { "hvc1", "hev1" -> ImageFormat.Heic; "av01" -> ImageFormat.Avif; else -> ImageFormat.HeifOther }
        val mime = when (format) { ImageFormat.Heic -> "image/heic"; ImageFormat.Avif -> "image/avif"; else -> "image/heif" }
        val fragment = inspectHeifItems(reader, graph, budget)
        issues += fragment.issues
        val detection = DetectionResult(if (plain) Disposition.NonLive else Disposition.Unknown, matches = emptyList(), issues = frozenList(issues), snapshot = snapshot)
        val facts = MediaFacts(imageFormat = format, mime = mime, width = image?.declaredWidth, height = image?.declaredHeight, coverage = Coverage.Partial, issues = frozenList(issues))
        val inspection = InspectionResult(snapshot, detection, Layout(snapshot.identities, fragment.regions, fragment.resources, fragment.relationships),
            listOf(facts), fragment.metadata, KeyPhotoResult(), issues = frozenList(issues))
        reader.validateIdentity().orThrow()
        SourceSession(listOf(reader), snapshot, null, null, emptyList(), emptyMap(), inspection, heifItems = graph, heifPrimaryIssues = primaryIssues)
    }
}
