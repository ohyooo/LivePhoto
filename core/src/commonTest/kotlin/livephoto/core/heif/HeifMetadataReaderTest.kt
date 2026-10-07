package livephoto.core.heif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import kotlin.test.*

class HeifMetadataReaderTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val xml = "<r:RDF xmlns:r=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><r:Description xmlns:p=\"urn:private\" p:secret=\"not-a-log-value\"/></r:RDF>".encodeToByteArray()
    private fun u(value: UInt, width: Int) = unsignedBytes(value.toULong(), width, Endian.Big).toByteArray()
    private fun full(type: String, payload: ByteArray, version: Int = 0) = GoogleFixtures.box(type, byteArrayOf(version.toByte(), 0, 0, 0) + payload)
    private fun exif(big: Boolean = false, bias: Int = 6): ByteArray {
        val endian = if (big) Endian.Big else Endian.Little
        fun integer(value: ULong, width: Int) = unsignedBytes(value, width, endian).toByteArray()
        val tiff = (if (big) "MM" else "II").encodeToByteArray() + integer(42uL, 2) + integer(8uL, 4) + integer(0uL, 2) + integer(0uL, 4)
        return u(bias.toUInt(), 4) + ByteArray(bias) + tiff
    }
    private fun fixture(exif: ByteArray = exif(), xmp: ByteArray = xml, multiple: Boolean = false, idat: Boolean = false,
                        contentType: String = "application/rdf+xml", encoding: String = "", protected: Boolean = false, linked: Boolean = true): ByteArray {
        val payloads = listOf(GoogleFixtures.video(hevc = true).samples.first(), exif, xmp)
        val positions = mutableListOf<List<Pair<UInt, UInt>>>()
        var data = byteArrayOf()
        for (payload in payloads) {
            val first = data.size.toUInt()
            if (multiple) {
                data += payload.copyOfRange(0, 2) + byteArrayOf(0xa5.toByte(), 0x5a, 0xff.toByte()) + payload.copyOfRange(2, payload.size)
                positions += listOf(first to 2u, first + 5u to (payload.size - 2).toUInt())
            } else { data += payload; positions += listOf(first to payload.size.toUInt()) }
        }
        val ftyp = GoogleFixtures.box("ftyp", "heic".encodeToByteArray() + u(0u, 4) + "mif1heic".encodeToByteArray())
        fun meta(start: UInt): ByteArray {
            var infos = byteArrayOf()
            for ((index, type) in listOf("hvc1", "Exif", "mime").withIndex()) {
                val suffix = if (type == "mime") "$contentType\u0000$encoding\u0000".encodeToByteArray() else byteArrayOf()
                infos += full("infe", u((index + 1).toUInt(), 2) + u(if (protected && index > 0) 1u else 0u, 2) + type.encodeToByteArray() + "item\u0000".encodeToByteArray() + suffix, 2)
            }
            var iloc = byteArrayOf(0x44, 0) + u(3u, 2)
            for ((index, extents) in positions.withIndex()) {
                iloc += u((index + 1).toUInt(), 2) + (if (idat) u(1u, 2) else byteArrayOf()) + u(0u, 2) + u(extents.size.toUInt(), 2)
                for ((offset, length) in extents) iloc += u(start + offset, 4) + u(length, 4)
            }
            val properties = GoogleFixtures.box("ipco", full("ispe", u(1u, 4) + u(1u, 4)) + GoogleFixtures.box("hvcC", GoogleFixtures.video(hevc = true).configuration))
            val iprp = GoogleFixtures.box("iprp", properties + full("ipma", u(1u, 4) + byteArrayOf(0, 1, 2, 0x81.toByte(), 0x82.toByte())))
            val iref = if (!linked) byteArrayOf() else full("iref", GoogleFixtures.box("cdsc", u(2u, 2) + u(1u, 2) + u(1u, 2)) + GoogleFixtures.box("cdsc", u(3u, 2) + u(1u, 2) + u(1u, 2)))
            return full("meta", full("hdlr", u(0u, 4) + "pict".encodeToByteArray() + ByteArray(12) + byteArrayOf(0)) + full("pitm", u(1u, 2)) +
                full("iinf", u(3u, 2) + infos) + full("iloc", iloc, if (idat) 1 else 0) + iprp + iref + if (idat) GoogleFixtures.box("idat", data) else byteArrayOf())
        }
        return if (idat) ftyp + meta(0u) else ftyp + meta((ftyp.size + meta(0u).size + 8).toUInt()) + GoogleFixtures.box("mdat", data)
    }
    private fun source(bytes: ByteArray) = MemoryBinarySource(Bytes(bytes), SourceId("heif-metadata"))
    private suspend fun read(bytes: ByteArray, ctx: Context = context): CoreResult<HeifMetadataFacts> = attempt {
        val reader = BinaryReader(source(bytes), ctx)
        val budget = ParseBudget(ctx)
        val roots = BmffReader(reader, budget).readBoxes(ByteRange(0uL, bytes.size.toULong())).orThrow()
        HeifMetadataReader.read(reader, HeifItemGraphReader.read(reader, roots, budget).orThrow(), budget).orThrow()
    }
    @Test fun tiffOffsetsEndianAndXmpAreParsedInLogicalExtentOrderNotFileOrder(): Unit = runImmediate {
        for (idat in listOf(false, true)) for (multiple in listOf(false, true)) for (big in listOf(false, true)) for (bias in listOf(0, 6)) {
            val facts = read(fixture(exif(big, bias), multiple = multiple, idat = idat)).orThrow()
            assertTrue(facts.issues.isEmpty())
            assertEquals(listOf(2u, 3u), facts.items.map { it.id })
            assertEquals(listOf(1u), facts.items.first().describes)
            val tiff = facts.items.first().tiff!!
            assertEquals(if (big) Endian.Big else Endian.Little, tiff.endian)
            assertEquals((4 + bias).toULong(), tiff.range.offset)
            assertEquals(1, tiff.ifds.size)
            assertEquals("not-a-log-value", facts.items.last().xmp!!.scalar("urn:private", "secret").orThrow())
        }
    }
    @Test fun metadataClassificationDoesNotPromoteCarrierOrDisclosePrivateValuesAndRawStaysExact(): Unit = runImmediate {
        val exif = exif()
        val bytes = fixture(exif, multiple = true)
        val core = DefaultLivePhotoCore()
        val inspection = core.inspect(ReadRequest(SourceSet.Single(source(bytes)), context)).orThrow()
        assertEquals(Disposition.Unknown, inspection.detection.disposition)
        assertEquals(2, inspection.metadata.count { it.selector.endsWith(":metadata-format") })
        assertTrue(inspection.metadata.none { it.value == Value.Text("not-a-log-value") })
        assertTrue(inspection.layout.resources.all { !it.standalone })
        val output = MemoryOutputTransaction(context, "heif-metadata-raw")
        core.extract(ExtractRequest(SourceSet.Single(source(bytes)), listOf(ResourceId("heif:item:2"), ResourceId("heif:item:3")), output = output, context = context)).orThrow()
        assertEquals(listOf(Bytes(exif), Bytes(xml)), output.committedAssets().values.toList())
    }
    @Test fun malformedExifAndXmlHavePhysicalItemIssuesButTheirRawBytesRemainAvailable(): Unit = runImmediate {
        val malformed = exif().also { it[0] = 0xff.toByte() }
        val bytes = fixture(malformed, "<broken>".encodeToByteArray(), multiple = true)
        val facts = read(bytes).orThrow()
        assertTrue(facts.items.isEmpty())
        assertEquals(setOf(IssueCode("OFFSET_OUT_OF_BOUNDS"), IssueCode("MALFORMED_XMP")), facts.issues.map { it.code }.toSet())
        assertTrue(facts.issues.all { it.location?.source == SourceId("heif-metadata") && it.location.range != null })
        val output = MemoryOutputTransaction(context, "heif-malformed-metadata-raw")
        DefaultLivePhotoCore().extract(ExtractRequest(SourceSet.Single(source(bytes)), listOf(ResourceId("heif:item:2")), output = output, context = context)).orThrow()
        assertEquals(Bytes(malformed), output.committedAssets().values.single())
    }
    @Test fun unknownMimeEncodingProtectionAndAbsentCdscNeverInventPrimaryBinding(): Unit = runImmediate {
        val other = read(fixture(contentType = "application/octet-stream", linked = false)).orThrow()
        assertEquals(listOf(2u), other.items.map { it.id })
        assertTrue(other.items.single().describes.isEmpty())
        for (bytes in listOf(fixture(encoding = "gzip"), fixture(protected = true))) {
            val facts = read(bytes).orThrow()
            assertTrue(facts.issues.isNotEmpty())
            assertTrue(facts.issues.all { it.code == IssueCode("CAPABILITY_UNSUPPORTED") && it.severity == Severity.Warning })
        }
    }
    @Test fun metadataBudgetsAndCancellationAreFatalRatherThanPartialSuccess(): Unit = runImmediate {
        val bytes = fixture(xmp = ByteArray(20_000) { 32 })
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(read(bytes, context.copy(limits = context.limits.copy(maxMetadataBytes = 10_000uL)))).error.code)
        assertEquals(IssueCode("CANCELLED"), assertIs<CoreResult.Failure>(read(fixture(), context.copy(cancellation = Cancellation { true }))).error.code)
    }
}
