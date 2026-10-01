package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.jpeg.*
import livephoto.core.vivo.*
import livephoto.core.xmp.*

internal data class VivoPairFacts(
    val imageReader: BinaryReader,
    val videoReader: BinaryReader,
    val imageTail: VivoLegacyMetadata,
    val videoTail: VivoLegacyMetadata,
    val uuid: BmffBox,
)

/** Exact semantic ID pairing. All candidates remain in the snapshot and identity guard. */
internal object VivoPairSession {
    private data class Image(val reader: BinaryReader, val jpeg: JpegStructure, val xmp: XmpCollection, val tail: VivoLegacyMetadata)
    private data class Video(val reader: BinaryReader, val uuid: BmffBox, val tail: VivoLegacyMetadata, val media: VideoStructure)

    suspend fun open(input: SourceSet, readers: List<BinaryReader>, snapshot: Snapshot, budget: ParseBudget): CoreResult<SourceSession> = attempt {
        val images = mutableListOf<Image>()
        val videos = mutableListOf<Video>()
        for (reader in readers) {
            budget.poll()
            val identity = reader.identity().orThrow()
            when (detectContent(reader).orThrow().kind) {
                ContentKind.Jpeg -> {
                    val jpeg = JpegParser.parse(reader, budget).orThrow()
                    val tail = VivoLegacyTail.read(reader, jpeg.trailing, budget).orThrow()
                    if (tail != null) images += Image(reader, jpeg, XmpReader.readJpeg(reader, jpeg, budget).orThrow(), tail)
                }
                ContentKind.IsoBmff -> {
                    val boxes = BmffReader(reader, budget).readBoxes(ByteRange(0uL, identity.size)).orThrow()
                    val owned = boxes.filter { it.type == "uuid" && it.userType == Bytes("vivoMediaExtInfo".encodeToByteArray()) }
                    if (owned.size > 1) fail("AMBIGUOUS_PAIR", "Multiple owned vivo UUID boxes cannot select one pairing authority", Stage.Inspect)
                    val uuid = owned.singleOrNull() ?: continue
                    val tail = VivoLegacyTail.read(reader, uuid.payload, budget).orThrow()
                        ?: fail("INVALID_PAIR_IDENTIFIER", "Owned vivo UUID has no confirmed legacy metadata", Stage.Inspect)
                    val media = BmffVideoProbe(reader, budget).probe(ByteRange(0uL, identity.size)).orThrow()
                    if (media.container != VideoContainer.Mp4) fail("UNSUPPORTED_CONTAINER", "This vivo legacy profile requires MP4")
                    videos += Video(reader, uuid, tail, media)
                }
                else -> Unit
            }
        }
        if (input is SourceSet.Pair && (images.singleOrNull()?.reader?.source !== input.image || videos.singleOrNull()?.reader?.source !== input.video))
            fail("PAIR_ASSET_MISSING", "Explicit pair roles require a confirmed JPEG image and MP4 video", Stage.Inspect)
        val pairs = images.flatMap { image -> videos.filter { it.tail.id == image.tail.id }.map { image to it } }
        if (pairs.size > 1) fail("AMBIGUOUS_PAIR", "Candidate identifiers do not establish a unique pair", Stage.Inspect)
        val pair = pairs.singleOrNull() ?: fail(if (images.isNotEmpty() && videos.isNotEmpty()) "INVALID_PAIR_IDENTIFIER" else "PAIR_ASSET_MISSING", "No unique exact-ID vivo legacy pair is available", Stage.Inspect)
        val image = pair.first
        val video = pair.second
        val keyIssues = (image.tail.issues + video.tail.issues).distinct()
        val key = KeyPhotoResult(rawFields = frozenList(image.tail.rawKeyFields + video.tail.rawKeyFields), issues = frozenList(keyIssues))
        val binding = CarrierBinding(ProtocolIds.VivoLegacy, ByteRange(0uL, video.tail.sourceIdentity.size), key = key, issues = keyIssues, profile = ProfileId("pair"))
        val primary = Region(ResourceId("primary"), image.tail.sourceIdentity.id, ByteRange(0uL, image.tail.sourceIdentity.size), ResourceKind.PrimaryImage, ProtocolIds.VivoLegacy)
        val motion = Region(videoId(ProtocolIds.VivoLegacy), video.tail.sourceIdentity.id, binding.video!!, ResourceKind.Video, ProtocolIds.VivoLegacy)
        val tailRegion = Region(ResourceId("vivo:legacy:image-tail"), primary.source, image.tail.range, ResourceKind.VendorMetadata, ProtocolIds.VivoLegacy)
        val uuidRegion = Region(ResourceId("vivo:legacy:video-uuid"), motion.source, video.uuid.range, ResourceKind.VendorMetadata, ProtocolIds.VivoLegacy)
        val regions = listOf(primary, motion, tailRegion, uuidRegion)
        val resources = regions.map { Resource(it.id, it.kind, listOf(it), it.kind in setOf(ResourceKind.PrimaryImage, ResourceKind.Video)) }
        val match = Match(binding.selector, MatchStrength.Legacy, issues = keyIssues, resourceIds = listOf(primary.id, motion.id))
        val detection = DetectionResult(Disposition.Live, binding.selector, listOf(match), keyIssues, snapshot)
        val metadata = listOf(image.tail, video.tail).flatMap { tail ->
            tail.json.entries.map { (name, value) ->
                budget.item(); budget.retain(96uL)
                MetadataEntry(name, value = value, owner = if (name in setOf("com.android.camera.livephoto", "com.android.camera.imageTime", "com.vivo.gallery.livePhoto.newCoverTime")) Ownership.SourceProtocol else Ownership.Unknown,
                    location = Location(source = tail.sourceIdentity.id, range = tail.jsonRange, selector = name), origin = FactOrigin.Parsed)
            }
        }
        val facts = jpegFacts(image.reader, image.jpeg)
        val inspection = InspectionResult(snapshot, detection,
            Layout(snapshot.identities, frozenList(regions), frozenList(resources), listOf(Relationship(RelationshipKind.PairedWith, primary.id, motion.id))),
            listOf(facts, videoFacts(video.media)), frozenList(metadata), key,
            PairingFacts(image.tail.id, video.tail.id, true), frozenList(keyIssues + facts.issues))
        for (reader in readers) reader.validateIdentity().orThrow()
        SourceSession(readers, snapshot, image.jpeg, image.xmp, listOf(binding), mapOf(ProtocolIds.VivoLegacy to video.media), inspection,
            legacyPair = VivoPairFacts(image.reader, video.reader, image.tail, video.tail, video.uuid))
    }
}
