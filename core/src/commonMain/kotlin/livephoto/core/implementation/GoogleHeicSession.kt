package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.*
import livephoto.core.heif.*
import livephoto.core.samsung.*
import livephoto.core.xmp.*

/** Read-only HEIF binding. Only MIME XMP items explicitly describing the primary are authorities. */
internal object GoogleHeicSession {
    private val operational = setOf("CANCELLED", "SOURCE_CHANGED", "IO_READ_FAILED", "UNEXPECTED_EOF", "RESOURCE_LIMIT_EXCEEDED")

    suspend fun open(reader: BinaryReader, snapshot: Snapshot, budget: ParseBudget,
        probeVideo: Boolean): CoreResult<SourceSession?> = attempt {
        val identity = reader.identity().orThrow()
        val roots = BmffReader(reader, budget).readBoxes(ByteRange(0uL, identity.size)).orThrow()
        if (roots.none { it.type == "meta" }) return@attempt null
        val graphResult = HeifItemGraphReader.read(reader, roots, budget)
        val graph = when (graphResult) {
            is CoreResult.Success -> graphResult.value
            is CoreResult.Failure -> {
                if (graphResult.error.code.value in operational) throw CoreFault(graphResult.error)
                // Samsung's independently bounded SEF can remain readable when image tables are not.
                return@attempt null
            }
        }
        if (graph.infos.single { it.id == graph.primary }.type !in setOf("hvc1", "hev1")) return@attempt null
        val metadata = HeifMetadataReader.read(reader, graph, budget).orThrow()
        val packets = metadata.items.filter { graph.primary in it.describes }.mapNotNull { it.xmp }
        if (packets.isEmpty()) return@attempt null
        val xmp = XmpCollection(frozenList(packets), emptyList())
        val mpvds = roots.filter { it.type == "mpvd" }
        val mpvd = mpvds.singleOrNull()
        val primary = ByteRange(0uL, mpvd?.range?.offset ?: identity.size)
        if (primary.length == 0uL) return@attempt null
        val google = GoogleCarrierReader.read(xmp, primary, "image/heic", identity, budget).orThrow()
        if (google.isEmpty()) return@attempt null
        fun issue(code: String, layer: Layer = Layer.Protocol, range: ByteRange? = mpvd?.range,
            warning: Boolean = false) = Issue(IssueCode(code), if (warning) Severity.Warning else Severity.Error,
                layer, Location(source = identity.id, range = range))
        val framing = mutableListOf<Issue>()
        when {
            mpvds.isEmpty() -> framing += issue("MOTION_VIDEO_MISSING")
            mpvd == null -> framing += issue("AMBIGUOUS_LAYOUT")
            mpvd.extendsToParentEnd -> framing += issue("CORRUPTED_CONTAINER")
            mpvd.headerLength != 8uL -> framing += issue("CAPABILITY_UNSUPPORTED", warning = true)
            mpvd.range.endExclusive != identity.size -> framing += issue("MOTION_VIDEO_LENGTH_MISMATCH")
            mpvd.payload.length == 0uL -> framing += issue("MOTION_VIDEO_MISSING")
        }
        if (mpvd != null && graph.locations.items.any { item -> item.extents.any { it.data.endExclusive > primary.endExclusive } })
            framing += issue("OFFSET_OUT_OF_BOUNDS", Layer.Structure)
        val physicalVideo = mpvd?.payload?.takeIf { framing.isEmpty() }
        val primaryResult = HeifCodedItemProbe.primary(reader, graph, budget)
        val coded = (primaryResult as? CoreResult.Success)?.value
        val primaryIssues = if (primaryResult is CoreResult.Failure) {
            if (primaryResult.error.code.value in operational) throw CoreFault(primaryResult.error)
            listOf(issue(primaryResult.error.code.value, Layer.Media, primaryResult.error.location?.range,
                primaryResult.error.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNSUPPORTED_CONTAINER")))
        } else emptyList()
        val coverageIssue = Issue(IssueCode("CAPABILITY_UNSUPPORTED"), Severity.Warning, Layer.Structure,
            Location(source = identity.id, range = roots.single { it.type == "meta" }.range),
            observed = Value.Text("HEIF derived/display/color interpretation, preservation and decode checks remain partial"))
        var bindings = google.map { binding ->
            val mismatch = if (binding.video != null && binding.video != physicalVideo) listOf(issue("MOTION_VIDEO_LENGTH_MISMATCH")) else emptyList()
            // The unique standard final mpvd is raw-resource authority, not a repair of invalid XMP.
            binding.copy(video = physicalVideo, issues = binding.issues + framing + mismatch + coverageIssue + primaryIssues)
        }
        val samsungResult = SamsungHeicReader.read(reader, budget)
        val samsung = (samsungResult as? CoreResult.Success)?.value
        val issues = mutableListOf<Issue>()
        if (samsungResult is CoreResult.Failure) {
            if (samsungResult.error.code.value in operational) throw CoreFault(samsungResult.error)
            issues += issue(samsungResult.error.code.value, Layer.Structure, samsungResult.error.location?.range,
                samsungResult.error.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNSUPPORTED_CONTAINER", "UNKNOWN_PROTOCOL_VARIANT"))
        }
        if (samsung != null) bindings = bindings + samsung.binding
        val videos = linkedMapOf<ProtocolId, VideoStructure>()
        val probes = linkedMapOf<ByteRange, CoreResult<VideoStructure>>()
        if (probeVideo) for (binding in bindings) {
            val range = binding.video ?: continue
            val result = probes[range] ?: BmffVideoProbe(reader, budget).probe(range).also { probes[range] = it }
            when (result) {
                is CoreResult.Success -> videos[binding.protocol] = result.value
                is CoreResult.Failure -> {
                    if (result.error.code.value in operational) throw CoreFault(result.error)
                    issues += issue(result.error.code.value, Layer.Media, result.error.location?.range ?: range,
                        result.error.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNSUPPORTED_CONTAINER", "VIDEO_CODEC_NOT_SUPPORTED", "AUDIO_CODEC_NOT_SUPPORTED"))
                }
            }
        }
        bindings = bindings.map { binding -> binding.copy(issues = binding.issues +
            (videos[binding.protocol]?.let { googleVideoIssues(it, binding.selector, false) } ?: emptyList())) }
        issues += bindings.flatMap { it.issues }
        val fragment = inspectHeifItems(reader, graph, budget, metadata)
        issues += fragment.issues
        val matches = bindings.map { binding -> Match(binding.selector,
            if (binding.structurallyValid && binding.protocol in videos && binding.issues.none { it.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNSUPPORTED_CONTAINER", "UNKNOWN_PROTOCOL_VARIANT") }) MatchStrength.Strong else MatchStrength.Weak,
            issues = binding.issues, resourceIds = if (binding.video == null) emptyList() else listOf(videoId(binding.protocol))) }
        val strong = matches.filter { it.strength == MatchStrength.Strong }
        val conflicting = strong.mapNotNull { match -> bindings.single { it.selector == match.target }.video }.distinct().size > 1
        val detection = DetectionResult(if (conflicting) Disposition.Ambiguous else if (strong.isNotEmpty()) Disposition.Live else Disposition.Candidate,
            if (conflicting) null else strong.singleOrNull()?.target ?: matches.singleOrNull()?.target, frozenList(matches), frozenList(issues.distinct()), snapshot)
        val regions = mutableListOf(Region(ResourceId("primary"), identity.id, primary, ResourceKind.PrimaryImage))
        val resources = mutableListOf(Resource(ResourceId("primary"), ResourceKind.PrimaryImage, listOf(regions.single()), false))
        regions += fragment.regions; resources += fragment.resources
        for (binding in bindings) {
            budget.item(); budget.retain(192uL)
            binding.video?.let { range ->
                val region = Region(videoId(binding.protocol), identity.id, range, ResourceKind.Video, binding.protocol)
                val aliases = bindings.filter { it.protocol != binding.protocol && it.video == range }.map { videoId(it.protocol) }
                regions += region; resources += Resource(region.id, region.kind, listOf(region), true, frozenList(aliases))
            }
            binding.padding?.let { regions += Region(ResourceId("${binding.protocol.value}:padding"), identity.id, it, ResourceKind.Padding, binding.protocol) }
        }
        samsung?.directory?.records?.forEachIndexed { index, record ->
            budget.item(); budget.retain(192uL)
            val region = Region(ResourceId("samsung:sef:record:$index"), identity.id, record.range, ResourceKind.Trailer,
                if (record.type in setOf(0x0a30.toUShort(), 0x0a31.toUShort())) ProtocolIds.Samsung else null)
            regions += region; resources += Resource(region.id, region.kind, listOf(region), false)
        }
        val media = listOf(MediaFacts(imageFormat = ImageFormat.Heic, mime = "image/heic", width = coded?.declaredWidth,
            height = coded?.declaredHeight, coverage = Coverage.Partial, issues = primaryIssues + coverageIssue)) + videos.values.distinctBy { it.range }.map(::videoFacts)
        val key = detection.primaryProtocol?.let { selected -> bindings.single { it.selector == selected }.key } ?: KeyPhotoResult()
        val inspection = InspectionResult(snapshot, detection, Layout(snapshot.identities, frozenList(regions), frozenList(resources), fragment.relationships),
            frozenList(media), fragment.metadata, key, issues = frozenList(issues.distinct()))
        reader.validateIdentity().orThrow()
        SourceSession(listOf(reader), snapshot, null, xmp, frozenList(bindings), videos, inspection,
            sef = samsung?.directory, heifItems = graph, heifPrimaryIssues = primaryIssues)
    }
}
