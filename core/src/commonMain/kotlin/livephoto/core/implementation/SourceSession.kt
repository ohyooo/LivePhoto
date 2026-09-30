package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.*
import livephoto.core.jpeg.*
import livephoto.core.xmp.*
import livephoto.core.xml.*

/** One operation's immutable, content-derived facts; borrowed input handles remain open. */
internal class SourceSession private constructor(
    val readers: List<BinaryReader>,
    val snapshot: Snapshot,
    val jpeg: JpegStructure?,
    val xmp: XmpCollection?,
    val bindings: List<GoogleBinding>,
    val videos: Map<ProtocolId, VideoStructure>,
    val inspection: InspectionResult,
) {
    val reader: BinaryReader get() = readers.single()
    suspend fun recheck(): Unit { for (reader in readers) reader.validateIdentity().orThrow() }

    companion object {
        suspend fun open(input: SourceSet, context: Context, budget: ParseBudget): CoreResult<SourceSession> = attempt {
            val sources = when (input) {
                is SourceSet.Single -> listOf(input.source)
                is SourceSet.Pair -> listOf(input.image, input.video)
                is SourceSet.Candidates -> input.sources
            }
            if (sources.size.toUInt() > context.limits.maxSources) fail("RESOURCE_LIMIT_EXCEEDED", "Source count exceeds operation limit")
            val readers = sources.map { BinaryReader(it, context) }
            val identities = readers.map { it.identity().orThrow() }
            if (identities.map { it.id }.distinct().size != identities.size) fail("INVALID_ARGUMENT", "Source IDs must be unique")
            val hash = Sha256()
            for (identity in identities) for (field in listOf(identity.id.value, identity.generation.value, identity.size.toString(), identity.digest?.value ?: "")) {
                val bytes = Bytes(field.encodeToByteArray())
                hash.update(unsignedBytes(bytes.size.toULong(), 8, Endian.Big)); hash.update(bytes)
            }
            val snapshot = Snapshot(identities, GenerationToken(hash.finish().value))
            if (readers.size != 1) fail("CAPABILITY_UNSUPPORTED", "Pair and candidate protocol orchestration is not implemented", Stage.Inspect)
            val reader = readers.single()
            val identity = identities.single()
            val content = detectContent(reader).orThrow()
            if (content.kind != ContentKind.Jpeg) {
                val detection = DetectionResult(Disposition.Unknown, matches = emptyList(), snapshot = snapshot)
                val inspection = InspectionResult(snapshot, detection, Layout(identities, emptyList(), emptyList()), emptyList(), emptyList(), KeyPhotoResult())
                return@attempt SourceSession(readers, snapshot, null, null, emptyList(), emptyMap(), inspection)
            }
            val jpeg = JpegParser.parse(reader, budget).orThrow()
            val xmp = XmpReader.readJpeg(reader, jpeg, budget).orThrow()
            val imageFacts = jpegFacts(reader, jpeg)
            val bindings = GoogleJpegReader.read(xmp, jpeg, identity, budget).orThrow()
            val videos = linkedMapOf<ProtocolId, VideoStructure>()
            val videoCache = linkedMapOf<ByteRange, CoreResult<VideoStructure>>()
            val issues = mutableListOf<Issue>()
            issues += xmp.issues
            issues += imageFacts.issues
            for (binding in bindings) {
                issues += binding.issues
                val range = binding.video ?: continue
                val probe = videoCache[range] ?: BmffVideoProbe(reader, budget).probe(range).also { videoCache[range] = it }
                when (probe) {
                    is CoreResult.Success -> videos[binding.protocol] = probe.value
                    is CoreResult.Failure -> {
                        if (probe.error.code.value in setOf("CANCELLED", "RESOURCE_LIMIT_EXCEEDED", "SOURCE_CHANGED", "IO_READ_FAILED", "UNEXPECTED_EOF")) throw CoreFault(probe.error)
                        issues += Issue(probe.error.code, if (probe.error.code.value in setOf("UNSUPPORTED_CONTAINER", "VIDEO_CODEC_NOT_SUPPORTED", "AUDIO_CODEC_NOT_SUPPORTED", "CAPABILITY_UNSUPPORTED")) Severity.Warning else Severity.Error,
                            Layer.Media, probe.error.location ?: Location(source = identity.id, range = binding.video))
                    }
                }
            }
            val matches = bindings.map { binding -> Match(binding.selector,
                if (binding.protocol in videos && binding.structurallyValid) MatchStrength.Strong else MatchStrength.Weak,
                issues = binding.issues, resourceIds = if (binding.video != null) listOf(videoId(binding.protocol)) else emptyList()) }
            val strong = matches.filter { it.strength == MatchStrength.Strong }
            val conflicting = strong.mapNotNull { match -> bindings.first { it.selector == match.target }.video }.distinct().size > 1
            val disposition = when {
                conflicting -> Disposition.Ambiguous
                strong.isNotEmpty() -> Disposition.Live
                matches.isNotEmpty() -> Disposition.Candidate
                else -> Disposition.NonLive
            }
            val primary = if (conflicting) null else (strong.firstOrNull { it.target.protocol == ProtocolIds.GoogleV2 } ?: strong.firstOrNull() ?: matches.singleOrNull())?.target
            val detection = DetectionResult(disposition, primary, frozenList(matches), frozenList(issues), snapshot)
            val regions = mutableListOf(Region(ResourceId("primary"), identity.id, jpeg.primary, ResourceKind.PrimaryImage))
            val resources = mutableListOf(Resource(ResourceId("primary"), ResourceKind.PrimaryImage, listOf(regions.first()), true))
            for (binding in bindings) {
                binding.video?.let { range ->
                    val region = Region(videoId(binding.protocol), identity.id, range, ResourceKind.Video, binding.protocol)
                    regions += region; resources += Resource(region.id, region.kind, listOf(region), true)
                }
                binding.padding?.let { regions += Region(ResourceId("${binding.protocol.value}:padding"), identity.id, it, ResourceKind.Padding, binding.protocol) }
                for ((index, item) in binding.items.withIndex()) if (index > 0 && index < binding.items.lastIndex) {
                    val kind = if (item.semantic == "GainMap") ResourceKind.GainMap else ResourceKind.Unknown
                    val region = Region(ResourceId("${binding.protocol.value}:aux:$index"), identity.id, item.range, kind, binding.protocol)
                    regions += region; resources += Resource(region.id, region.kind, listOf(region), true)
                }
            }
            val media = listOf(imageFacts) + videos.values.map(::videoFacts)
            val metadata = mutableListOf<MetadataEntry>()
            for ((index, segment) in jpeg.segments.withIndex()) {
                if (segment.marker !in 0xe0..0xef && segment.marker != 0xfe) continue
                val kind = when (segment.payloadKind) { AppPayloadKind.Xmp, AppPayloadKind.ExtendedXmp -> ResourceKind.Xmp; AppPayloadKind.Exif -> ResourceKind.Exif; AppPayloadKind.Icc -> ResourceKind.Icc; else -> ResourceKind.Unknown }
                val region = Region(ResourceId("metadata:$index"), identity.id, segment.range, kind)
                regions += region; resources += Resource(region.id, kind, listOf(region), false)
                metadata += MetadataEntry("jpeg:marker:${segment.marker}:$index", raw = region.id,
                    owner = if (kind == ResourceKind.Unknown) Ownership.Unknown else Ownership.StandardImage,
                    location = Location(source = identity.id, range = segment.range, resource = region.id), origin = FactOrigin.Parsed)
            }
            fun propertyMetadata(element: XmlElement, authoritative: Boolean, depth: UInt = 0u) {
                budget.item(depth)
                for (attribute in element.attributes) if (attribute.name.expanded.uri != RDF_URI) {
                    budget.item(depth)
                    val name = attribute.name.expanded
                    val owned = authoritative && name.uri == CAMERA_URI && bindings.any { name.local in if (it.protocol == ProtocolIds.GoogleV1) V1_FIELDS else V2_FIELDS }
                    val selector = "{${name.uri}}${name.local}"
                    budget.retain(96uL + selector.length.toULong() * 4uL)
                    metadata += MetadataEntry(selector, value = Value.Text(attribute.value), owner = if (owned) Ownership.SourceProtocol else Ownership.Ordinary,
                        location = Location(source = identity.id, selector = selector), origin = FactOrigin.Parsed)
                }
                for (child in element.children.filterIsInstance<XmlElement>()) {
                    budget.item(depth)
                    val name = child.name.expanded
                    val selector = "{${name.uri}}${name.local}"
                    budget.retain(96uL + selector.length.toULong() * 4uL)
                    if (name.uri != RDF_URI) metadata += MetadataEntry(selector, value = Value.Text(child.children.mapNotNull { (it as? XmlText)?.text ?: (it as? XmlCData)?.text }.joinToString("")),
                        owner = if (authoritative && name.uri == CAMERA_URI && bindings.any { name.local in if (it.protocol == ProtocolIds.GoogleV1) V1_FIELDS else V2_FIELDS }) Ownership.SourceProtocol else Ownership.Ordinary,
                        location = Location(source = identity.id, selector = selector), origin = FactOrigin.Parsed)
                    propertyMetadata(child, false, depth + 1u)
                }
            }
            for (packet in xmp.packets) for (description in packet.descriptions) propertyMetadata(description, true)
            if (jpeg.trailing.length != 0uL && bindings.none { it.video != null }) {
                val region = Region(ResourceId("unknown-trailer"), identity.id, jpeg.trailing, ResourceKind.Trailer)
                regions += region; resources += Resource(region.id, region.kind, listOf(region), false)
            }
            val rangeGroups = resources.groupBy { resource -> resource.extents.map { it.source to it.range } }
            val shared = resources.map { resource -> budget.item(); budget.retain(64uL); resource.copy(sharedWith = rangeGroups.getValue(resource.extents.map { it.source to it.range }).map { it.id }.filter { it != resource.id }) }
            val primaryBinding = bindings.firstOrNull { it.selector == primary }
            val parsedKey = primaryBinding?.key ?: KeyPhotoResult()
            val key = if (primaryBinding?.protocol == ProtocolIds.GoogleV2 && parsedKey.position == null && parsedKey.issues.isEmpty() && videos[primaryBinding.protocol] != null) {
                try { selectKey(videos.getValue(primaryBinding.protocol), null).copy(rawFields = parsedKey.rawFields) }
                catch (_: CoreFault) { parsedKey }
            } else parsedKey
            reader.validateIdentity().orThrow()
            val inspection = InspectionResult(snapshot, detection, Layout(identities, frozenList(regions), frozenList(shared)), frozenList(media), frozenList(metadata), key, issues = frozenList(issues))
            SourceSession(readers, snapshot, jpeg, xmp, bindings, videos.toMap(), inspection)
        }
    }
}

internal fun videoId(protocol: ProtocolId): ResourceId = ResourceId("${protocol.value}:video")

internal fun videoFacts(video: VideoStructure): MediaFacts = MediaFacts(
    videoContainer = video.container, mime = if (video.container == VideoContainer.Mov) "video/quicktime" else "video/mp4",
    duration = video.tracks.filter { it.handler == "vide" }.maxOfOrNull { it.presentationDuration },
    tracks = video.tracks.map { track -> TrackFacts(TrackId(track.trackId.toString()), track.handler,
        videoCodec = track.codec, audioCodec = track.audioCodec, codecString = track.sampleEntry,
        codecConfiguration = track.codecConfiguration, duration = track.presentationDuration,
        frameCount = if (track.handler == "vide") track.samples.size.toULong() else null,
        width = track.width, height = track.height, origin = FactOrigin.Parsed) }, coverage = Coverage.Partial,
)

private suspend fun jpegFacts(reader: BinaryReader, jpeg: JpegStructure): MediaFacts {
    val frames = jpeg.segments.filter { it.marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf) }
    if (frames.size != 1) return MediaFacts(imageFormat = ImageFormat.Jpeg, mime = "image/jpeg", coverage = Coverage.Partial,
        issues = listOf(Issue(IssueCode(if (frames.isEmpty()) "CORRUPTED_CONTAINER" else "UNSUPPORTED_CONTAINER"), if (frames.isEmpty()) Severity.Error else Severity.Warning, Layer.Structure)))
    val range = frames.single().payload!!
    if (range.length < 6uL || jpeg.scans.isEmpty()) fail("CORRUPTED_CONTAINER", "JPEG frame or scan structure is incomplete")
    val header = reader.readBuffer(range.offset, 6u).orThrow()
    val height = readUnsigned(header.slice(1, 3), Endian.Big).toUInt()
    val width = readUnsigned(header.slice(3, 5), Endian.Big).toUInt()
    val components = header[5].toInt() and 255
    if (width == 0u || components == 0 || range.length != 6uL + 3uL * components.toULong()) fail("CORRUPTED_CONTAINER", "JPEG frame dimensions or components are invalid")
    return MediaFacts(imageFormat = ImageFormat.Jpeg, mime = "image/jpeg", width = width, height = height.takeIf { it != 0u }, coverage = Coverage.Partial,
        issues = if (height == 0u) listOf(Issue(IssueCode("UNSUPPORTED_CONTAINER"), Severity.Warning, Layer.Structure)) else emptyList())
}
