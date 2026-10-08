package livephoto.core.huawei

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.heif.HeifFixtures
import livephoto.core.memory.*
import kotlin.test.*

/** Independently assembled bounded HEIC/movie/tail; no device-compatibility claim. */
class HuaweiHeicReadTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL, maxMetadataBytes = 1_000_000uL))
    private val core = DefaultLivePhotoCore()
    private val selector = ProtocolSelector(ProtocolIds.Huawei, ProfileId("basic60"))
    private fun input(bytes: ByteArray) = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("huawei-heic")))
    private fun fixture(image: ByteArray = HeifFixtures.plain(), movie: ByteArray = GoogleFixtures.video().bytes,
        prefix: String = "v6_f1") = HuaweiFixtures.photo(jpeg = image, video = movie, prefix = prefix)
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
    private fun typeOffset(bytes: ByteArray, type: String): Int = bytes.indices.first { offset ->
        offset <= bytes.size - type.length && type.indices.all { bytes[offset + it].toInt() and 255 == type[it].code }
    }

    @Test fun finiteEnvelopeKeepsMovieAndTrailerSeparateAndPartial(): Unit = runImmediate {
        val f = fixture(); val source = input(f.bytes)
        val result = value(core.inspect(ReadRequest(source, context)))
        assertEquals(selector, result.detection.primaryProtocol)
        assertEquals(Disposition.Candidate, result.detection.disposition)
        assertEquals(MatchStrength.Weak, result.detection.matches.single().strength)
        assertEquals(ImageFormat.Heic, result.media.first().imageFormat)
        assertEquals(Coverage.Partial, result.media.first().coverage)
        assertNull(result.keyPhoto.position)
        assertTrue(result.keyPhoto.rawFields.isNotEmpty())
        assertTrue(result.issues.any { it.code.value == "TIMESTAMP_SEMANTICS_UNKNOWN" })
        val movie = result.layout.resources.single { it.kind == ResourceKind.Video }
        assertEquals(ByteRange(f.jpeg.size.toULong(), f.video.size.toULong()), movie.extents.single().range)
        val tail = result.layout.resources.single { it.kind == ResourceKind.Trailer }
        assertEquals(ByteRange((f.bytes.size - 60).toULong(), 60uL), tail.extents.single().range)
        val raw = MemoryOutputTransaction(context, "heic-video")
        val extracted = value(core.extract(ExtractRequest(source, listOf(movie.id), result.snapshot, output = raw, context = context)))
        assertEquals(Bytes(f.video), raw.committedAssets().values.single())
        assertTrue(extracted.preservation.records.any { it.guarantee == Guarantee.ExactExtraction && it.outcome == GuaranteeOutcome.Verified })
        val rawTail = MemoryOutputTransaction(context, "heic-tail")
        value(core.extract(ExtractRequest(source, listOf(tail.id), result.snapshot, output = rawTail, context = context)))
        assertEquals(Bytes(f.tail), rawTail.committedAssets().values.single())
        val validation = value(core.validate(ValidationRequest(source, context = context)))
        assertEquals(Verdict.Warning, validation.verdict)
        assertFalse(validation.coverage == Coverage.Complete)
        assertEquals(Bytes(f.bytes), source.source.readAt(0uL, f.bytes.size.toUInt()).orThrow())
    }

    @Test fun supportsBoundedExtendedBoxesIdatAndMultipleExtents(): Unit = runImmediate {
        for (image in listOf(HeifFixtures.plain(metaLast = true), HeifFixtures.plain(extended = true),
            HeifFixtures.plain(idat = true), HeifFixtures.plain(multiple = true))) {
            val f = fixture(image); val source = input(f.bytes)
            val result = value(core.inspect(ReadRequest(source, context)))
            assertEquals(selector, result.detection.primaryProtocol)
            for (r in result.layout.resources.filter { it.id.value.startsWith("heif:item:") })
                assertTrue(r.extents.all { it.range.endExclusive <= image.size.toULong() })
            val output = MemoryOutputTransaction(context, "heic-profile-${image.size}")
            value(core.extract(ExtractRequest(source, emptyList(), output = output, context = context)))
            assertEquals(Bytes(f.video), output.committedAssets().values.single())
        }
    }

    @Test fun itemExtentCannotBorrowMovieOrTrailerBytes(): Unit = runImmediate {
        val image = HeifFixtures.plain(); val iloc = typeOffset(image, "iloc")
        assertTrue(iloc >= 0)
        // Literal independent version-0 iloc layout: first extent offset is 18 bytes after its type.
        for (offset in listOf(image.size, image.size + GoogleFixtures.video().bytes.size)) {
            val bad = image.copyOf(); GoogleFixtures.u32(offset.toUInt()).copyInto(bad, iloc + 18)
            val result = assertIs<CoreResult.Failure>(core.inspect(ReadRequest(input(fixture(bad).bytes), context)))
            assertEquals("OFFSET_OUT_OF_BOUNDS", result.error.code.value)
        }
    }

    @Test fun rootBoxCannotConsumeMovieAndOpenEndedImageBoxIsNotAuthority(): Unit = runImmediate {
        val image = HeifFixtures.plain(); val mdat = typeOffset(image, "mdat")
        assertTrue(mdat >= 4)
        val crosses = image.copyOf(); GoogleFixtures.u32((image.size + 1).toUInt()).copyInto(crosses, mdat - 4)
        assertEquals("OFFSET_OUT_OF_BOUNDS", assertIs<CoreResult.Failure>(core.inspect(ReadRequest(input(fixture(crosses).bytes), context))).error.code.value)
        val zero = image.copyOf(); ByteArray(4).copyInto(zero, mdat - 4)
        assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(core.inspect(ReadRequest(input(fixture(zero).bytes), context))).error.code.value)
    }

    @Test fun honorAndUnknownMediaExtensionNeverAcquirePureMovieAuthority(): Unit = runImmediate {
        val base = GoogleFixtures.video().bytes
        for (f in listOf(fixture(prefix = "v2_f1"), fixture(movie = base + GoogleFixtures.box("uuid", ByteArray(16))))) {
            val source = input(f.bytes)
            val result = value(core.inspect(ReadRequest(source, context)))
            assertTrue(result.layout.resources.none { it.kind == ResourceKind.Video })
            assertTrue(result.issues.any { it.code.value == "UNKNOWN_PROTOCOL_VARIANT" })
            val output = MemoryOutputTransaction(context, "heic-unconfirmed")
            assertEquals("MOTION_VIDEO_MISSING", assertIs<CoreResult.Failure>(core.extract(ExtractRequest(source, emptyList(), output = output, context = context))).error.code.value)
            assertTrue(output.committedAssets().isEmpty())
        }
    }

    @Test fun writableOwnershipIsNotInferredFromReadableEnvelope(): Unit = runImmediate {
        val f = fixture(); val source = input(f.bytes)
        val split = MemoryOutputTransaction(context, "heic-clean")
        assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(core.split(SplitRequest(source, output = split, context = context))).error.code.value)
        assertTrue(split.committedAssets().isEmpty())
        val repair = MemoryOutputTransaction(context, "heic-repair")
        assertIs<CoreResult.Failure>(core.repair(RepairRequest(source, output = repair, context = context)))
        assertTrue(repair.committedAssets().isEmpty())
        val converted = MemoryOutputTransaction(context, "heic-convert")
        assertIs<CoreResult.Failure>(core.convert(ConvertRequest(source, ProtocolSelector(ProtocolIds.GoogleV2), output = converted, context = context)))
        assertTrue(converted.committedAssets().isEmpty())
        assertEquals(Bytes(f.bytes), source.source.readAt(0uL, f.bytes.size.toUInt()).orThrow())
    }

    @Test fun missingTailDoesNotInventHuaweiAndBudgetOrCancellationRemainHardFailures(): Unit = runImmediate {
        val plain = value(core.inspect(ReadRequest(input(HeifFixtures.plain()), context)))
        assertTrue(plain.detection.matches.none { it.target.protocol == ProtocolIds.Huawei })
        val f = fixture()
        val cancelled = context.copy(cancellation = Cancellation { true })
        assertEquals("CANCELLED", assertIs<CoreResult.Failure>(core.inspect(ReadRequest(input(f.bytes), cancelled))).error.code.value)
        val low = context.copy(limits = context.limits.copy(maxMetadataBytes = 512uL))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", assertIs<CoreResult.Failure>(core.inspect(ReadRequest(input(f.bytes), low))).error.code.value)
    }

    @Test fun competingMotionEnvelopeAndInvalidExtendedHeaderFailClosed(): Unit = runImmediate {
        val f = fixture(HeifFixtures.plain() + GoogleFixtures.box("mpvd", byteArrayOf()))
        assertEquals("AMBIGUOUS_LAYOUT", assertIs<CoreResult.Failure>(core.inspect(ReadRequest(input(f.bytes), context))).error.code.value)
        val impossible = GoogleFixtures.u32(1u) + "ftyp".encodeToByteArray() + ByteArray(8)
        assertEquals("CORRUPTED_CONTAINER", assertIs<CoreResult.Failure>(core.inspect(ReadRequest(input(impossible), context))).error.code.value)
    }

    @Test fun sourceGenerationChangeAndStaleExtractionSnapshotPublishNothing(): Unit = runImmediate {
        val f = fixture()
        val changing = TestSource(f.bytes)
        changing.onRead = { changing.currentIdentity = changing.currentIdentity.copy(generation = GenerationToken("changed")) }
        assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(core.inspect(ReadRequest(SourceSet.Single(changing), context))).error.code.value)
        val source = input(f.bytes); val inspected = value(core.inspect(ReadRequest(source, context)))
        val stale = Snapshot(inspected.snapshot.identities, GenerationToken("stale"))
        val output = MemoryOutputTransaction(context, "heic-stale")
        assertEquals("SOURCE_CHANGED", assertIs<CoreResult.Failure>(core.extract(ExtractRequest(source, emptyList(), stale, output = output, context = context))).error.code.value)
        assertTrue(output.committedAssets().isEmpty())
    }
}
