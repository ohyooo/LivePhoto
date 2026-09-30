package livephoto.core.samsung

import livephoto.core.*
import livephoto.core.binary.TestSource
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.*
import kotlin.test.*

class SamsungCoreReadTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private val target = ProtocolSelector(ProtocolId("samsung.motionphoto"), ProfileId("jpeg-sef-mpv3"))
    private fun input(bytes: ByteArray) = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("samsung-input")))

    @Test
    fun canonicalN2R36UsesSefAuthorityAndExactVideoWithoutRecordHeader(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        for (withXmp in listOf(false, true)) {
            val fixture = SamsungFixtures.photo(video, xmp = withXmp, secondaryPadding = if (withXmp) "0" else null)
            assertEquals(36u, SamsungFixtures.read32(fixture.bytes, fixture.bytes.size - 8))
            val source = input(fixture.bytes)
            val inspected = value(core.inspect(ReadRequest(source, context)))
            assertEquals(Disposition.Live, inspected.detection.disposition)
            assertEquals(target, inspected.detection.primaryProtocol)
            assertEquals(MatchStrength.Strong, inspected.detection.matches.single { it.target.protocol == target.protocol }.strength)
            val movie = inspected.layout.resources.first { it.kind == ResourceKind.Video }
            assertEquals(ByteRange(fixture.videoStart.toULong(), video.size.toULong()), movie.extents.single().range)
            val vendor = value(core.validateProtocol(ValidationRequest(source, target = target, context = context)))
            if (withXmp) assertTrue(vendor.verdict != Verdict.Invalid) else assertEquals(Verdict.Valid, vendor.verdict)
            assertEquals(Coverage.Partial, value(core.validateMedia(ValidationRequest(source, target = target, context = context))).coverage)
            val transaction = MemoryOutputTransaction(context, "samsung-read-$withXmp")
            val result = value(core.extract(ExtractRequest(source, listOf(movie.id), inspected.snapshot, output = transaction, context = context)))
            assertEquals(Bytes(video), transaction.committedAssets().values.single())
            assertTrue(result.preservation.records.any { it.guarantee == Guarantee.ExactExtraction && it.outcome == GuaranteeOutcome.Verified })
            if (withXmp) {
                assertEquals(MatchStrength.CompatibleBase, inspected.detection.matches.single { it.target.protocol == ProtocolId("google.motionphoto.v2") }.strength)
                val strictGoogle = value(core.validateProtocol(ValidationRequest(source, target = ProtocolSelector(ProtocolId("google.motionphoto.v2"), ProfileId("jpeg")), context = context)))
                assertEquals(Verdict.Invalid, strictGoogle.verdict)
            }
        }
    }

    @Test
    fun legacyFooterR44IsReadableOnlyAsLegacyAndFailsCanonicalValidation(): Unit = runImmediate {
        val fixture = SamsungFixtures.photo(legacy = true)
        assertEquals(44u, SamsungFixtures.read32(fixture.bytes, fixture.bytes.size - 8))
        val source = input(fixture.bytes)
        val inspected = value(core.inspect(ReadRequest(source, context)))
        val match = inspected.detection.matches.single { it.target.protocol == ProtocolId("samsung.motionphoto") }
        assertEquals(MatchStrength.Legacy, match.strength)
        val report = value(core.validateProtocol(ValidationRequest(source, target = target, context = context)))
        assertEquals(Verdict.Invalid, report.verdict)
        val movie = inspected.layout.resources.first { it.kind == ResourceKind.Video }
        val transaction = MemoryOutputTransaction(context, "samsung-legacy-raw")
        value(core.extract(ExtractRequest(source, listOf(movie.id), inspected.snapshot, output = transaction, context = context)))
        assertEquals(Bytes(GoogleFixtures.video().bytes), transaction.committedAssets().values.single())
        val canonical = SamsungFixtures.photo()
        val unknownVersion = canonical.bytes.copyOf().also { SamsungFixtures.le32(108u).copyInto(it, canonical.tableStart + 4) }
        val missingVersion = GoogleFixtures.jpeg() + SamsungFixtures.trailer(listOf(SamsungFixtures.Record(0x0a30, "MotionPhoto_Data", GoogleFixtures.video().bytes)))
        for ((index, bytes) in listOf(unknownVersion, missingVersion).withIndex()) {
            val input = input(bytes)
            val facts = value(core.inspect(ReadRequest(input, context)))
            assertTrue(facts.detection.matches.none { it.target.protocol == target.protocol && it.strength == MatchStrength.Strong })
            val validation = value(core.validateProtocol(ValidationRequest(input, target = target, context = context)))
            assertTrue(validation.verdict != Verdict.Valid)
            assertTrue(validation.coverage != Coverage.Complete)
            assertTrue((validation.issues + validation.checks.flatMap { it.issues }).any { it.code == IssueCode("UNKNOWN_PROTOCOL_VARIANT") })
            val raw = MemoryOutputTransaction(context, "samsung-unknown-version-raw-$index")
            value(core.extract(ExtractRequest(input, listOf(facts.layout.resources.first { it.kind == ResourceKind.Video }.id), facts.snapshot, output = raw, context = context)))
            assertEquals(Bytes(GoogleFixtures.video().bytes), raw.committedAssets().values.single())
        }
    }

    @Test
    fun maliciousFooterCountBackOffsetsRecordHeadersAndOverlapNeverBecomeStrong(): Unit = runImmediate {
        val fixture = SamsungFixtures.photo(ordinaryRecord = true)
        fun changed(offset: Int, value: UInt) = fixture.bytes.copyOf().also { SamsungFixtures.le32(value).copyInto(it, offset) }
        val first = fixture.tableStart + 12
        val second = first + 12
        val invalid = listOf(
            changed(fixture.bytes.size - 8, 0u), changed(fixture.bytes.size - 8, UInt.MAX_VALUE),
            changed(fixture.tableStart + 8, 0u), changed(fixture.tableStart + 8, UInt.MAX_VALUE),
            changed(fixture.tableStart + 4, 108u),
            changed(first + 4, 0u), changed(first + 4, UInt.MAX_VALUE),
            changed(first + 8, 7u), changed(first + 8, UInt.MAX_VALUE),
            changed(fixture.jpegEnd + 4, UInt.MAX_VALUE),
            fixture.bytes.copyOf().also { it[fixture.jpegEnd + 2] = 0x31 },
            fixture.bytes.copyOf().also { it[fixture.jpegEnd + 8] = 'X'.code.toByte() },
            fixture.bytes.copyOf().also { it[first] = 1 },
            fixture.bytes.copyOf().also { fixture.bytes.copyOfRange(first, first + 12).copyInto(it, second) },
            changed(second + 4, SamsungFixtures.read32(fixture.bytes, first + 4) - 1u),
            fixture.bytes.copyOf().also { it[it.lastIndex] = 0 },
        )
        for ((index, bytes) in invalid.withIndex()) {
            when (val detected = core.detect(ReadRequest(input(bytes), context))) {
                is CoreResult.Failure -> assertTrue(detected.error.code.value.isNotBlank())
                is CoreResult.Success -> assertTrue(detected.value.matches.none { it.target.protocol == target.protocol && it.strength == MatchStrength.Strong }, "Malformed directory index=$index")
            }
            val transaction = MemoryOutputTransaction(context, "samsung-malformed-$index")
            assertIs<CoreResult.Failure>(core.split(SplitRequest(input(bytes), output = transaction, context = context)))
            assertTrue(transaction.committedAssets().isEmpty())
        }
    }

    @Test
    fun duplicateMotionOrVersionRecordsAndWrongVersionPayloadAreNotCanonical(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val motion = SamsungFixtures.Record(0x0a30, "MotionPhoto_Data", video)
        val version = SamsungFixtures.Record(0x0a31, "MotionPhoto_Version", "mpv3".encodeToByteArray())
        for ((index, records) in listOf(listOf(motion, motion, version), listOf(motion, version, version), listOf(motion, version.copy(payload = "mpv9".encodeToByteArray())), listOf(motion)).withIndex()) {
            val source = input(GoogleFixtures.jpeg() + SamsungFixtures.trailer(records))
            when (val detected = core.detect(ReadRequest(source, context))) {
                is CoreResult.Failure -> assertTrue(detected.error.code.value.isNotBlank())
                is CoreResult.Success -> assertTrue(detected.value.matches.none { it.target.protocol == target.protocol && it.strength == MatchStrength.Strong })
            }
            val transaction = MemoryOutputTransaction(context, "samsung-duplicate-$index")
            assertIs<CoreResult.Failure>(core.split(SplitRequest(source, output = transaction, context = context)))
            assertTrue(transaction.committedAssets().isEmpty())
        }
    }

    @Test
    fun SefStringsInsideAppOrArbitrarySuffixDoNotEstablishMotionAuthority(): Unit = runImmediate {
        for (bytes in listOf(
            GoogleFixtures.jpeg(GoogleFixtures.segment(0xee, "SEFH MotionPhoto_Data SEFT".encodeToByteArray())),
            GoogleFixtures.jpeg() + "random MotionPhoto_Data SEFH garbage SEFT".encodeToByteArray(),
        )) {
            when (val detected = core.detect(ReadRequest(input(bytes), context))) {
                is CoreResult.Failure -> assertTrue(detected.error.code.value.isNotBlank())
                is CoreResult.Success -> assertTrue(detected.value.matches.none { it.target.protocol == target.protocol && it.strength == MatchStrength.Strong })
            }
        }
    }

    @Test
    fun validSefDirectoryWithFtypStubHasNoVerifiedVideoOrCleanPublication(): Unit = runImmediate {
        val fake = GoogleFixtures.box("ftyp", "isom".encodeToByteArray() + GoogleFixtures.u32(0u) + "mp42".encodeToByteArray()) + GoogleFixtures.box("mdat", byteArrayOf(1))
        val source = input(SamsungFixtures.photo(fake).bytes)
        val detected = value(core.detect(ReadRequest(source, context)))
        assertTrue(detected.matches.none { it.target.protocol == target.protocol && it.strength == MatchStrength.Strong })
        assertEquals(Verdict.Invalid, value(core.validateMedia(ValidationRequest(source, target = target, context = context))).verdict)
        val transaction = MemoryOutputTransaction(context, "samsung-fake-video")
        assertIs<CoreResult.Failure>(core.split(SplitRequest(source, output = transaction, context = context)))
        assertTrue(transaction.committedAssets().isEmpty())
    }

    @Test
    fun snapshotChangeAfterSefInspectionCannotAuthorizeExtraction(): Unit = runImmediate {
        val source = TestSource(SamsungFixtures.photo().bytes)
        val input = SourceSet.Single(source)
        val inspected = value(core.inspect(ReadRequest(input, context)))
        source.currentIdentity = source.currentIdentity.copy(generation = GenerationToken("samsung-new-generation"))
        val transaction = MemoryOutputTransaction(context, "samsung-stale")
        val failure = assertIs<CoreResult.Failure>(core.extract(ExtractRequest(input, listOf(inspected.layout.resources.first { it.kind == ResourceKind.Video }.id), inspected.snapshot, output = transaction, context = context)))
        assertEquals(IssueCode("SOURCE_CHANGED"), failure.error.code)
        assertTrue(transaction.committedAssets().isEmpty())
        assertFalse(source.closed)
    }

    @Test
    fun platformAndDeviceCapabilitiesDoNotOverstateHeicWriterCoverage() {
        val jpeg = core.getProtocolCapabilities(target)
        assertEquals(Implementation.Experimental, jpeg.operations.single { it.operation == Operation.Create }.implementation)
        assertTrue(jpeg.operations.none { Verification.DeviceTested in it.verification })
        val heic = core.getProtocolCapabilities(target.copy(profile = ProfileId("heic-sef-mpv2")))
        assertTrue(heic.operations.single { it.operation == Operation.Create }.implementation !in setOf(Implementation.Supported, Implementation.Experimental))
        assertTrue(heic.operations.none { Verification.DeviceTested in it.verification })
    }

    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
