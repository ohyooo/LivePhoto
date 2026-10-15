package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.BinaryReader
import livephoto.core.bmff.BmffVideoProbe
import livephoto.core.jpeg.JpegParser
import livephoto.core.memory.*
import livephoto.core.xmp.XmpReader
import kotlin.test.*

class GoogleCoreMutationTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun input(bytes: ByteArray) = SourceSet.Single(source(bytes, "carrier"))

    @Test
    fun createBothProfilesClosesFormalLoopWithByteExactOriginalVideoAndImmutableInputs(): Unit = runImmediate {
        val ordinaryXmp = GoogleFixtures.xmpSegment("<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' xmlns:p='urn:ordinary' p:Copyright='keep &amp; preserve'><p:Opaque><rdf:RDF><rdf:Description xmlns:g='http://ns.google.com/photos/1.0/camera/' g:MotionPhoto='private-value'/></rdf:RDF></p:Opaque></rdf:Description></rdf:RDF>")
        val opaque = GoogleFixtures.segment(0xef, byteArrayOf(1, 2, 0xff.toByte(), 0xd9.toByte(), 9))
        val cover = GoogleFixtures.jpeg(ordinaryXmp + opaque)
        val video = GoogleFixtures.video().bytes
        for (protocol in listOf("google.microvideo.v1", "google.motionphoto.v2")) {
            val imageSource = source(cover, "create-image-$protocol")
            val videoSource = source(video, "create-video-$protocol")
            val transaction = MemoryOutputTransaction(context, "create-$protocol")
            val operation = value(core.create(CreateRequest(imageSource, videoSource, ProtocolSelector(ProtocolId(protocol)), output = transaction, context = context)))
            assertEquals(TransactionState.Committed, operation.output.receipt.state)
            assertTrue(operation.execution.none { it.transcoded || it.remuxed })
            assertEquals(Bytes(cover), value(imageSource.readAt(0uL, cover.size.toUInt())))
            assertEquals(Bytes(video), value(videoSource.readAt(0uL, video.size.toUInt())))
            val carrier = transaction.committedAssets().values.single().toByteArray()
            assertEquals(video.toList(), carrier.takeLast(video.size))
            assertTrue(contains(carrier, opaque))
            assertTrue(carrier.decodeToString().contains("private-value"))
            val resultSource = input(carrier)
            val found = value(core.detect(ReadRequest(resultSource, context)))
            assertEquals(Disposition.Live, found.disposition)
            assertEquals(ProtocolId(protocol), found.primaryProtocol?.protocol)
            val inspected = value(core.inspect(ReadRequest(resultSource, context)))
            val resource = inspected.layout.resources.single { it.kind == ResourceKind.Video }
            val extractedTxn = MemoryOutputTransaction(context, "created-extract-$protocol")
            val extracted = value(core.extract(ExtractRequest(resultSource, listOf(resource.id), inspected.snapshot, output = extractedTxn, context = context)))
            assertEquals(Bytes(video), extractedTxn.committedAssets().values.single())
            assertTrue(extracted.preservation.records.any { it.guarantee == Guarantee.ExactExtraction && it.outcome == GuaranteeOutcome.Verified })
            val reader = BinaryReader(resultSource.source, context)
            val jpeg = value(JpegParser.parse(reader))
            val xmp = value(XmpReader.readJpeg(reader, jpeg))
            assertEquals("keep & preserve", value(xmp.scalar("urn:ordinary", "Copyright")))
            if (protocol == "google.motionphoto.v2") assertEquals("1", value(xmp.scalar("http://ns.google.com/photos/1.0/camera/", "MotionPhotoVersion")))
            val analysis = value(core.analyze(AnalyzeRequest(resultSource, context = context)))
            assertEquals(Disposition.Live, analysis.inspection.detection.disposition)
            assertEquals(Coverage.Partial, analysis.validation.coverage)
        }
    }

    @Test
    fun cleanSplitDeletesOnlyOwnedFieldsAndPreservesOrdinaryXmpIccAndOpaqueAppBytes(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val ordinary = "<p:Copyright xmlns:p='urn:ordinary'>kept &amp; exact</p:Copyright><p:Private xmlns:p='urn:ordinary'><![CDATA[MotionPhoto ordinary]]></p:Private>"
        val opaque = GoogleFixtures.segment(0xee, byteArrayOf(7, 8, 9))
        val icc = GoogleFixtures.segment(0xe2, "ICC_PROFILE\u0000".encodeToByteArray() + byteArrayOf(1, 1, 11, 12))
        for (v2 in listOf(false, true)) {
            val xmlCarrier = if (v2) GoogleFixtures.v2Photo(video, extra = ordinary) else GoogleFixtures.v1Photo(video, extra = ordinary)
            val carrier = xmlCarrier.copyOfRange(0, 2) + opaque + icc + xmlCarrier.copyOfRange(2, xmlCarrier.size)
            val transaction = MemoryOutputTransaction(context, "clean-$v2")
            val operation = value(core.split(SplitRequest(input(carrier), output = transaction, context = context)))
            assertEquals(2, operation.output.assets.size)
            assertTrue(operation.preservation.changes.any { change -> change.selector.contains("http://ns.google.com/photos/1.0/camera/") && change.after == null })
            assertTrue(operation.preservation.changes.none { it.selector.contains("urn:ordinary") })
            val imageId = operation.output.assets.single { it.role == AssetRole.PrimaryImage }.id
            val videoId = operation.output.assets.single { it.role == AssetRole.MotionVideo }.id
            assertEquals(Bytes(video), transaction.committedAssets()[videoId])
            val image = transaction.committedAssets().getValue(imageId).toByteArray()
            assertTrue(contains(image, opaque)); assertTrue(contains(image, icc))
            val cleanSource = source(image, "clean-image-$v2")
            assertEquals(Disposition.NonLive, value(core.detect(ReadRequest(SourceSet.Single(cleanSource), context))).disposition)
            val reader = BinaryReader(cleanSource, context)
            val jpeg = value(JpegParser.parse(reader))
            assertEquals(image.size.toULong(), jpeg.primary.length)
            assertEquals(0uL, jpeg.trailing.length)
            val packet = value(XmpReader.readJpeg(reader, jpeg))
            assertEquals("kept & exact", value(packet.scalar("urn:ordinary", "Copyright")))
            assertEquals("MotionPhoto ordinary", value(packet.scalar("urn:ordinary", "Private")))
            for (field in listOf("MicroVideo", "MicroVideoOffset", "MotionPhoto", "MotionPhotoVersion")) assertNull(value(packet.scalar("http://ns.google.com/photos/1.0/camera/", field)))
            val again = MemoryOutputTransaction(context, "clean-idempotent-$v2")
            val secondClean = value(core.split(SplitRequest(SourceSet.Single(cleanSource), output = again, context = context)))
            assertEquals(1, secondClean.output.assets.size)
            assertEquals(Bytes(image), again.committedAssets().values.single())
        }
    }

    @Test
    fun rawSplitRetainsProtocolBearingJpegWhileCleanSplitRemovesItsBindings(): Unit = runImmediate {
        val carrier = GoogleFixtures.v2Photo()
        val transaction = MemoryOutputTransaction(context, "raw-split")
        val operation = value(core.split(SplitRequest(input(carrier), mode = SplitMode.Raw, output = transaction, context = context)))
        val image = transaction.committedAssets().getValue(operation.output.assets.single { it.role == AssetRole.PrimaryImage }.id)
        val video = transaction.committedAssets().getValue(operation.output.assets.single { it.role == AssetRole.MotionVideo }.id)
        assertEquals(Bytes(GoogleFixtures.video().bytes), video)
        assertEquals(Bytes(carrier.copyOfRange(0, carrier.size - video.size)), image)
        assertTrue(image.toByteArray().decodeToString().contains("MotionPhotoVersion"))
    }

    @Test
    fun unknownAuxiliaryCannotBeSilentlyLostDuringCleanSplit(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val auxiliary = byteArrayOf(11, 22, 33, 44)
        val primary = "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='image/jpeg' item:Semantic='Primary' item:Padding='0'/></rdf:li>"
        val aux = "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='application/x-private' item:Semantic='VendorAux' item:Length='4'/></rdf:li>"
        val motion = "<rdf:li rdf:parseType='Resource'><container:Item item:Mime='video/mp4' item:Semantic='MotionPhoto' item:Length='${video.size}'/></rdf:li>"
        val carrier = GoogleFixtures.jpeg(GoogleFixtures.xmpSegment(GoogleFixtures.v2Xml(video.size, directory = primary + aux + motion))) + auxiliary + video
        val inspected = value(core.inspect(ReadRequest(input(carrier), context)))
        assertTrue(inspected.layout.resources.any { resource -> resource.kind == ResourceKind.Unknown && resource.extents.any { it.range.length == 4uL } })
        val transaction = MemoryOutputTransaction(context, "unknown-aux")
        when (val split = core.split(SplitRequest(input(carrier), output = transaction, context = context))) {
            is CoreResult.Failure -> assertTrue(transaction.committedAssets().isEmpty())
            is CoreResult.Success -> {
                assertTrue(transaction.committedAssets().values.any { contains(it.toByteArray(), auxiliary) })
                assertTrue(split.value.preservation.records.none { it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Verified } || split.value.output.assets.size >= 3)
            }
        }
    }

    @Test
    fun unresolvedMpfExifAndExtendedPacketDependenciesBlockCleanInsteadOfDroppingMetadata(): Unit = runImmediate {
        val protectedSegments = listOf(
            GoogleFixtures.segment(0xe2, "MPF\u0000".encodeToByteArray() + byteArrayOf(1, 2, 3, 4)),
            GoogleFixtures.segment(0xe1, "Exif\u0000\u0000".encodeToByteArray() + GoogleFixtures.bytes(0x49, 0x49, 42, 0, 8, 0, 0, 0, 1, 0, 0x7c, 0x92, 7, 0, 4, 0, 0, 0, 1, 2, 3, 4, 0, 0, 0, 0)),
            GoogleFixtures.segment(0xe1, "http://ns.adobe.com/xmp/extension/\u0000".encodeToByteArray() + "0123456789ABCDEF0123456789ABCDEF".encodeToByteArray() + GoogleFixtures.u32(2u) + GoogleFixtures.u32(0u) + byteArrayOf(1)),
        )
        val base = GoogleFixtures.v2Photo()
        val appLength = ((base[4].toInt() and 255) shl 8) or (base[5].toInt() and 255)
        val firstXmpEnd = 4 + appLength
        for ((index, segment) in protectedSegments.withIndex()) {
            // EXIF follows the rewritten packet here: shrinking XMP actually moves its base.
            val carrier = base.copyOfRange(0, firstXmpEnd) + segment + base.copyOfRange(firstXmpEnd, base.size)
            val transaction = MemoryOutputTransaction(context, "protected-$index")
            assertIs<CoreResult.Failure>(core.split(SplitRequest(input(carrier), output = transaction, context = context)), "Protected segment index=$index follows rewritten XMP")
            assertTrue(transaction.committedAssets().isEmpty())
        }
        val exif = protectedSegments[1]
        val stationary = base.copyOfRange(0, 2) + exif + base.copyOfRange(2, base.size)
        val bestEffort = MemoryOutputTransaction(context, "stationary-exif-best-effort")
        val result = value(core.split(SplitRequest(input(stationary), output = bestEffort, context = context)))
        val image = result.output.assets.single { it.role == AssetRole.PrimaryImage }
        assertContentEquals(exif, bestEffort.committedAssets().getValue(image.id).toByteArray().copyOfRange(2, 2 + exif.size))
        assertTrue(result.preservation.records.any { it.assetId == image.id && it.guarantee == Guarantee.MetadataPreserving && it.outcome == GuaranteeOutcome.Unknown })
        for ((index, policy) in listOf(MutationPolicy(preservation = PreservationPolicy.Strict), MutationPolicy(requiredGuarantees = listOf(Guarantee.MetadataPreserving))).withIndex()) {
            val transaction = MemoryOutputTransaction(context, "stationary-exif-required-$index")
            assertIs<CoreResult.Failure>(core.split(SplitRequest(input(stationary), policy = policy, output = transaction, context = context)), "Unverified MakerNote metadata policy index=$index")
            assertTrue(transaction.committedAssets().isEmpty())
        }
    }

    @Test
    fun unimplementedTargetAndIncompatiblePreferenceCannotPublishRenamedBytes(): Unit = runImmediate {
        for ((selector, preference) in listOf(
            ProtocolSelector(ProtocolId("google.motionphoto.v2"), ProfileId("heic")) to MediaPreference(),
            ProtocolSelector(ProtocolId("google.motionphoto.v2"), ProfileId("unknown")) to MediaPreference(),
            ProtocolSelector(ProtocolId("apple.livephoto")) to MediaPreference(),
            ProtocolSelector(ProtocolId("google.motionphoto.v2")) to MediaPreference(videoCodec = VideoCodec.Hevc),
        )) {
            val transaction = MemoryOutputTransaction(context, "unsupported-${selector.protocol.value}-${selector.profile?.value}-${preference.videoCodec}")
            assertIs<CoreResult.Failure>(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "cover"), source(GoogleFixtures.video().bytes, "video"), selector, preference, output = transaction, context = context)))
            assertTrue(transaction.committedAssets().isEmpty())
        }
    }

    @Test
    fun aacActualConfigurationMustAgreeWithMp4aHeaderBeforeCreatePublishes(): Unit = runImmediate {
        val valid = GoogleFixtures.video(aac = true)
        val movie = value(BmffVideoProbe(BinaryReader(source(valid.bytes, "aac-valid"), context)).probe(ByteRange(0uL, valid.bytes.size.toULong())))
        val audio = movie.tracks.single { it.handler == "soun" }
        assertEquals(48000u, audio.audioActualSampleRate)
        assertEquals(2u, audio.audioActualChannelCount)
        val accepted = MemoryOutputTransaction(context, "aac-valid-create")
        value(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "aac-cover"), source(valid.bytes, "aac-video"), ProtocolSelector(ProtocolId("google.motionphoto.v2")), output = accepted, context = context)))
        assertEquals(1, accepted.committedAssets().size)
        for ((index, asc) in listOf(GoogleFixtures.bytes(0x12, 0x90), GoogleFixtures.bytes(0x11, 0xb8)).withIndex()) {
            val invalid = GoogleFixtures.video(aac = true, audioConfig = asc).bytes
            val probe = assertIs<CoreResult.Failure>(BmffVideoProbe(BinaryReader(source(invalid, "aac-invalid-$index"), context)).probe(ByteRange(0uL, invalid.size.toULong())))
            assertEquals(IssueCode("CORRUPTED_CONTAINER"), probe.error.code)
            val transaction = MemoryOutputTransaction(context, "aac-rejected-$index")
            assertIs<CoreResult.Failure>(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "aac-bad-cover"), source(invalid, "aac-bad-video"), ProtocolSelector(ProtocolId("google.motionphoto.v2")), output = transaction, context = context)))
            assertTrue(transaction.committedAssets().isEmpty())
        }
    }

    @Test
    fun requestedKeySelectionUsesActualPresentationSamplesAndEarlierNearestTie(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val cases = listOf(
            CoverPosition.Timestamp(Time(20, 1000u), Selection.Nearest, Time(20, 1000u)) to 0L,
            CoverPosition.Timestamp(Time(30, 1000u), Selection.AtOrBefore, Time(30, 1000u)) to 0L,
            CoverPosition.Timestamp(Time(40, 1000u), Selection.Exact) to 40L,
            CoverPosition.Timestamp(Time(80, 2000u), Selection.Exact) to 40L,
            CoverPosition.FrameIndex(1uL) to 40L,
        )
        for ((index, entry) in cases.withIndex()) {
            val transaction = MemoryOutputTransaction(context, "key-selection-$index")
            val operation = value(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "key-cover"), source(video, "key-video"), ProtocolSelector(ProtocolId("google.motionphoto.v2")), edits = EditSpec(keyPosition = entry.first), output = transaction, context = context)))
            assertEquals(0, requireNotNull(operation.keyPhoto?.position).compareTo(Time(entry.second, 1000u)))
            val key = value(core.getKeyPhotoPosition(ReadRequest(input(transaction.committedAssets().values.single().toByteArray()), context)))
            assertEquals(Time(entry.second * 1000, 1_000_000u), key.position)
        }
        val composition = GoogleFixtures.box("ctts", byteArrayOf(1, 0, 0, 0) + GoogleFixtures.u32(2u) +
            GoogleFixtures.u32(1u) + GoogleFixtures.u32(50u) + GoogleFixtures.u32(1u) + GoogleFixtures.u32((-30).toUInt()))
        for ((index, expected) in listOf(10L, 50L).withIndex()) {
            val transaction = MemoryOutputTransaction(context, "bframe-index-$index")
            val operation = value(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "bframe-cover"), source(GoogleFixtures.video(composition = composition).bytes, "bframe-video"), ProtocolSelector(ProtocolId("google.motionphoto.v2")), edits = EditSpec(keyPosition = CoverPosition.FrameIndex(index.toULong())), output = transaction, context = context)))
            assertEquals(0, requireNotNull(operation.keyPhoto?.position).compareTo(Time(expected, 1000u)))
        }
    }

    @Test
    fun invalidSelectionAndUnrepresentableMicrosecondsFailWhileDefaultIsDisclosed(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        for ((index, position) in listOf(
            CoverPosition.Timestamp(Time(20, 1000u), Selection.Exact),
            CoverPosition.Timestamp(Time(20, 1000u), Selection.Nearest),
            CoverPosition.Timestamp(Time(80, 1000u), Selection.Exact),
            CoverPosition.FrameIndex(2uL),
        ).withIndex()) {
            val transaction = MemoryOutputTransaction(context, "invalid-key-$index")
            assertIs<CoreResult.Failure>(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "bad-key-cover"), source(video, "bad-key-video"), ProtocolSelector(ProtocolId("google.motionphoto.v2")), edits = EditSpec(keyPosition = position), output = transaction, context = context)))
            assertTrue(transaction.committedAssets().isEmpty())
        }
        val unrepresentable = video.copyOf()
        for (type in listOf("mvhd", "mdhd")) {
            val tag = type.encodeToByteArray()
            val index = (0..unrepresentable.size - 4).single { start -> tag.indices.all { unrepresentable[start + it] == tag[it] } }
            GoogleFixtures.u32(3000u).copyInto(unrepresentable, index + 4 + 12)
        }
        val rejected = MemoryOutputTransaction(context, "fractional-microseconds")
        val error = assertIs<CoreResult.Failure>(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "fraction-cover"), source(unrepresentable, "fraction-video"), ProtocolSelector(ProtocolId("google.motionphoto.v2")), edits = EditSpec(keyPosition = CoverPosition.FrameIndex(1uL)), output = rejected, context = context))).error
        assertEquals(IssueCode("VALUE_NOT_REPRESENTABLE"), error.code)
        assertTrue(rejected.committedAssets().isEmpty())
        val transaction = MemoryOutputTransaction(context, "default-key-disclosed")
        val operation = value(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "default-cover"), source(video, "default-video"), ProtocolSelector(ProtocolId("google.motionphoto.v2")), output = transaction, context = context)))
        assertEquals(KeySource.DerivedDefault, operation.keyPhoto?.source)
        assertNull(operation.keyPhoto?.frameIndex)
        assertEquals(0, requireNotNull(operation.keyPhoto?.position).compareTo(Time(40, 1000u)))
    }

    @Test
    fun createPlanBindsBothInputIdentitiesWithoutCreatingAnyOutput(): Unit = runImmediate {
        val image = source(GoogleFixtures.jpeg(), "plan-image")
        val video = source(GoogleFixtures.video().bytes, "plan-video")
        val transaction = MemoryOutputTransaction(context, "plan-only")
        val plan = value(core.plan(CreateRequest(image, video, ProtocolSelector(ProtocolId("google.motionphoto.v2")), output = transaction, context = context)))
        assertEquals(setOf(SourceId("plan-image"), SourceId("plan-video")), plan.snapshot.identities.map { it.id }.toSet())
        assertEquals(2, plan.snapshot.identities.size)
        assertTrue(plan.snapshot.identities.all { it.digest != null })
        assertEquals(TransactionState.Open, value(transaction.query()).state)
        assertTrue(value(transaction.query()).assetIds.isEmpty())
        assertTrue(transaction.committedAssets().isEmpty())
        value(image.identity()); value(video.identity())
    }

    @Test
    fun alreadyLiveCoverAndUnrequestedTrimAreRejectedBeforePublication(): Unit = runImmediate {
        val target = ProtocolSelector(ProtocolId("google.motionphoto.v2"))
        for ((image, edits) in listOf(GoogleFixtures.v2Photo() to null, GoogleFixtures.jpeg() to EditSpec(trim = TrimSpec(TimeRange(Time.Zero, Time(40, 1000u)))))) {
            val transaction = MemoryOutputTransaction(context, "reject-edits-${image.size}")
            assertIs<CoreResult.Failure>(core.create(CreateRequest(source(image, "cover"), source(GoogleFixtures.video().bytes, "video"), target, edits = edits, output = transaction, context = context)))
            assertTrue(transaction.committedAssets().isEmpty())
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { start -> needle.indices.all { haystack[start + it] == needle[it] } }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
