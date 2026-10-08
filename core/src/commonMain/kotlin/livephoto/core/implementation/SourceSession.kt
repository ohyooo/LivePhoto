package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.*
import livephoto.core.jpeg.*
import livephoto.core.xmp.*
import livephoto.core.xml.*
import livephoto.core.exif.*
import livephoto.core.oplus.*
import livephoto.core.samsung.*
import livephoto.core.vivo.*
import livephoto.core.huawei.*
import livephoto.core.legacy.*
import livephoto.core.apple.*
import livephoto.core.heif.*

/** One operation's immutable, content-derived facts; borrowed input handles remain open. */
internal class SourceSession internal constructor(
    val readers: List<BinaryReader>,
    val snapshot: Snapshot,
    val jpeg: JpegStructure?,
    val xmp: XmpCollection?,
    val bindings: List<CarrierBinding>,
    val videos: Map<ProtocolId, VideoStructure>,
    val inspection: InspectionResult,
    val exifComments: List<ExifCommentFacts> = emptyList(),
    val sef: SefDirectory? = null,
    val gainMaps: List<GainMapFacts> = emptyList(),
    val huaweiTail: HuaweiTailFacts? = null,
    val legacyPair: VivoPairFacts? = null,
    val applePair: ApplePairFacts? = null,
    val heifItems: HeifItemGraph? = null,
    val heifPrimaryIssues: List<Issue> = emptyList(),
) {
    val reader: BinaryReader get() = applePair?.imageReader ?: legacyPair?.imageReader ?: readers.single()
    suspend fun readerFor(source: SourceId): BinaryReader = readers.firstOrNull { it.identity().orThrow().id == source }
        ?: fail("INVALID_ARGUMENT", "Resource source is absent from the operation snapshot")
    suspend fun recheck(): Unit { for (reader in readers) reader.validateIdentity().orThrow() }

    companion object {
        suspend fun open(input: SourceSet, context: Context, budget: ParseBudget, probeEmbeddedVideo: Boolean = true): CoreResult<SourceSession> = attempt {
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
            if (readers.size != 1) {
                val apple = ApplePairSession.open(input, readers, snapshot, budget)
                val vivo = VivoPairSession.open(input, readers, snapshot, budget)
                val missing = setOf("PAIR_ASSET_MISSING", "INVALID_PAIR_IDENTIFIER")
                for (result in listOf(apple, vivo)) if (result is CoreResult.Failure && result.error.code.value !in missing) throw CoreFault(result.error)
                val appleSession = (apple as? CoreResult.Success)?.value
                val vivoSession = (vivo as? CoreResult.Success)?.value
                if (appleSession != null && vivoSession != null) fail("AMBIGUOUS_PAIR", "Candidates contain more than one protocol pair", Stage.Inspect)
                return@attempt appleSession ?: vivoSession ?: apple.orThrow() ?: vivo.orThrow()
            }
            val reader = readers.single()
            val identity = identities.single()
            val content = detectContent(reader).orThrow()
            if (content.kind != ContentKind.Jpeg) {
                if (content.kind == ContentKind.IsoBmff && BmffBrandHint.Heic !in content.brandHints) {
                    ApplePairSession.open(input, readers, snapshot, budget).orThrow()?.let { return@attempt it }
                }
                if (content.kind == ContentKind.IsoBmff && BmffBrandHint.Heic in content.brandHints) {
                    GoogleHeicSession.open(reader, snapshot, budget, probeEmbeddedVideo).orThrow()?.let { return@attempt it }
                    val heic = SamsungHeicReader.read(reader, budget).orThrow()
                    if (heic != null) {
                        val binding = heic.binding
                        val video = heic.video
                        val strength = if (heic.directory.legacyDialect) MatchStrength.Legacy else if (video != null && binding.structurallyValid && binding.issues.none { it.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNKNOWN_PROTOCOL_VARIANT", "UNSUPPORTED_CONTAINER") }) MatchStrength.Strong else MatchStrength.Weak
                        val match = Match(binding.selector, strength, issues = binding.issues, resourceIds = listOf(videoId(binding.protocol)))
                        val detection = DetectionResult(if (strength == MatchStrength.Strong) Disposition.Live else Disposition.Candidate, binding.selector, listOf(match), binding.issues, snapshot)
                        val primaryRegion = Region(ResourceId("primary"), identity.id, heic.primary, ResourceKind.PrimaryImage)
                        val videoRegion = Region(videoId(binding.protocol), identity.id, binding.video!!, ResourceKind.Video, binding.protocol)
                        val regions = mutableListOf(primaryRegion, videoRegion)
                        val resources = mutableListOf(Resource(primaryRegion.id, primaryRegion.kind, listOf(primaryRegion), false), Resource(videoRegion.id, videoRegion.kind, listOf(videoRegion), true))
                        val heif = heic.itemGraph?.let { inspectHeifItems(reader, it, budget) }
                        if (heif != null) { regions += heif.regions; resources += heif.resources }
                        for ((index, record) in heic.directory.records.withIndex()) {
                            budget.item(); budget.retain(96uL)
                            val region = Region(ResourceId("samsung:sef:record:$index"), identity.id, record.range, ResourceKind.Trailer,
                                if (record.type in setOf(0x0a30.toUShort(), 0x0a31.toUShort())) ProtocolIds.Samsung else null)
                            regions += region; resources += Resource(region.id, region.kind, listOf(region), false)
                        }
                        val media = listOf(MediaFacts(imageFormat = ImageFormat.Heic, mime = "image/heic", width = heic.codedImage?.declaredWidth, height = heic.codedImage?.declaredHeight,
                            coverage = Coverage.Partial, issues = heic.primaryIssues + binding.issues.filter { it.layer == Layer.Structure })) + listOfNotNull(video?.let(::videoFacts))
                        val metadata = mutableListOf(MetadataEntry("samsung:mpv2:pointer-mode", value = heic.pointerMode?.let { Value.Text(it) }, owner = Ownership.SourceProtocol,
                            location = Location(source = identity.id, range = heic.directory.motionRecord!!.payloadRange, selector = "samsung:mpv2:pointer-mode"), origin = FactOrigin.Parsed))
                        if (heif != null) metadata += heif.metadata
                        val inspection = InspectionResult(snapshot, detection, Layout(identities, regions, resources, heif?.relationships ?: emptyList()), media, metadata, binding.key, issues = binding.issues + (heif?.issues ?: emptyList()))
                        reader.validateIdentity().orThrow()
                        return@attempt SourceSession(readers, snapshot, null, null, listOf(binding), if (video == null) emptyMap() else mapOf(binding.protocol to video), inspection, sef = heic.directory, heifItems = heic.itemGraph, heifPrimaryIssues = heic.primaryIssues)
                    }
                    ApplePairSession.open(input, readers, snapshot, budget).orThrow()?.let { return@attempt it }
                    HeifImageSession.open(reader, snapshot, budget).orThrow()?.let { return@attempt it }
                }
                val detection = DetectionResult(Disposition.Unknown, matches = emptyList(), snapshot = snapshot)
                val inspection = InspectionResult(snapshot, detection, Layout(identities, emptyList(), emptyList()), emptyList(), emptyList(), KeyPhotoResult())
                return@attempt SourceSession(readers, snapshot, null, null, emptyList(), emptyMap(), inspection)
            }
            val jpeg = JpegParser.parse(reader, budget).orThrow()
            val xmp = XmpReader.readJpeg(reader, jpeg, budget).orThrow()
            val imageFacts = jpegFacts(reader, jpeg)
            val google = GoogleJpegReader.read(xmp, jpeg, identity, budget).orThrow()
            val exifComments = mutableListOf<ExifCommentFacts>()
            val exifIssues = mutableListOf<Issue>()
            for (segment in jpeg.segments.filter { it.payloadKind == AppPayloadKind.Exif }) {
                val payload = segment.payload!!
                val range = checkedRange(payload.offset + 6uL, payload.length - 6uL, identity.size)
                when (val facts = ExifUserCommentReader(reader, budget).read(range)) {
                    is CoreResult.Success -> exifComments += facts.value
                    is CoreResult.Failure -> {
                        if (facts.error.code.value in setOf("CANCELLED", "RESOURCE_LIMIT_EXCEEDED", "SOURCE_CHANGED", "IO_READ_FAILED", "UNEXPECTED_EOF")) throw CoreFault(facts.error)
                        exifIssues += Issue(facts.error.code, Severity.Warning, Layer.Structure, Location(source = identity.id, range = range))
                    }
                }
            }
            val comments = exifComments.flatMap { it.comments }
            val appleIdentifier = AppleImageReader.read(reader, jpeg, budget, exifComments.map { it.document }).orThrow()
            val appleEvidence = frozenList(listOfNotNull(appleIdentifier?.let { AppleCidEvidence.parsed(reader, "image", it.value, it.range, budget) }))
            val apple = appleIdentifier?.let { CarrierBinding(ProtocolIds.Apple, profile = ProfileId("jpeg-mov"),
                issues = listOf(Issue(IssueCode("PAIR_ASSET_MISSING"), Severity.Error, Layer.Protocol))) }
            val comment = if (comments.size == 1) comments.single().text else null
            val oplus = OplusReader.read(xmp, google, jpeg, identity, comment, budget).orThrow()
            val sef = SefReader.parse(reader, jpeg.primary.endExclusive, budget).orThrow()
            val samsung = sef?.let { SamsungJpegReader.bind(it, google, jpeg) }
            val vivo = VivoReader.read(xmp, google, jpeg, identity, budget).orThrow()
            val huaweiTail = HuaweiTail.read(reader, jpeg.primary.endExclusive, budget).orThrow()?.let { HuaweiJpegReader.confirmEnvelope(reader, it, budget) }
            val huawei = huaweiTail?.let(HuaweiJpegReader::bind)
            val fusion = FusionReader.read(xmp, samsung, oplus, vivo, google, budget).orThrow()
            var bindings = google.map { binding ->
                val vendor = samsung?.takeIf { it.video != null } ?: oplus?.takeIf { it.video != null } ?: vivo?.takeIf { it.video != null }
                if (vendor != null && binding.protocol == ProtocolIds.GoogleV2) binding.copy(video = vendor.video, compatibleBaseOf = vendor.protocol,
                    issues = binding.issues + if (binding.video != vendor.video) listOf(Issue(IssueCode("MOTION_VIDEO_LENGTH_MISMATCH"), Severity.Error, Layer.Protocol, Location(source = identity.id, range = binding.video))) else emptyList(),
                    ownedProperties = if (vendor.protocol == ProtocolIds.VivoModern && vendor.items.size == 3) binding.ownedProperties - ExpandedName(CONTAINER_URI, "Directory") else binding.ownedProperties)
                else binding
            } + listOfNotNull(oplus, samsung, vivo, huawei, fusion, apple)
            if (fusion?.structurallyValid == true) {
                // Shared ranges come from the checked SEF graph. Each base match retains its own issues.
                bindings = bindings.map { binding ->
                    if (binding.protocol in setOf(ProtocolIds.GoogleV2, ProtocolIds.Oplus, ProtocolIds.VivoModern))
                        binding.copy(video = fusion.video, compatibleBaseOf = ProtocolIds.Fusion)
                    else binding
                }
            }
            val gainMaps = mutableListOf<GainMapFacts>()
            val auxiliaryIssues = mutableListOf<Issue>()
            val auxiliaryRanges = bindings.flatMap { it.items }.filter { it.semantic == "GainMap" && it.mime == "image/jpeg" }.map { it.range }.distinct() +
                if (bindings.isEmpty()) listOfNotNull(ordinaryGainMap(xmp, jpeg, identity, budget)) else emptyList()
            for (range in auxiliaryRanges.distinct()) {
                try { gainMaps += verifyGainMap(reader, range, budget) }
                catch (fault: CoreFault) {
                    if (fault.error.code.value in setOf("CANCELLED", "SOURCE_CHANGED", "IO_READ_FAILED", "RESOURCE_LIMIT_EXCEEDED", "UNEXPECTED_EOF")) throw fault
                    auxiliaryIssues += Issue(fault.error.code, if (fault.error.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNSUPPORTED_CONTAINER")) Severity.Warning else Severity.Error, Layer.Media, Location(source = identity.id, range = range))
                }
            }
            bindings = bindings.map { binding ->
                if (binding.protocol == ProtocolIds.VivoModern && binding.items.any { it.semantic == "GainMap" }) binding.copy(issues = binding.issues.filterNot { issue -> issue.location?.selector == "vivo:GainMap:jpeg-verification" && binding.items.filter { it.semantic == "GainMap" }.all { item -> gainMaps.any { it.range == item.range } } } + auxiliaryIssues)
                else binding
            }
            val videos = linkedMapOf<ProtocolId, VideoStructure>()
            val videoCache = linkedMapOf<ByteRange, CoreResult<VideoStructure>>()
            val issues = mutableListOf<Issue>()
            issues += xmp.issues
            issues += exifIssues
            issues += imageFacts.issues
            issues += auxiliaryIssues
            for (binding in bindings) {
                issues += binding.issues
                val range = binding.video ?: continue
                // Repair preview independently probes a bounded physical suffix. Deferred media
                // remains absent from videos and therefore cannot acquire complete coverage.
                if (!probeEmbeddedVideo) continue
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
            bindings = bindings.map { binding ->
                val profileIssues = videos[binding.protocol]?.let { googleVideoIssues(it, binding.selector, false) } ?: emptyList()
                issues += profileIssues
                binding.copy(issues = binding.issues + profileIssues)
            }
            val matches = bindings.map { binding -> Match(binding.selector,
                if (binding.protocol == ProtocolIds.Fusion && binding.structurallyValid && binding.protocol in videos) MatchStrength.Legacy else if (binding.protocol == ProtocolIds.Samsung && sef?.legacyDialect == true && binding.protocol in videos) MatchStrength.Legacy else if (binding.compatibleBaseOf != null && binding.protocol in videos) MatchStrength.CompatibleBase else if (binding.protocol in videos && binding.structurallyValid && binding.issues.none { it.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNSUPPORTED_CONTAINER", "UNKNOWN_PROTOCOL_VARIANT") }) MatchStrength.Strong else MatchStrength.Weak,
                evidence = if (binding.protocol == ProtocolIds.Apple) appleEvidence else emptyList(),
                issues = binding.issues, resourceIds = if (binding.video != null) listOf(videoId(binding.protocol)) else emptyList()) }
            val strong = matches.filter { it.strength == MatchStrength.Strong }
            val conflicting = strong.mapNotNull { match -> bindings.first { it.selector == match.target }.video }.distinct().size > 1 ||
                fusion?.issues?.any { it.severity == Severity.Error && it.code.value == "CONFLICTING_METADATA" } == true
            val disposition = when {
                conflicting -> Disposition.Ambiguous
                strong.isNotEmpty() -> Disposition.Live
                matches.isNotEmpty() -> Disposition.Candidate
                else -> Disposition.NonLive
            }
            val primary = if (conflicting) null else (matches.firstOrNull { it.target.protocol == ProtocolIds.Fusion && it.strength == MatchStrength.Legacy } ?: strong.firstOrNull { it.target.protocol !in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2) } ?: strong.firstOrNull { it.target.protocol == ProtocolIds.GoogleV2 } ?: strong.firstOrNull() ?: matches.singleOrNull())?.target
            val detection = DetectionResult(disposition, primary, frozenList(matches), frozenList(issues), snapshot)
            val regions = mutableListOf(Region(ResourceId("primary"), identity.id, jpeg.primary, ResourceKind.PrimaryImage))
            val resources = mutableListOf(Resource(ResourceId("primary"), ResourceKind.PrimaryImage, listOf(regions.first()), true))
            for (binding in bindings) {
                binding.video?.let { range ->
                    val region = Region(videoId(binding.protocol), identity.id, range, ResourceKind.Video, binding.protocol)
                    regions += region; resources += Resource(region.id, region.kind, listOf(region), true)
                }
                binding.padding?.let { regions += Region(ResourceId("${binding.protocol.value}:padding"), identity.id, it, ResourceKind.Padding, binding.protocol) }
                binding.trailer?.let { range ->
                    val region = Region(ResourceId("${binding.protocol.value}:trailer"), identity.id, range, ResourceKind.Trailer, binding.protocol)
                    regions += region; resources += Resource(region.id, region.kind, listOf(region), false)
                }
                for ((index, item) in binding.items.withIndex()) if (index > 0 && index < binding.items.lastIndex) {
                    val kind = if (item.semantic == "GainMap") ResourceKind.GainMap else ResourceKind.Unknown
                    val region = Region(ResourceId("${binding.protocol.value}:aux:$index"), identity.id, item.range, kind, binding.protocol)
                    regions += region; resources += Resource(region.id, region.kind, listOf(region), true)
                }
            }
            if (sef != null) {
                for ((index, record) in sef.records.withIndex()) {
                    val region = Region(ResourceId("samsung:sef:record:$index"), identity.id, record.range, ResourceKind.Trailer, if (record.type in setOf(0x0a30.toUShort(), 0x0a31.toUShort())) ProtocolIds.Samsung else null)
                    regions += region; resources += Resource(region.id, region.kind, listOf(region), false)
                }
                val indexRegion = Region(ResourceId("samsung:sef:index"), identity.id, ByteRange(sef.table.offset, sef.footer.endExclusive - sef.table.offset), ResourceKind.Trailer)
                regions += indexRegion; resources += Resource(indexRegion.id, indexRegion.kind, listOf(indexRegion), false)
            }
            if (bindings.isEmpty()) for ((index, gainMap) in gainMaps.withIndex()) {
                val region = Region(ResourceId("ordinary:gainmap:$index"), identity.id, gainMap.range, ResourceKind.GainMap)
                regions += region; resources += Resource(region.id, region.kind, listOf(region), true)
            }
            if (huaweiTail != null) {
                for ((id, range) in listOfNotNull(huaweiTail.gap?.let { "huawei:unknown-gap" to it }, huaweiTail.candidateVideoRange.takeIf { huaweiTail.videoRange == null }?.let { "huawei:unconfirmed-media" to it })) {
                    budget.item(); budget.retain(96uL)
                    val region = Region(ResourceId(id), identity.id, range, ResourceKind.Unknown)
                    regions += region; resources += Resource(region.id, region.kind, listOf(region), false)
                }
            }
            val media = listOf(imageFacts) + videos.values.map(::videoFacts) + gainMaps.map { it.media }
            val metadata = mutableListOf<MetadataEntry>()
            if (huaweiTail != null) {
                for (field in huaweiTail.rawFrameFields) {
                    budget.item(); budget.retain(96uL)
                    metadata += MetadataEntry(field.selector, value = field.rawValue, owner = if (huaweiTail.variant == HuaweiTailVariant.Basic60) Ownership.SourceProtocol else Ownership.Unknown,
                        location = field.location, origin = FactOrigin.Parsed)
                }
                metadata += MetadataEntry("huawei:tail:LIVE", value = Value.Text("LIVE_${huaweiTail.liveValue}"), owner = if (huaweiTail.variant == HuaweiTailVariant.Basic60) Ownership.SourceProtocol else Ownership.Unknown,
                    location = Location(source = identity.id, range = ByteRange(huaweiTail.tailRange.offset + 40uL, 20uL)), origin = FactOrigin.Parsed)
            }
            if (sef != null) for ((index, record) in sef.records.withIndex()) {
                budget.item(); budget.retain(96uL)
                val selector = when (record.type) { 0x0a30.toUShort() -> "samsung:sef:MotionPhoto_Data"; 0x0a31.toUShort() -> "samsung:sef:MotionPhoto_Version"; else -> "samsung:sef:type:${record.type}:$index" }
                metadata += MetadataEntry(selector, value = Value.Text("type=${record.type}; recordLength=${record.range.length}"), raw = ResourceId("samsung:sef:record:$index"),
                    owner = if (record.type in setOf(0x0a30.toUShort(), 0x0a31.toUShort())) Ownership.SourceProtocol else Ownership.Unknown,
                    location = Location(source = identity.id, range = record.range), origin = FactOrigin.Parsed)
            }
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
                    val owned = authoritative && bindings.any { name in it.ownedProperties }
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
                        owner = if (authoritative && bindings.any { name in it.ownedProperties } && name != ExpandedName(CONTAINER_URI, "Directory")) Ownership.SourceProtocol else Ownership.Ordinary,
                        location = Location(source = identity.id, selector = selector), origin = FactOrigin.Parsed)
                    propertyMetadata(child, false, depth + 1u)
                }
            }
            for (packet in xmp.packets) for (description in packet.descriptions) propertyMetadata(description, true)
            for (facts in exifComments) for (entry in facts.comments) {
                budget.item(); budget.retain(96uL)
                metadata += MetadataEntry("exif:UserComment", value = entry.text?.let { Value.Text(it) },
                    owner = if (entry.marker != null && oplus != null) Ownership.SourceProtocol else Ownership.Ordinary,
                    location = Location(source = identity.id, range = entry.entry.valueRange, selector = "exif:UserComment"), origin = FactOrigin.Parsed)
            }
            if (jpeg.trailing.length != 0uL && bindings.none { it.video != null } && gainMaps.isEmpty()) {
                val region = Region(ResourceId("unknown-trailer"), identity.id, jpeg.trailing, ResourceKind.Trailer)
                regions += region; resources += Resource(region.id, region.kind, listOf(region), false)
            }
            val rangeGroups = resources.groupBy { resource -> resource.extents.map { it.source to it.range } }
            val shared = resources.map { resource -> budget.item(); budget.retain(64uL); resource.copy(sharedWith = rangeGroups.getValue(resource.extents.map { it.source to it.range }).map { it.id }.filter { it != resource.id }) }
            val verifiedGainMapRanges = gainMaps.map { it.range }.toSet()
            val relationships = shared.filter { resource -> resource.kind == ResourceKind.GainMap && resource.extents.singleOrNull()?.range in verifiedGainMapRanges }.map { resource ->
                budget.item(); budget.retain(64uL)
                Relationship(RelationshipKind.AuxiliaryOf, resource.id, ResourceId("primary"))
            }
            val primaryBinding = bindings.firstOrNull { it.selector == primary }
            val parsedKey = primaryBinding?.key ?: if (disposition != Disposition.Ambiguous) KeyPhotoResult(rawFields = bindings.flatMap { it.key.rawFields }.distinct(), issues = bindings.flatMap { it.key.issues }.distinct()) else KeyPhotoResult()
            val key = if (primaryBinding?.protocol == ProtocolIds.GoogleV2 && parsedKey.position == null && parsedKey.issues.isEmpty() && videos[primaryBinding.protocol] != null) {
                try { selectKey(videos.getValue(primaryBinding.protocol), null).copy(rawFields = parsedKey.rawFields) }
                catch (_: CoreFault) { parsedKey }
            } else parsedKey
            reader.validateIdentity().orThrow()
            if (appleIdentifier != null) metadata += MetadataEntry("apple:image:content-identifier", value = Value.Text(appleIdentifier.value), owner = Ownership.SourceProtocol,
                location = Location(source = identity.id, range = appleIdentifier.range), origin = FactOrigin.Parsed)
            val inspection = InspectionResult(snapshot, detection, Layout(identities, frozenList(regions), frozenList(shared), frozenList(relationships)), frozenList(media), frozenList(metadata), key,
                pairing = appleIdentifier?.let { PairingFacts(imageIdentifier = it.value, matches = false, evidence = appleEvidence) }, issues = frozenList(issues))
            SourceSession(readers, snapshot, jpeg, xmp, bindings, videos.toMap(), inspection, frozenList(exifComments), sef, frozenList(gainMaps), huaweiTail)
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

internal suspend fun jpegFacts(reader: BinaryReader, jpeg: JpegStructure): MediaFacts {
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
