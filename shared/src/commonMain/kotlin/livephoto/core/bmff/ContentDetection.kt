package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*

/** Signature/brand candidates only: no codec, item graph, media or protocol validity claim. */
internal enum class ContentKind { Jpeg, Png, Gif, WebP, Tiff, IsoBmff, Unknown }
internal enum class BmffBrandHint { Heif, Heic, Avif, QuickTime, Mp4 }
internal data class ContentCandidate(
    val kind: ContentKind,
    val fileType: BmffFileType? = null,
    val brandHints: Set<BmffBrandHint> = emptySet(),
)

internal suspend fun detectContent(reader: BinaryReader): CoreResult<ContentCandidate> = attempt {
    val size = reader.identity().orThrow().size
    val prefix = reader.readBuffer(0uL, minOf(size, 12uL).toUInt()).orThrow()
    fun starts(vararg bytes: Int): Boolean = prefix.size >= bytes.size && bytes.indices.all { (prefix[it].toInt() and 0xff) == bytes[it] }
    val kind = when {
        starts(0xff, 0xd8) -> ContentKind.Jpeg
        starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> ContentKind.Png
        starts(0x47, 0x49, 0x46, 0x38, 0x37, 0x61) || starts(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) -> ContentKind.Gif
        prefix.size >= 12 && fourCc(prefix, 0) == "RIFF" && fourCc(prefix, 8) == "WEBP" -> ContentKind.WebP
        starts(0x49, 0x49, 0x2a, 0x00) || starts(0x4d, 0x4d, 0x00, 0x2a) ||
            starts(0x49, 0x49, 0x2b, 0x00) || starts(0x4d, 0x4d, 0x00, 0x2b) -> ContentKind.Tiff
        else -> ContentKind.Unknown
    }
    if (kind != ContentKind.Unknown) {
        reader.validateIdentity().orThrow()
        return@attempt ContentCandidate(kind)
    }
    // Avoid mistaking arbitrary bytes for BMFF. The first box must carry ftyp or a known preamble.
    if (prefix.size < 8 || fourCc(prefix, 4) !in setOf("ftyp", "free", "skip", "wide", "moov", "mdat")) {
        reader.validateIdentity().orThrow()
        return@attempt ContentCandidate(ContentKind.Unknown)
    }
    val parser = BmffReader(reader)
    val boxes = parser.readBoxes(ByteRange(0uL, size)).orThrow()
    val fileTypes = boxes.filter { it.type == "ftyp" }
    if (fileTypes.size > 1) fail("CORRUPTED_CONTAINER", "Multiple top-level BMFF ftyp boxes are ambiguous")
    val fileType = fileTypes.singleOrNull()?.let { parser.readFileType(it).orThrow() }
    val brands = fileType?.let { setOf(it.majorBrand) + it.compatibleBrands } ?: emptySet()
    val hints = mutableSetOf<BmffBrandHint>()
    if (brands.any { it in setOf("mif1", "msf1") }) hints.add(BmffBrandHint.Heif)
    if (brands.any { it in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs") }) hints.add(BmffBrandHint.Heic)
    if (brands.any { it in setOf("avif", "avis") }) hints.add(BmffBrandHint.Avif)
    if ("qt  " in brands) hints.add(BmffBrandHint.QuickTime)
    if (brands.any { it in setOf("isom", "iso2", "mp41", "mp42") }) hints.add(BmffBrandHint.Mp4)
    reader.validateIdentity().orThrow()
    ContentCandidate(ContentKind.IsoBmff, fileType, hints.toSet())
}
