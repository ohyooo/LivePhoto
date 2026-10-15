package livephoto.core.legacy

import livephoto.core.*
import livephoto.core.binary.TestSource
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import livephoto.core.oplus.OplusFixtures
import kotlin.test.*

class VivoLegacyCoreTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolId("vivo.legacy-pair"), ProfileId("pair"))
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private fun pair(fixture: LegacyFixtures.Pair) = SourceSet.Pair(source(fixture.image, "different-image-name"), source(fixture.video, "different-video-name"))

    @Test
    fun matchingIdsBindTwoIndependentSourcesWithLegacyLifecycle(): Unit = runImmediate {
        val fixture = LegacyFixtures.pair()
        val inspected = value(core.inspect(ReadRequest(pair(fixture), context)))
        assertEquals(target, inspected.detection.primaryProtocol)
        assertEquals(MatchStrength.Legacy, inspected.detection.matches.single { it.target == target }.strength)
        assertEquals(setOf(SourceId("different-image-name"), SourceId("different-video-name")), inspected.snapshot.identities.map { it.id }.toSet())
        assertEquals("pair-A", inspected.pairing?.imageIdentifier)
        assertEquals("pair-A", inspected.pairing?.videoIdentifier)
        assertEquals(true, inspected.pairing?.matches)
        assertTrue(inspected.layout.relationships.any { it.kind == RelationshipKind.PairedWith })
        assertNull(inspected.keyPhoto.position)
        assertTrue(inspected.keyPhoto.issues.any { it.code == IssueCode("TIMESTAMP_SEMANTICS_UNKNOWN") })
        assertTrue(value(core.validateProtocol(ValidationRequest(pair(fixture), target = target, context = context))).verdict != Verdict.Invalid)
    }

    @Test
    fun utf8IdentifiersAndJsonEscapesUseBytesInsteadOfCharacters(): Unit = runImmediate {
        for (id in listOf("照片-α", "id\"quoted\\slash")) {
            val fixture = LegacyFixtures.pair(id)
            val inspected = value(core.inspect(ReadRequest(pair(fixture), context)))
            assertEquals(id, inspected.pairing?.imageIdentifier)
            assertEquals(id, inspected.pairing?.videoIdentifier)
            val raw = MemoryOutputTransaction(context, "vivo-legacy-unicode-${id.length}")
            value(core.split(SplitRequest(pair(fixture), mode = SplitMode.Raw, output = raw, context = context)))
            assertEquals(setOf(Bytes(fixture.image), Bytes(fixture.video)), raw.committedAssets().values.toSet())
        }
        val id = "照片"
        val escaped = LegacyFixtures.pair(id, imageJson = "{\"${LegacyFixtures.vivoIdKey}\":\"\\u7167\\u7247\",\"com.android.camera.imageTime\":40}")
        assertEquals(id, value(core.inspect(ReadRequest(pair(escaped), context))).pairing?.imageIdentifier)
    }

    @Test
    fun rawPairKeepsBothFilesExactlyIncludingOwnedTailAndUuid(): Unit = runImmediate {
        val fixture = LegacyFixtures.pair()
        val input = pair(fixture)
        val inspected = value(core.inspect(ReadRequest(input, context)))
        val raw = MemoryOutputTransaction(context, "vivo-legacy-raw")
        val result = value(core.extract(ExtractRequest(input, resources = inspected.layout.resources.filter { it.kind in setOf(ResourceKind.PrimaryImage, ResourceKind.Video) }.map { it.id }, snapshot = inspected.snapshot, output = raw, context = context)))
        assertEquals(Bytes(fixture.image), raw.committedAssets()[result.output.assets.single { it.role == AssetRole.PrimaryImage }.id])
        assertEquals(Bytes(fixture.video), raw.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
        assertTrue(result.preservation.records.filter { it.guarantee == Guarantee.ExactExtraction }.all { it.outcome == GuaranteeOutcome.Verified })
    }

    @Test
    fun cleanRemovesEofUuidAndTailWithoutTouchingMediaOrOrdinaryExif(): Unit = runImmediate {
        val exif = OplusFixtures.exifSegment("ordinary vivo legacy note")
        val fixture = LegacyFixtures.pair(extraImage = exif)
        val raw = MemoryOutputTransaction(context, "vivo-legacy-clean")
        val result = value(core.split(SplitRequest(pair(fixture), output = raw, context = context)))
        assertEquals(Bytes(fixture.cleanImage), raw.committedAssets()[result.output.assets.single { it.role == AssetRole.PrimaryImage }.id])
        assertEquals(Bytes(fixture.cleanVideo), raw.committedAssets()[result.output.assets.single { it.role == AssetRole.MotionVideo }.id])
        assertTrue(result.preservation.records.any { it.guarantee == Guarantee.BitstreamPreserving && it.outcome == GuaranteeOutcome.Verified })
        assertTrue(result.preservation.changes.isNotEmpty())
        val ordinaryUuid = GoogleFixtures.box("uuid", ByteArray(16) { 0x61 } + "ordinary private metadata".encodeToByteArray())
        val withOrdinary = fixture.copy(video = fixture.cleanVideo + ordinaryUuid + fixture.uuid, cleanVideo = fixture.cleanVideo + ordinaryUuid)
        val preserved = MemoryOutputTransaction(context, "vivo-legacy-ordinary-uuid")
        val preservedResult = value(core.split(SplitRequest(pair(withOrdinary), output = preserved, context = context)))
        assertEquals(Bytes(withOrdinary.cleanVideo), preserved.committedAssets()[preservedResult.output.assets.single { it.role == AssetRole.MotionVideo }.id])
        val secondFailure = FailSecondAsset(MemoryOutputTransaction(context, "vivo-legacy-second-failure"))
        assertIs<CoreResult.Failure>(core.split(SplitRequest(pair(fixture), output = secondFailure, context = context)))
        assertEquals(2, secondFailure.createCalls)
        assertEquals(0, secondFailure.commitCalls)
        assertTrue(secondFailure.delegate.committedAssets().isEmpty())
        assertEquals(TransactionState.Aborted, value(secondFailure.delegate.query()).state)

        // A leading owned UUID needs stco/co64 relocation, which this safe EOF subset does not implement.
        val ftypSize = fixture.cleanVideo.take(4).fold(0) { size, byte -> (size shl 8) or (byte.toInt() and 255) }
        val leadingVideo = fixture.cleanVideo.copyOfRange(0, ftypSize) + fixture.uuid + fixture.cleanVideo.copyOfRange(ftypSize, fixture.cleanVideo.size)
        val stco = (0..leadingVideo.size - 4).single { offset -> leadingVideo.copyOfRange(offset, offset + 4).contentEquals("stco".encodeToByteArray()) }
        val offset = GoogleFixtures.video().sampleOffset + fixture.uuid.size.toULong()
        GoogleFixtures.u32(offset.toUInt()).copyInto(leadingVideo, stco + 12)
        val unsupported = CountingTransaction(MemoryOutputTransaction(context, "vivo-legacy-leading-uuid"))
        assertIs<CoreResult.Failure>(core.split(SplitRequest(SourceSet.Pair(source(fixture.image, "image"), source(leadingVideo, "video")), output = unsupported, context = context)))
        assertEquals(0, unsupported.commitCalls)
    }

    @Test
    fun mismatchingEmptyAndCaseDifferentIdentifiersCannotPublish(): Unit = runImmediate {
        for ((index, fixture) in listOf(LegacyFixtures.pair(videoId = "pair-B"), LegacyFixtures.pair(videoId = "PAIR-A"), LegacyFixtures.pair(id = ""), LegacyFixtures.pair(videoId = " pair-A")).withIndex()) {
            val transaction = CountingTransaction(MemoryOutputTransaction(context, "vivo-legacy-id-conflict-$index"))
            assertIs<CoreResult.Failure>(core.split(SplitRequest(pair(fixture), output = transaction, context = context)))
            assertEquals(0, transaction.commitCalls); assertTrue(transaction.delegate.committedAssets().isEmpty())
        }
        val fixture = LegacyFixtures.pair()
        assertIs<CoreResult.Failure>(core.inspect(ReadRequest(SourceSet.Pair(source(fixture.image, "same-source-id"), source(fixture.video, "same-source-id")), context)))
    }

    @Test
    fun malformedJsonLengthAndSignaturesDoNotBecomeTrustedPairs(): Unit = runImmediate {
        val fixture = LegacyFixtures.pair()
        val jSize = ("vivo" + LegacyFixtures.json("pair-A")).encodeToByteArray().size
        val badLength = fixture.image.copyOf().also { GoogleFixtures.u32(UInt.MAX_VALUE).copyInto(it, fixture.cleanImage.size + jSize) }
        val badIdLength = fixture.image.copyOf().also { GoogleFixtures.u32(UInt.MAX_VALUE).copyInto(it, fixture.cleanImage.size + jSize + 4 + 11) }
        val badSignature = fixture.image.copyOf().also { it[it.lastIndex] = 0 }
        val duplicate = LegacyFixtures.pair(imageJson = LegacyFixtures.json("pair-A", ",\"${LegacyFixtures.vivoIdKey}\":\"pair-B\""))
        val escapedDuplicate = LegacyFixtures.pair(imageJson = LegacyFixtures.json("pair-A", ",\"com.android.camera.\\u006civephoto\":\"pair-A\""))
        val malformed = LegacyFixtures.pair(imageJson = "{\"${LegacyFixtures.vivoIdKey}\":\"pair-A\",}")
        val invalidUnicode = LegacyFixtures.pair(imageJson = "{\"${LegacyFixtures.vivoIdKey}\":\"\\uD800\"}")
        for ((index, image) in listOf(badLength, badIdLength, badSignature, duplicate.image, escapedDuplicate.image, malformed.image, invalidUnicode.image).withIndex()) {
            val input = SourceSet.Pair(source(image, "bad-image"), source(fixture.video, "video"))
            val transaction = CountingTransaction(MemoryOutputTransaction(context, "vivo-legacy-bad-tail-$index"))
            assertIs<CoreResult.Failure>(core.split(SplitRequest(input, output = transaction, context = context)))
            assertEquals(0, transaction.commitCalls); assertTrue(transaction.delegate.committedAssets().isEmpty())
        }
    }

    @Test
    fun uuidAuthorityRequiresTopLevelUserTypeAndRejectsDuplicateOwners(): Unit = runImmediate {
        val fixture = LegacyFixtures.pair()
        val wrongType = GoogleFixtures.box("uuid", ByteArray(16) { 0x33 } + fixture.tail)
        val hidden = GoogleFixtures.box("free", fixture.uuid)
        for ((index, video) in listOf(fixture.cleanVideo + wrongType, fixture.cleanVideo + hidden, fixture.video + fixture.uuid).withIndex()) {
            val transaction = CountingTransaction(MemoryOutputTransaction(context, "vivo-legacy-uuid-$index"))
            assertIs<CoreResult.Failure>(core.split(SplitRequest(SourceSet.Pair(source(fixture.image, "image"), source(video, "video")), output = transaction, context = context)))
            assertEquals(0, transaction.commitCalls)
        }
    }

    @Test
    fun candidatesDoNotSelectAnArbitraryDuplicatePair(): Unit = runImmediate {
        val fixture = LegacyFixtures.pair()
        val candidates = SourceSet.Candidates(listOf(source(fixture.image, "image"), source(fixture.video, "video-one"), source(fixture.video, "video-two")))
        val transaction = CountingTransaction(MemoryOutputTransaction(context, "vivo-legacy-ambiguous-candidates"))
        assertIs<CoreResult.Failure>(core.split(SplitRequest(candidates, output = transaction, context = context)))
        assertEquals(0, transaction.commitCalls)
        val unique = SourceSet.Candidates(listOf(source(fixture.video, "video"), source(fixture.image, "image")))
        assertEquals(target, value(core.inspect(ReadRequest(unique, context))).detection.primaryProtocol)
    }

    @Test
    fun changedSecondSourceSnapshotDoesNotPublishHalfPair(): Unit = runImmediate {
        val fixture = LegacyFixtures.pair()
        val image = TestSource(fixture.image, maxChunk = 1)
        val video = TestSource(fixture.video, maxChunk = 1).also { it.currentIdentity = it.currentIdentity.copy(id = SourceId("video")) }
        val input = SourceSet.Pair(image, video)
        val inspected = value(core.inspect(ReadRequest(input, context)))
        video.currentIdentity = video.currentIdentity.copy(generation = GenerationToken("changed-video"))
        val transaction = CountingTransaction(MemoryOutputTransaction(context, "vivo-legacy-stale"))
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(core.extract(ExtractRequest(input, resources = emptyList(), snapshot = inspected.snapshot, output = transaction, context = context))).error.code)
        assertEquals(0, transaction.commitCalls); assertFalse(image.closed); assertFalse(video.closed)
    }

    @Test
    fun legacyCreateIsHiddenAndUnavailableWithoutDeviceEvidence(): Unit = runImmediate {
        val caps = core.getProtocolCapabilities(target)
        val create = caps.operations.single { it.operation == Operation.Create }
        assertEquals(Implementation.Unsupported, create.implementation)
        assertEquals(Exposure.Hidden, create.exposure)
        assertEquals(Lifecycle.Legacy, create.lifecycle)
        assertTrue(caps.operations.none { Verification.DeviceTested in it.verification })
        val transaction = CountingTransaction(MemoryOutputTransaction(context, "vivo-legacy-create-hidden"))
        assertIs<CoreResult.Failure>(core.create(CreateRequest(source(GoogleFixtures.jpeg(), "image"), source(GoogleFixtures.video().bytes, "video"), target, output = transaction, context = context)))
        assertEquals(0, transaction.commitCalls)
    }

    @Test
    fun convertFromPairUsesExplicitTargetPresentationPositionAndPreservesVideoSamples(): Unit = runImmediate {
        val fixture = LegacyFixtures.pair()
        val transaction = MemoryOutputTransaction(context, "vivo-legacy-convert-google")
        value(core.convert(ConvertRequest(pair(fixture), ProtocolSelector(ProtocolId("google.motionphoto.v2"), ProfileId("jpeg")), edits = EditSpec(keyPosition = CoverPosition.FrameIndex(1uL)), output = transaction, context = context)))
        val carrier = transaction.committedAssets().values.single().toByteArray()
        val converted = SourceSet.Single(source(carrier, "converted"))
        val inspected = value(core.inspect(ReadRequest(converted, context)))
        assertEquals(ProtocolId("google.motionphoto.v2"), inspected.detection.primaryProtocol?.protocol)
        assertEquals(Time(40000, 1_000_000u), inspected.keyPhoto.position)
        assertTrue(inspected.detection.matches.none { it.target.protocol == target.protocol })
        val raw = MemoryOutputTransaction(context, "vivo-legacy-converted-extract")
        value(core.extract(ExtractRequest(converted, emptyList(), inspected.snapshot, output = raw, context = context)))
        assertEquals(Bytes(fixture.cleanVideo), raw.committedAssets().values.single())
    }

    private class FailSecondAsset(val delegate: MemoryOutputTransaction) : OutputTransaction by delegate {
        var createCalls = 0
        var commitCalls = 0
        override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> {
            createCalls++
            return if (createCalls == 2) CoreResult.Failure(CoreError(IssueCode("IO_ERROR"), Stage.Publish, "Injected second asset creation failure")) else delegate.create(spec)
        }
        override suspend fun commit(): CoreResult<Receipt> { commitCalls++; return delegate.commit() }
    }

    private class CountingTransaction(val delegate: MemoryOutputTransaction) : OutputTransaction by delegate {
        var commitCalls = 0
        override suspend fun commit(): CoreResult<Receipt> { commitCalls++; return delegate.commit() }
    }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
