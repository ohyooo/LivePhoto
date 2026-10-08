package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.apple.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.jpeg.*
import livephoto.core.xmp.*
import livephoto.core.heif.*

internal data class ApplePairFacts(val imageReader: BinaryReader, val videoReader: BinaryReader)

/** Only parsed identifiers establish pairing; filenames and candidate order carry no authority. */
internal object ApplePairSession {
    private data class Image(val reader: BinaryReader, val jpeg: JpegStructure?, val id: AppleImageIdentifier, val heif: AppleHeifImage? = null)
    private data class Video(val reader: BinaryReader, val id: AppleVideoIdentifier)

    suspend fun open(input: SourceSet, readers: List<BinaryReader>, snapshot: Snapshot, budget: ParseBudget): CoreResult<SourceSession?> = attempt {
        val images = mutableListOf<Image>()
        val videos = mutableListOf<Video>()
        for (reader in readers) {
            val content = detectContent(reader).orThrow()
            when (content.kind) {
                ContentKind.Jpeg -> {
                    val jpeg = JpegParser.parse(reader, budget).orThrow()
                    val id = AppleImageReader.read(reader, jpeg, budget).orThrow()
                    if (id != null) images += Image(reader, jpeg, id)
                }
                ContentKind.IsoBmff -> if (BmffBrandHint.Heic in content.brandHints) {
                    AppleHeifImageReader.read(reader, budget).orThrow()?.let { images += Image(reader, null, it.identifier, it) }
                } else AppleVideoReader.read(reader, budget).orThrow()?.let { videos += Video(reader, it) }
                else -> Unit
            }
        }
        if (images.isEmpty() && videos.isEmpty()) return@attempt null
        if (input is SourceSet.Pair && (images.singleOrNull()?.reader?.source !== input.image || videos.singleOrNull()?.reader?.source !== input.video))
            fail("PAIR_ASSET_MISSING", "Explicit Apple pair roles require identified image and video carriers", Stage.Inspect)
        val pairs = images.flatMap { image -> videos.filter { it.id.value == image.id.value }.map { image to it } }
        if (pairs.size > 1) fail("AMBIGUOUS_PAIR", "Apple identifiers do not select a unique pair", Stage.Inspect)
        val pair = pairs.singleOrNull()
        if (pair == null && readers.size != 1) fail(if (images.isNotEmpty() && videos.isNotEmpty()) "INVALID_PAIR_IDENTIFIER" else "PAIR_ASSET_MISSING", "No exact-ID Apple pair is available", Stage.Inspect)
        val image = pair?.first ?: images.singleOrNull()
        val video = pair?.second ?: videos.singleOrNull()
        val issues = mutableListOf<Issue>()
        if (pair == null) issues += Issue(IssueCode("PAIR_ASSET_MISSING"), Severity.Error, Layer.Protocol)
        var media: VideoStructure? = null
        var key = KeyPhotoResult()
        if (video != null) {
            val range = ByteRange(0uL, video.reader.identity().orThrow().size)
            when (val result = BmffVideoProbe(video.reader, budget, allowTimedMetadata = true).probe(range)) {
                is CoreResult.Success -> {
                    media = result.value
                    key = AppleVideoReader.key(video.reader, media, budget).orThrow()
                    issues += key.issues
                }
                is CoreResult.Failure -> {
                    if (result.error.code.value in setOf("CANCELLED", "SOURCE_CHANGED", "IO_READ_FAILED", "UNEXPECTED_EOF", "RESOURCE_LIMIT_EXCEEDED")) throw CoreFault(result.error)
                    issues += Issue(result.error.code, if (result.error.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNSUPPORTED_CONTAINER", "VIDEO_CODEC_NOT_SUPPORTED", "AUDIO_CODEC_NOT_SUPPORTED")) Severity.Warning else Severity.Error, Layer.Media, result.error.location)
                }
            }
        }
        val profile = "${if (image?.heif != null) "heic" else "jpeg"}-${if (media?.container == VideoContainer.Mp4) "mp4" else "mov"}"
        val selector = ProtocolSelector(ProtocolIds.Apple, ProfileId(profile))
        val regions = mutableListOf<Region>()
        if (image != null) regions += Region(ResourceId("primary"), image.reader.identity().orThrow().id, ByteRange(0uL, image.reader.identity().orThrow().size), ResourceKind.PrimaryImage, ProtocolIds.Apple)
        if (video != null) regions += Region(videoId(ProtocolIds.Apple), video.reader.identity().orThrow().id, ByteRange(0uL, video.reader.identity().orThrow().size), ResourceKind.Video, ProtocolIds.Apple)
        val complete = pair != null && media != null
        val match = Match(selector, if (complete) MatchStrength.Strong else MatchStrength.Weak, issues = issues.toList(), resourceIds = regions.map { it.id })
        val detection = DetectionResult(if (complete) Disposition.Live else Disposition.Candidate, selector, listOf(match), issues.toList(), snapshot)
        val metadata = listOfNotNull(
            image?.let { MetadataEntry("apple:image:content-identifier", value = Value.Text(it.id.value), owner = Ownership.SourceProtocol, location = Location(source = it.reader.identity().orThrow().id, range = it.id.range), origin = FactOrigin.Parsed) },
            video?.let { MetadataEntry(APPLE_CID, value = Value.Text(it.id.value), owner = Ownership.SourceProtocol, location = Location(source = it.reader.identity().orThrow().id, range = it.id.range), origin = FactOrigin.Parsed) })
        val facts = listOfNotNull(image?.let { it.heif?.media ?: jpegFacts(it.reader, it.jpeg!!) }, media?.let(::videoFacts))
        val inspection = InspectionResult(snapshot, detection,
            Layout(snapshot.identities, regions.toList(), regions.map { Resource(it.id, it.kind, listOf(it), true) },
                if (pair == null) emptyList() else listOf(Relationship(RelationshipKind.PairedWith, ResourceId("primary"), videoId(ProtocolIds.Apple)))),
            facts, metadata, key, PairingFacts(image?.id?.value, video?.id?.value, pair != null), issues.toList())
        val binding = CarrierBinding(ProtocolIds.Apple, video?.let { ByteRange(0uL, it.reader.identity().orThrow().size) }, key = key, issues = issues.toList(), profile = ProfileId(profile))
        for (reader in readers) reader.validateIdentity().orThrow()
        SourceSession(readers, snapshot, image?.jpeg, image?.jpeg?.let { XmpReader.readJpeg(image.reader, it, budget).orThrow() }, listOf(binding),
            media?.let { mapOf(ProtocolIds.Apple to it) } ?: emptyMap(), inspection,
            applePair = pair?.let { ApplePairFacts(it.first.reader, it.second.reader) }, heifItems = image?.heif?.graph)
    }
}
