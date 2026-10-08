package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.*
import livephoto.core.heif.*
import livephoto.core.huawei.*
import livephoto.core.xmp.*

/** Read-only tail-bounded HEIC. The trailer is not an image box or a decoded-media guarantee. */
internal object HuaweiHeicSession {
    private val operational = setOf("CANCELLED", "SOURCE_CHANGED", "IO_READ_FAILED", "UNEXPECTED_EOF", "RESOURCE_LIMIT_EXCEEDED")

    suspend fun open(reader: BinaryReader, snapshot: Snapshot, budget: ParseBudget,
        probeVideo: Boolean): CoreResult<SourceSession?> = attempt {
        val identity = reader.identity().orThrow()
        val prefix = reader.readBuffer(0uL, minOf(16uL, identity.size).toUInt()).orThrow()
        if (prefix.size < 8 || fourCc(prefix, 4) != "ftyp") return@attempt null
        val size32 = readUnsigned(prefix.slice(0, 4), Endian.Big)
        if (size32 == 0uL) return@attempt null
        val firstLength = if (size32 == 1uL) {
            if (prefix.size < 16) fail("UNEXPECTED_EOF", "Extended HEIC ftyp header is incomplete")
            readUnsigned(prefix.slice(8, 16), Endian.Big)
        } else size32
        val parser = BmffReader(reader, budget)
        if (firstLength < if (size32 == 1uL) 16uL else 8uL) fail("CORRUPTED_CONTAINER", "HEIC ftyp length is shorter than its header")
        val first = parser.readBoxes(checkedRange(0uL, firstLength, identity.size)).orThrow().singleOrNull()
            ?: fail("CORRUPTED_CONTAINER", "HEIC ftyp envelope is not unique")
        val fileType = parser.readFileType(first).orThrow()
        if ((setOf(fileType.majorBrand) + fileType.compatibleBrands).none { it in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs") })
            return@attempt null
        val tail = HuaweiTail.read(reader, budget = budget).orThrow() ?: return@attempt null
        val primary = ByteRange(0uL, tail.candidateVideoRange.offset)
        val roots = parser.readBoxes(primary).orThrow()
        if (roots.firstOrNull()?.type != "ftyp" || roots.count { it.type == "ftyp" } != 1 || roots.count { it.type == "meta" } != 1)
            fail("CORRUPTED_CONTAINER", "Huawei HEIC requires a complete unique image envelope before the movie")
        if (roots.any { it.extendsToParentEnd })
            fail("CAPABILITY_UNSUPPORTED", "Huawei HEIC tail binding requires explicit image box lengths")
        if (roots.any { it.type in setOf("mpvd", "sefd", "moov", "moof") })
            fail("AMBIGUOUS_LAYOUT", "Huawei HEIC has a competing motion/container envelope")
        val graph = HeifItemGraphReader.read(reader, roots, budget).orThrow()
        if (graph.infos.single { it.id == graph.primary }.type !in setOf("hvc1", "hev1", "grid"))
            fail("CAPABILITY_UNSUPPORTED", "Huawei HEIC primary item type is outside the read profile")
        val metadata = HeifMetadataReader.read(reader, graph, budget).orThrow()
        val packets = metadata.items.filter { graph.primary in it.describes }.mapNotNull { it.xmp }
        if (packets.isNotEmpty() && GoogleCarrierReader.read(XmpCollection(frozenList(packets), emptyList()),
                primary, "image/heic", identity, budget).orThrow().isNotEmpty())
            fail("AMBIGUOUS_LAYOUT", "Huawei HEIC has competing image-linked motion metadata")
        val codedResult = HeifCodedItemProbe.primary(reader, graph, budget)
        val coded = (codedResult as? CoreResult.Success)?.value
        val primaryIssues = if (codedResult is CoreResult.Failure) {
            if (codedResult.error.code.value in operational) throw CoreFault(codedResult.error)
            listOf(Issue(codedResult.error.code,
                if (codedResult.error.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNSUPPORTED_CONTAINER")) Severity.Warning else Severity.Error,
                Layer.Media, codedResult.error.location ?: Location(source = identity.id, range = primary)))
        } else emptyList()
        val confirmed = HuaweiJpegReader.confirmEnvelope(reader, tail, budget)
        val coverage = Issue(IssueCode("CAPABILITY_UNSUPPORTED"), Severity.Warning, Layer.Structure,
            Location(source = identity.id, range = primary),
            observed = Value.Text("HEIC display/color, derived/auxiliary dependencies, decoding and writable ownership remain partial"))
        val binding = HuaweiJpegReader.bind(confirmed).copy(issues = confirmed.issues + coverage + primaryIssues)
        val issues = binding.issues.toMutableList()
        var video: VideoStructure? = null
        if (probeVideo && binding.video != null) {
            when (val result = BmffVideoProbe(reader, budget).probe(binding.video)) {
                is CoreResult.Success -> { video = result.value; issues += googleVideoIssues(result.value, binding.selector, false) }
                is CoreResult.Failure -> {
                    if (result.error.code.value in operational) throw CoreFault(result.error)
                    issues += Issue(result.error.code,
                        if (result.error.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNSUPPORTED_CONTAINER", "VIDEO_CODEC_NOT_SUPPORTED", "AUDIO_CODEC_NOT_SUPPORTED")) Severity.Warning else Severity.Error,
                        Layer.Media, result.error.location ?: Location(source = identity.id, range = binding.video))
                }
            }
        }
        val finalBinding = binding.copy(issues = frozenList(issues))
        val fragment = inspectHeifItems(reader, graph, budget, metadata)
        issues += fragment.issues
        val match = Match(finalBinding.selector, MatchStrength.Weak, issues = finalBinding.issues,
            resourceIds = if (binding.video == null) emptyList() else listOf(videoId(ProtocolIds.Huawei)))
        val detection = DetectionResult(Disposition.Candidate, finalBinding.selector, listOf(match), frozenList(issues), snapshot)
        val regions = mutableListOf(Region(ResourceId("primary"), identity.id, primary, ResourceKind.PrimaryImage))
        val resources = mutableListOf(Resource(ResourceId("primary"), ResourceKind.PrimaryImage, listOf(regions.single()), false))
        regions += fragment.regions; resources += fragment.resources
        binding.video?.let { range ->
            val region = Region(videoId(ProtocolIds.Huawei), identity.id, range, ResourceKind.Video, ProtocolIds.Huawei)
            regions += region; resources += Resource(region.id, region.kind, listOf(region), true)
        }
        if (binding.video == null) {
            val region = Region(ResourceId("huawei:unconfirmed-media"), identity.id, tail.candidateVideoRange, ResourceKind.Unknown)
            regions += region; resources += Resource(region.id, region.kind, listOf(region), false)
        }
        val trailer = Region(ResourceId("huawei:trailer"), identity.id, tail.tailRange, ResourceKind.Trailer, ProtocolIds.Huawei)
        regions += trailer; resources += Resource(trailer.id, trailer.kind, listOf(trailer), false)
        val owner = if (confirmed.variant == HuaweiTailVariant.Basic60) Ownership.SourceProtocol else Ownership.Unknown
        val fields = confirmed.rawFrameFields.map { field ->
            budget.item(); budget.retain(128uL)
            MetadataEntry(field.selector, value = field.rawValue, owner = owner, location = field.location, origin = FactOrigin.Parsed)
        } + MetadataEntry("huawei:tail:LIVE", value = Value.Text("LIVE_${tail.liveValue}"), owner = owner,
            location = Location(source = identity.id, range = ByteRange(tail.tailRange.offset + 40uL, 20uL)), origin = FactOrigin.Parsed)
        val media = listOf(MediaFacts(imageFormat = ImageFormat.Heic, mime = "image/heic", width = coded?.declaredWidth,
            height = coded?.declaredHeight, coverage = Coverage.Partial, issues = primaryIssues + coverage)) + listOfNotNull(video?.let(::videoFacts))
        val inspection = InspectionResult(snapshot, detection, Layout(snapshot.identities, frozenList(regions), frozenList(resources), fragment.relationships),
            media, fragment.metadata + fields, confirmed.key, issues = frozenList(issues))
        reader.validateIdentity().orThrow()
        SourceSession(listOf(reader), snapshot, null, null, listOf(finalBinding), video?.let { mapOf(ProtocolIds.Huawei to it) } ?: emptyMap(),
            inspection, huaweiTail = confirmed, heifItems = graph, heifPrimaryIssues = primaryIssues)
    }
}
