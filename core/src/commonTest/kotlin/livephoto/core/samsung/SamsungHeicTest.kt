package livephoto.core.samsung

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.implementation.ExtentSource
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
    fun multipleNoncontiguousExtentsExtractOnlyDeclaredItemBytesAndPlanningDoesNotPublish(): Unit = runImmediate {
        val bytes = fixture(multiple = true).bytes
        val inspected = value(core.inspect(ReadRequest(input(bytes), context)))
        val item = inspected.layout.resources.single { it.id == ResourceId("heif:item:1") }
        assertEquals(2, item.extents.size)
        assertFalse(item.standalone)
        assertEquals(3uL, item.extents[1].range.offset - item.extents[0].range.endExclusive)
        val output = MemoryOutputTransaction(context, "heif-multi-raw")
        val request = ExtractRequest(input(bytes), listOf(item.id), inspected.snapshot, output = output, context = context)
        value(core.plan(request))
        assertTrue(output.committedAssets().isEmpty())
        val result = value(core.extract(request))
        assertEquals(Bytes(GoogleFixtures.video(hevc = true).samples.first()), output.committedAssets().values.single())
        assertEquals("application/octet-stream", result.output.assets.single().mime)
        assertTrue(result.preservation.records.any { it.guarantee == Guarantee.ExactExtraction && it.outcome == GuaranteeOutcome.Verified })
        assertTrue(result.preservation.records.none { it.guarantee == Guarantee.ImageDataPreserving && it.outcome == GuaranteeOutcome.Verified })
    }

    @Test
    fun oversizedMultiExtentItemIsRejectedDuringPlanAndBeforeAnyStaging(): Unit = runImmediate {
        val bytes = fixture(multiple = true).bytes
        val limitedContext = context.copy(limits = context.limits.copy(maxOutputBytes = 4uL))
        val output = MemoryOutputTransaction(limitedContext, "heif-multi-budget")
        val request = ExtractRequest(input(bytes), listOf(ResourceId("heif:item:1")), output = output, context = limitedContext)
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(core.plan(request)).error.code)
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(core.extract(request)).error.code)
        assertTrue(value(output.query()).assetIds.isEmpty())
    }

    @Test
    fun stagedReaderCannotAliasTheDerivedMultiExtentInputView(): Unit = runImmediate {
        val source = input(fixture(multiple = true).bytes)
        val inspected = value(core.inspect(ReadRequest(source, context)))
        val item = inspected.layout.resources.single { it.id == ResourceId("heif:item:1") }
        val alias = ExtentSource.create(BinaryReader(source.source, context), item.extents.map { it.range }).orThrow()
        val base = MemoryOutputTransaction(context, "heif-derived-alias")
        val output = object : OutputTransaction by base {
            override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> = CoreResult.Success(alias)
        }
        val result = core.extract(ExtractRequest(source, listOf(item.id), inspected.snapshot, output = output, context = context))
        assertEquals(IssueCode("OUTPUT_ALIASES_INPUT"), assertIs<CoreResult.Failure>(result).error.code)
        assertEquals(TransactionState.Aborted, value(base.query()).state)
        assertTrue(base.committedAssets().isEmpty())
        assertEquals(source.source.identity().orThrow().size, source.source.size().orThrow())
    }

    @Test
    fun codedPrimaryReportsDeclaredDimensionsButNotCompleteDecodeOrImageGraphCoverage(): Unit = runImmediate {
        for (multiple in listOf(false, true)) for (shared in listOf(false, true)) {
            val inspected = value(core.inspect(ReadRequest(input(fixture(multiple = multiple, shared = shared).bytes), context)))
            assertEquals(1u, inspected.media.first().width)
            assertEquals(1u, inspected.media.first().height)
            assertEquals(Coverage.Partial, inspected.media.first().coverage)
            assertTrue(inspected.detection.matches.none { it.strength == MatchStrength.Strong })
            assertTrue(inspected.issues.any { it.code == IssueCode("CAPABILITY_UNSUPPORTED") && it.layer == Layer.Structure })
        }
    }

    @Test
    fun corruptPrimaryNalAndDimensionsAreReportedWhileRawItemRemainsByteExact(): Unit = runImmediate {
        val fixture = fixture()
        val reader = BinaryReader(MemoryBinarySource(Bytes(fixture.bytes), SourceId("heif-coded-corruption")), context)
        val roots = BmffReader(reader).readBoxes(ByteRange(0uL, fixture.bytes.size.toULong())).orThrow()
        val graph = livephoto.core.heif.HeifItemGraphReader.read(reader, roots).orThrow()
        val sample = graph.locations.items.single().extents.single().data
        val dimensions = graph.properties.single { it.type == "ispe" }.payload
        val cases = listOf(
            fixture.bytes.copyOf().also { GoogleFixtures.u32(0u).copyInto(it, dimensions.offset.toInt() + 4) },
            fixture.bytes.copyOf().also { it[sample.offset.toInt() + 4] = 0x80.toByte() },
            fixture.bytes.copyOf().also { it[sample.offset.toInt() + 5] = 0 },
            fixture.bytes.copyOf().also { GoogleFixtures.u32(9u).copyInto(it, sample.offset.toInt()) })
        for ((index, bytes) in cases.withIndex()) {
            val inspected = value(core.inspect(ReadRequest(input(bytes), context)))
            assertNull(inspected.media.first().width)
            assertTrue(inspected.issues.any { it.layer == Layer.Media && it.severity == Severity.Error })
            val raw = MemoryOutputTransaction(context, "heif-coded-invalid-$index")
            value(core.extract(ExtractRequest(input(bytes), listOf(ResourceId("heif:item:1")), inspected.snapshot, output = raw, context = context)))
            assertEquals(Bytes(bytes.copyOfRange(sample.offset.toInt(), sample.endExclusive.toInt())), raw.committedAssets().values.single())
        }
    }

    @Test
    fun unimplementedConfigurationVersionDoesNotBecomeDecodedDimensions(): Unit = runImmediate {
        val fixture = fixture()
        val reader = BinaryReader(MemoryBinarySource(Bytes(fixture.bytes), SourceId("heif-coded-version")), context)
        val roots = BmffReader(reader).readBoxes(ByteRange(0uL, fixture.bytes.size.toULong())).orThrow()
        val graph = livephoto.core.heif.HeifItemGraphReader.read(reader, roots).orThrow()
        val configuration = graph.properties.single { it.type == "hvcC" }.payload
        val bytes = fixture.bytes.copyOf().also { it[configuration.offset.toInt()] = 2 }
        val inspected = value(core.inspect(ReadRequest(input(bytes), context)))
        assertNull(inspected.media.first().width)
        assertEquals(Coverage.Partial, inspected.media.first().coverage)
        assertTrue(inspected.issues.any { it.code == IssueCode("UNSUPPORTED_CONTAINER") && it.layer == Layer.Media && it.severity == Severity.Warning })
    }

    @Test
    fun plainHeicUsesTheSameItemInspectionAndRawWithoutPretendingToBeAMotionPhoto(): Unit = runImmediate {
        for (multiple in listOf(false, true)) {
            val fixture = fixture(multiple = multiple)
            val bytes = fixture.bytes.copyOfRange(0, fixture.mpvdStart)
            val inspected = value(core.inspect(ReadRequest(input(bytes), context)))
            assertEquals(Disposition.NonLive, inspected.detection.disposition)
            assertTrue(inspected.detection.matches.isEmpty())
            assertEquals(ImageFormat.Heic, inspected.media.single().imageFormat)
            assertEquals(1u, inspected.media.single().width)
            assertEquals(Coverage.Partial, inspected.media.single().coverage)
            val item = inspected.layout.resources.single { it.id == ResourceId("heif:item:1") }
            assertFalse(item.standalone)
            val output = MemoryOutputTransaction(context, "plain-heic-item-$multiple")
            val request = ExtractRequest(input(bytes), listOf(item.id), inspected.snapshot, output = output, context = context)
            val plan = value(core.plan(request))
            assertEquals(Availability.Conditional, plan.capabilities.availability)
            assertTrue(output.committedAssets().isEmpty())
            value(core.extract(request))
            assertEquals(Bytes(GoogleFixtures.video(hevc = true).samples.first()), output.committedAssets().values.single())
        }
    }

    @Test
    fun multiItemOrUnknownPrivateDependenciesDoNotBecomeCertifiedPlainImages(): Unit = runImmediate {
        val fixture = fixture(shared = true)
        val bytes = fixture.bytes.copyOfRange(0, fixture.mpvdStart)
        val inspected = value(core.inspect(ReadRequest(input(bytes), context)))
        assertEquals(Disposition.Unknown, inspected.detection.disposition)
        assertEquals(2, inspected.layout.resources.size)
        assertTrue(inspected.layout.resources.all { !it.standalone })
        assertTrue(inspected.issues.any { it.code == IssueCode("CAPABILITY_UNSUPPORTED") && it.layer == Layer.Structure })
        val extended = bytes + GoogleFixtures.box("priv", byteArrayOf(0, 1, 2))
        val other = value(core.inspect(ReadRequest(input(extended), context)))
        assertEquals(Disposition.Unknown, other.detection.disposition)
        assertTrue(other.detection.matches.isEmpty())
    }

    @Test
    fun plainHeicRawCarrierKeepsEveryByteAndItemCodestreamIsNotARebuiltImage(): Unit = runImmediate {
        val fixture = fixture(multiple = true)
        val bytes = fixture.bytes.copyOfRange(0, fixture.mpvdStart)
        val output = MemoryOutputTransaction(context, "plain-heic-whole-raw")
        val result = value(core.extract(ExtractRequest(input(bytes), emptyList(), includeRawCarrier = true, output = output, context = context)))
        assertEquals(Bytes(bytes), output.committedAssets().values.single())
        assertEquals(AssetRole.Composite, result.output.assets.single().role)
        assertTrue(result.preservation.records.all { it.outcome in setOf(GuaranteeOutcome.Verified, GuaranteeOutcome.NotApplicable) })
        val split = MemoryOutputTransaction(context, "plain-heic-clean-not-implemented")
        assertIs<CoreResult.Failure>(core.split(SplitRequest(input(bytes), output = split, context = context)))
        assertTrue(split.committedAssets().isEmpty())
    }

    @Test
    fun heicBrandAloneCannotSupplyAnImageItemGraph(): Unit = runImmediate {
        val bytes = GoogleFixtures.box("ftyp", "heic".encodeToByteArray() + GoogleFixtures.u32(0u) + "heicmif1".encodeToByteArray()) + GoogleFixtures.box("mdat", byteArrayOf(0, 1, 2, 3))
        val inspected = value(core.inspect(ReadRequest(input(bytes), context)))
        assertEquals(Disposition.Unknown, inspected.detection.disposition)
        assertTrue(inspected.media.isEmpty())
        assertTrue(inspected.layout.resources.isEmpty())
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

    private fun fixture(relative: Boolean = false, nested: Boolean = true, shared: Boolean = false, multiple: Boolean = false): Fixture {
        val ftyp = GoogleFixtures.box("ftyp", "heic".encodeToByteArray() + GoogleFixtures.u32(0u) + "heicmif1".encodeToByteArray())
        val codedStill = GoogleFixtures.video(hevc = true).samples.first()
        fun meta(imageOffset: UInt): ByteArray {
            val handler = GoogleFixtures.fullBox("hdlr", GoogleFixtures.u32(0u) + "pict".encodeToByteArray() + ByteArray(12) + byteArrayOf(0))
            val primary = GoogleFixtures.fullBox("pitm", GoogleFixtures.bytes(0, 1))
            val item = GoogleFixtures.box("infe", GoogleFixtures.bytes(2, 0, 0, 0, 0, 1, 0, 0) + "hvc1Primary\u0000".encodeToByteArray())
            val secondItem = if (shared) GoogleFixtures.box("infe", GoogleFixtures.bytes(2, 0, 0, 1, 0, 2, 0, 0) + "hvc1Shared\u0000".encodeToByteArray()) else byteArrayOf()
            val info = GoogleFixtures.fullBox("iinf", GoogleFixtures.bytes(0, if (shared) 2 else 1) + item + secondItem)
            fun location(id: Int): ByteArray {
                val extents = if (multiple) GoogleFixtures.u32(imageOffset) + GoogleFixtures.u32(2u) + GoogleFixtures.u32(imageOffset + 5u) + GoogleFixtures.u32((codedStill.size - 2).toUInt())
                    else GoogleFixtures.u32(imageOffset) + GoogleFixtures.u32(codedStill.size.toUInt())
                return GoogleFixtures.bytes(0, id, 0, 0, 0, if (multiple) 2 else 1) + extents
            }
            val locations = GoogleFixtures.fullBox("iloc", GoogleFixtures.bytes(0x44, 0, 0, if (shared) 2 else 1) + location(1) + if (shared) location(2) else byteArrayOf())
            val properties = GoogleFixtures.box("ipco", GoogleFixtures.fullBox("ispe", GoogleFixtures.u32(1u) + GoogleFixtures.u32(1u)) + GoogleFixtures.box("hvcC", GoogleFixtures.video(hevc = true).configuration))
            val associations = GoogleFixtures.fullBox("ipma", GoogleFixtures.u32(if (shared) 2u else 1u) + GoogleFixtures.bytes(0, 1, 2, 0x81, 0x82) + if (shared) GoogleFixtures.bytes(0, 2, 2, 0x81, 0x82) else byteArrayOf())
            return GoogleFixtures.fullBox("meta", handler + primary + info + locations + GoogleFixtures.box("iprp", properties + associations))
        }
        val stillData = if (multiple) codedStill.copyOfRange(0, 2) + GoogleFixtures.bytes(0xa5, 0x5a, 0xff) + codedStill.copyOfRange(2, codedStill.size) else codedStill
        val prefix = ftyp + meta((ftyp.size + meta(0u).size + 8).toUInt()) + GoogleFixtures.box("mdat", stillData)
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
