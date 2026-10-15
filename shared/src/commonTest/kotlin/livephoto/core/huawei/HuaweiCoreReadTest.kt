package livephoto.core.huawei

import livephoto.core.*
import livephoto.core.binary.TestSource
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import livephoto.core.oplus.OplusFixtures
import kotlin.test.*

class HuaweiCoreReadTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolId("huawei.movingphoto"), ProfileId("basic60"))
    private fun input(bytes: ByteArray) = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("huawei-read")))

    @Test
    fun fixedTailBindsPureVideoAndKeepsUnknownTimeSemantics(): Unit = runImmediate {
        val fixture = HuaweiFixtures.photo()
        val source = input(fixture.bytes)
        val inspected = value(core.inspect(ReadRequest(source, context)))
        assertEquals(target, inspected.detection.primaryProtocol)
        assertEquals(Disposition.Live, inspected.detection.disposition)
        assertEquals(MatchStrength.Strong, inspected.detection.matches.single { it.target == target }.strength)
        val movie = inspected.layout.resources.single { it.kind == ResourceKind.Video }
        assertEquals(ByteRange(fixture.jpeg.size.toULong(), fixture.video.size.toULong()), movie.extents.single().range)
        val trailer = inspected.layout.resources.single { it.kind == ResourceKind.Trailer }
        assertEquals(ByteRange((fixture.bytes.size - 60).toULong(), 60uL), trailer.extents.single().range)
        assertNull(inspected.keyPhoto.position)
        assertTrue(inspected.keyPhoto.rawFields.isNotEmpty())
        assertTrue((inspected.issues + inspected.keyPhoto.issues).any { it.code == IssueCode("TIMESTAMP_SEMANTICS_UNKNOWN") })
        val raw = MemoryOutputTransaction(context, "huawei-fixed-raw")
        value(core.extract(ExtractRequest(source, listOf(movie.id), inspected.snapshot, output = raw, context = context)))
        assertEquals(Bytes(fixture.video), raw.committedAssets().values.single())
    }

    @Test
    fun publishedArithmeticExampleUsesMMinusTwentyInsteadOfEofOffset(): Unit = runImmediate {
        val initialVideo = GoogleFixtures.video().bytes
        val video = initialVideo + GoogleFixtures.box("free", ByteArray(3000 - initialVideo.size - 8))
        val initialImage = GoogleFixtures.jpeg()
        val image = GoogleFixtures.jpeg(GoogleFixtures.segment(0xfe, ByteArray(6940 - initialImage.size - 4)))
        val fixture = HuaweiFixtures.photo(video = video, jpeg = image)
        assertEquals(10000, fixture.bytes.size)
        assertEquals("LIVE_3020", fixture.tail.copyOfRange(40, 60).decodeToString().trim())
        val inspected = value(core.inspect(ReadRequest(input(fixture.bytes), context)))
        assertEquals(ByteRange(6940uL, 3000uL), inspected.layout.resources.single { it.kind == ResourceKind.Video }.extents.single().range)
        assertEquals(ByteRange(9940uL, 60uL), inspected.layout.resources.single { it.kind == ResourceKind.Trailer }.extents.single().range)
    }

    @Test
    fun FrameHistoryAndMillisecondsHistoryAreBothRawWithoutInventedUnits(): Unit = runImmediate {
        for ((index, history) in listOf("1:2", "40:80").withIndex()) {
            val source = input(HuaweiFixtures.photo(history = history).bytes)
            val key = value(core.getKeyPhotoPosition(ReadRequest(source, context)))
            assertNull(key.position, "history index=$index")
            assertEquals(KeySource.Unknown, key.source)
            assertTrue(key.rawFields.any { (it.rawValue as? Value.Text)?.value?.contains(history) == true })
            assertTrue(key.issues.any { it.code == IssueCode("TIMESTAMP_SEMANTICS_UNKNOWN") })
        }
    }

    @Test
    fun malformedLengthFieldsCannotAuthorizeMutation(): Unit = runImmediate {
        for ((index, live) in listOf("LIVE_0", "LIVE_20", "LIVE_19", "LIVE_+30", "LIVE_-30", "LIVE_30x", "LIVE_", "LIVE_999999999999999").withIndex()) {
            val source = input(HuaweiFixtures.photo(live = live).bytes)
            val code = if (index in setOf(0, 1, 2, 7)) "MOTION_VIDEO_LENGTH_MISMATCH" else "VENDOR_TRAILER_INVALID"
            assertEquals(IssueCode(code), assertIs<CoreResult.Failure>(core.detect(ReadRequest(source, context))).error.code, "live=$live")
            val transaction = MemoryOutputTransaction(context, "huawei-malformed-$index")
            assertIs<CoreResult.Failure>(core.split(SplitRequest(source, output = transaction, context = context)))
            assertTrue(transaction.committedAssets().isEmpty())
        }
    }

    @Test
    fun signatureInsideImageOrExifMakeIsNotProtocolAuthority(): Unit = runImmediate {
        val markerText = GoogleFixtures.segment(0xee, "v6_f1 1:2 LIVE_3020".encodeToByteArray())
        val ordinaryExif = OplusFixtures.exifSegment("HUAWEI HONOR LIVE_3020")
        for (bytes in listOf(GoogleFixtures.jpeg(markerText), GoogleFixtures.jpeg(ordinaryExif), HuaweiFixtures.photo().bytes.dropLast(1).toByteArray())) {
            val detection = value(core.detect(ReadRequest(input(bytes), context)))
            assertTrue(detection.matches.none { it.target.protocol == target.protocol && it.strength == MatchStrength.Strong })
        }
    }

    @Test
    fun ftypStubAndPrimaryOverlapDoNotBecomeConfirmedVideo(): Unit = runImmediate {
        val fake = GoogleFixtures.box("ftyp", "isom".encodeToByteArray() + GoogleFixtures.u32(0u) + "mp42".encodeToByteArray()) + GoogleFixtures.box("mdat", byteArrayOf(1, 2))
        val fixture = HuaweiFixtures.photo()
        val overlap = HuaweiFixtures.photo(live = "LIVE_${fixture.video.size + 20 + 1}")
        for ((index, bytes) in listOf(HuaweiFixtures.photo(video = fake).bytes, overlap.bytes).withIndex()) {
            val source = input(bytes)
            if (index == 0) assertTrue(value(core.detect(ReadRequest(source, context))).matches.none { it.target.protocol == target.protocol && it.strength == MatchStrength.Strong })
            else assertEquals(IssueCode("OFFSET_OUT_OF_BOUNDS"), assertIs<CoreResult.Failure>(core.detect(ReadRequest(source, context))).error.code)
            val transaction = MemoryOutputTransaction(context, "huawei-bad-media-$index")
            assertIs<CoreResult.Failure>(core.split(SplitRequest(source, output = transaction, context = context)))
            assertTrue(transaction.committedAssets().isEmpty())
        }
    }

    @Test
    fun shortReadsAndChangedSnapshotKeepBorrowedSourceOpen(): Unit = runImmediate {
        val fixture = HuaweiFixtures.photo()
        val source = TestSource(fixture.bytes, maxChunk = 1)
        val input = SourceSet.Single(source)
        val inspected = value(core.inspect(ReadRequest(input, context)))
        assertEquals(target, inspected.detection.primaryProtocol)
        source.currentIdentity = source.currentIdentity.copy(generation = GenerationToken("huawei-new"))
        val transaction = MemoryOutputTransaction(context, "huawei-changed")
        val error = assertIs<CoreResult.Failure>(core.extract(ExtractRequest(input, listOf(inspected.layout.resources.single { it.kind == ResourceKind.Video }.id), inspected.snapshot, output = transaction, context = context))).error
        assertEquals(IssueCode("SOURCE_CHANGED"), error.code)
        assertTrue(transaction.committedAssets().isEmpty()); assertFalse(source.closed)
    }

    @Test
    fun capabilityKeepsHonorWriterAndDeviceEvidenceSeparate() {
        val basic = core.getProtocolCapabilities(target)
        assertEquals(Implementation.Experimental, basic.operations.single { it.operation == Operation.Create }.implementation)
        assertTrue(basic.operations.none { Verification.DeviceTested in it.verification })
        val honor = core.getProtocolCapabilities(target.copy(profile = ProfileId("honor-extended")))
        assertEquals(Implementation.Unsupported, honor.operations.single { it.operation == Operation.Create }.implementation)
    }

    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
