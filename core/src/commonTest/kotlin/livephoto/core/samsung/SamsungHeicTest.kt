package livephoto.core.samsung

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import kotlin.test.*

/** Real item-table encodings are present; image decoding is deliberately unclaimed. */
class SamsungHeicTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolId("samsung.motionphoto"), ProfileId("heic-sef-mpv2"))
    private fun input(bytes: ByteArray) = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("samsung-heic")))
    private data class Fixture(val bytes: ByteArray, val videoStart: Int, val pointerStart: Int, val mpvdStart: Int)

    @Test
    fun itemInspectionExposesRawCodestreamWithoutClaimingAnIndependentHeicCarrier(): Unit = runImmediate {
        val fixture = fixture()
        val inspected = value(core.inspect(ReadRequest(input(fixture.bytes), context)))
        val item = inspected.layout.resources.single { it.id == ResourceId("heif:item:1") }
        assertFalse(item.standalone)
        assertEquals(ResourceKind.Unknown, item.kind)
        assertEquals(Value.Text("hvc1"), inspected.metadata.single { it.selector == "heif:item:1:type" }.value)
        assertEquals(Value.Number("1"), inspected.metadata.single { it.selector == "heif:primary-item-id" }.value)
        val raw = MemoryOutputTransaction(context, "heif-raw-item")
        value(core.extract(ExtractRequest(input(fixture.bytes), listOf(item.id), inspected.snapshot, output = raw, context = context)))
        assertEquals(Bytes(GoogleFixtures.video(hevc = true).samples.first()), raw.committedAssets().values.single())
        assertTrue(inspected.media.first().coverage != Coverage.Complete)
        assertTrue(inspected.detection.matches.none { it.strength == MatchStrength.Strong })
    }

    @Test
    fun corruptImageExtentIsReportedWithoutDiscardingAnIndependentlyTrustedMotionRange(): Unit = runImmediate {
        val fixture = fixture()
        val reader = BinaryReader(MemoryBinarySource(Bytes(fixture.bytes), SourceId("heif-bounds")), context)
        val parser = BmffReader(reader)
        val meta = parser.readBoxes(ByteRange(0uL, fixture.bytes.size.toULong())).orThrow().single { it.type == "meta" }
        val iloc = parser.readBoxes(ByteRange(meta.payload.offset + 4uL, meta.payload.length - 4uL)).orThrow().single { it.type == "iloc" }
        val damaged = fixture.bytes.copyOf().also { GoogleFixtures.u32(UInt.MAX_VALUE).copyInto(it, iloc.payload.offset.toInt() + 14) }
        val inspected = value(core.inspect(ReadRequest(input(damaged), context)))
        assertTrue(inspected.issues.any { it.code == IssueCode("OFFSET_OUT_OF_BOUNDS") && it.severity == Severity.Error })
        assertTrue(inspected.layout.resources.none { it.id == ResourceId("heif:item:1") })
        val movie = inspected.layout.resources.single { it.kind == ResourceKind.Video }
        val raw = MemoryOutputTransaction(context, "heif-corrupt-image-raw-video")
        value(core.extract(ExtractRequest(input(damaged), listOf(movie.id), inspected.snapshot, output = raw, context = context)))
        assertEquals(Bytes(GoogleFixtures.video().bytes), raw.committedAssets().values.single())
    }

    @Test
    fun sharedItemResourcesAreExplicitAndCannotBePublishedTwiceAsDifferentBytes(): Unit = runImmediate {
        val bytes = fixture(shared = true).bytes
        val inspected = value(core.inspect(ReadRequest(input(bytes), context)))
        val items = inspected.layout.resources.filter { it.id.value.startsWith("heif:item:") }
        assertEquals(2, items.size)
        assertEquals(listOf(items[1].id), items[0].sharedWith)
        assertEquals(listOf(items[0].id), items[1].sharedWith)
        assertEquals(items[0].extents.single().range, items[1].extents.single().range)
        val output = MemoryOutputTransaction(context, "heif-shared-aliases")
        assertEquals(IssueCode("INVALID_ARGUMENT"), assertIs<CoreResult.Failure>(core.extract(ExtractRequest(input(bytes), items.map { it.id }, inspected.snapshot, output = output, context = context))).error.code)
        assertTrue(output.committedAssets().isEmpty())
    }

    @Test
    fun absoluteAndAbiOnlyRelativePointersExposeExactVideoWithIncompleteImageCoverage(): Unit = runImmediate {
        for (relative in listOf(false, true)) for (nested in listOf(false, true)) {
            val fixture = fixture(relative, nested)
            val source = input(fixture.bytes)
            val inspected = value(core.inspect(ReadRequest(source, context)))
            assertTrue(inspected.detection.matches.none { it.target.protocol == target.protocol && it.strength == MatchStrength.Strong })
            val movie = inspected.layout.resources.first { it.kind == ResourceKind.Video }
            assertEquals(ByteRange(fixture.videoStart.toULong(), GoogleFixtures.video().bytes.size.toULong()), movie.extents.single().range)
            val report = value(core.validate(ValidationRequest(source, target = target, context = context)))
            assertTrue(report.coverage != Coverage.Complete)
            assertTrue(report.verdict != Verdict.Valid)
            assertTrue(report.checks.any { it.layer == Layer.Structure && it.coverage != Coverage.Complete })
            if (relative) assertTrue((report.issues + report.checks.flatMap { it.issues }).any { it.code == IssueCode("UNKNOWN_PROTOCOL_VARIANT") })
            val raw = MemoryOutputTransaction(context, "samsung-heic-raw-$relative-$nested")
            value(core.extract(ExtractRequest(source, listOf(movie.id), inspected.snapshot, output = raw, context = context)))
            assertEquals(Bytes(GoogleFixtures.video().bytes), raw.committedAssets().values.single())
        }
    }

    @Test
    fun pointerBoundsAndDuplicateMpvdLayoutsFailWithoutChoosingUnrelatedMedia(): Unit = runImmediate {
        val fixture = fixture()
        val bad = listOf(
            fixture.bytes.copyOf().also { GoogleFixtures.u32(0u).copyInto(it, fixture.pointerStart + 4) },
            fixture.bytes.copyOf().also { GoogleFixtures.u32(UInt.MAX_VALUE).copyInto(it, fixture.pointerStart + 4) },
            fixture.bytes.copyOf().also { GoogleFixtures.u32(0u).copyInto(it, fixture.pointerStart + 8) },
            fixture.bytes.copyOf().also { GoogleFixtures.u32((GoogleFixtures.video().bytes.size + 1).toUInt()).copyInto(it, fixture.pointerStart + 8) },
            fixture.bytes + fixture.bytes.copyOfRange(fixture.mpvdStart, fixture.bytes.size),
        )
        for ((index, bytes) in bad.withIndex()) {
            when (val inspected = core.inspect(ReadRequest(input(bytes), context))) {
                is CoreResult.Failure -> assertTrue(inspected.error.code in setOf(IssueCode("SEF_DIRECTORY_INVALID"), IssueCode("AMBIGUOUS_LAYOUT")))
                is CoreResult.Success -> {
                    assertTrue(inspected.value.detection.matches.none { it.strength == MatchStrength.Strong }, "HEIC malformed index=$index")
                    assertTrue(inspected.value.issues.any { it.severity == Severity.Error })
                }
            }
        }
    }

    @Test
    fun ftypAndMpvdWithoutImageItemTablesCannotBeCertifiedAsValidHeic(): Unit = runImmediate {
        val onlyBrandAndVideo = GoogleFixtures.box("ftyp", "heic".encodeToByteArray() + GoogleFixtures.u32(0u) + "heicmif1".encodeToByteArray()) + GoogleFixtures.box("mpvd", GoogleFixtures.video().bytes)
        when (val inspected = core.inspect(ReadRequest(input(onlyBrandAndVideo), context))) {
            is CoreResult.Failure -> assertTrue(inspected.error.code in setOf(IssueCode("CORRUPTED_CONTAINER"), IssueCode("SEF_DIRECTORY_INVALID"), IssueCode("AMBIGUOUS_LAYOUT")))
            is CoreResult.Success -> assertTrue(inspected.value.detection.matches.none { it.strength == MatchStrength.Strong })
        }
        val transaction = MemoryOutputTransaction(context, "samsung-heic-no-image-clean")
        assertIs<CoreResult.Failure>(core.split(SplitRequest(input(onlyBrandAndVideo), output = transaction, context = context)))
        assertTrue(transaction.committedAssets().isEmpty())
    }

    private fun fixture(relative: Boolean = false, nested: Boolean = true, shared: Boolean = false): Fixture {
        val ftyp = GoogleFixtures.box("ftyp", "heic".encodeToByteArray() + GoogleFixtures.u32(0u) + "heicmif1".encodeToByteArray())
        val codedStill = GoogleFixtures.video(hevc = true).samples.first()
        fun meta(imageOffset: UInt): ByteArray {
            val handler = GoogleFixtures.fullBox("hdlr", GoogleFixtures.u32(0u) + "pict".encodeToByteArray() + ByteArray(12) + byteArrayOf(0))
            val primary = GoogleFixtures.fullBox("pitm", GoogleFixtures.bytes(0, 1))
            val item = GoogleFixtures.box("infe", GoogleFixtures.bytes(2, 0, 0, 0, 0, 1, 0, 0) + "hvc1Primary\u0000".encodeToByteArray())
            val secondItem = if (shared) GoogleFixtures.box("infe", GoogleFixtures.bytes(2, 0, 0, 1, 0, 2, 0, 0) + "hvc1Shared\u0000".encodeToByteArray()) else byteArrayOf()
            val info = GoogleFixtures.fullBox("iinf", GoogleFixtures.bytes(0, if (shared) 2 else 1) + item + secondItem)
            fun location(id: Int) = GoogleFixtures.bytes(0, id, 0, 0, 0, 1) + GoogleFixtures.u32(imageOffset) + GoogleFixtures.u32(codedStill.size.toUInt())
            val locations = GoogleFixtures.fullBox("iloc", GoogleFixtures.bytes(0x44, 0, 0, if (shared) 2 else 1) + location(1) + if (shared) location(2) else byteArrayOf())
            val properties = GoogleFixtures.box("ipco", GoogleFixtures.fullBox("ispe", GoogleFixtures.u32(1u) + GoogleFixtures.u32(1u)) + GoogleFixtures.box("hvcC", GoogleFixtures.video(hevc = true).configuration))
            val associations = GoogleFixtures.fullBox("ipma", GoogleFixtures.u32(if (shared) 2u else 1u) + GoogleFixtures.bytes(0, 1, 2, 0x81, 0x82) + if (shared) GoogleFixtures.bytes(0, 2, 2, 0x81, 0x82) else byteArrayOf())
            return GoogleFixtures.fullBox("meta", handler + primary + info + locations + GoogleFixtures.box("iprp", properties + associations))
        }
        val prefix = ftyp + meta((ftyp.size + meta(0u).size + 8).toUInt()) + GoogleFixtures.box("mdat", codedStill)
        val video = GoogleFixtures.video().bytes
        val pointer = "mpv2".encodeToByteArray() + GoogleFixtures.u32(if (relative) 8u else (prefix.size + 8).toUInt()) + GoogleFixtures.u32(video.size.toUInt())
        val suffix = SamsungFixtures.trailer(listOf(SamsungFixtures.Record(0x0a30, "MotionPhoto_Data", pointer), SamsungFixtures.Record(0x0a31, "MotionPhoto_Version", "mpv3".encodeToByteArray())))
        val sefd = GoogleFixtures.box("sefd", suffix)
        val bytes = if (nested) prefix + GoogleFixtures.box("mpvd", video + sefd) else prefix + GoogleFixtures.box("mpvd", video) + sefd
        val pointerStart = prefix.size + 8 + video.size + 8 + 24
        return Fixture(bytes, prefix.size + 8, pointerStart, prefix.size)
    }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
