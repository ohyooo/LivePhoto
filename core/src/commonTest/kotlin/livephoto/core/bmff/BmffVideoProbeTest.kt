package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.BinaryReader
import livephoto.core.binary.TestSource
import livephoto.core.google.GoogleFixtures
import kotlin.test.*

class BmffVideoProbeTest {
    private fun context(metadata: ULong = 65536uL, cancellation: Cancellation? = null) =
        Context(Limits(65536uL, 65536uL, maxMetadataBytes = metadata), cancellation = cancellation)

    @Test
    fun independentMovieTablesProduceExpectedAbsoluteSampleRangesAndTimes(): Unit = runImmediate {
        val fixture = GoogleFixtures.video()
        val result = probe(fixture.bytes)
        assertEquals(VideoContainer.Mp4, result.container)
        assertFalse(result.decoderValidated)
        val track = result.tracks.single()
        assertEquals(1u, track.trackId)
        assertEquals("vide", track.handler)
        assertEquals(1000u, track.timescale)
        assertEquals(80uL, track.duration)
        assertEquals(Time(80, 1000u), track.presentationDuration)
        assertEquals(1u, track.width)
        assertEquals(1u, track.height)
        assertEquals(0x10000u, track.displayWidthFixed)
        assertEquals(0x10000u, track.displayHeightFixed)
        assertNull(track.edit)
        assertEquals(VideoCodec.Avc, track.codec)
        assertEquals("avc1", track.sampleEntry)
        assertEquals(Bytes(fixture.configuration), track.codecConfiguration)
        assertEquals(listOf(ByteRange(fixture.sampleOffset, 6uL), ByteRange(fixture.sampleOffset + 6uL, 7uL)), track.samples.map { it.range })
        assertEquals(listOf(0uL, 40uL), track.samples.map { it.decodeTime })
        assertEquals(listOf(0L, 40L), track.samples.map { it.presentationTime })
        assertEquals(listOf(40u, 40u), track.samples.map { it.duration })
        assertEquals(listOf(true, false), track.samples.map { it.isSync })
    }

    @Test
    fun co64AndCompleteHevcParameterArraysHaveIndependentGoldenCoverage(): Unit = runImmediate {
        val fixture = GoogleFixtures.video(hevc = true, co64 = true)
        val movie = probe(fixture.bytes)
        val track = movie.tracks.single()
        assertEquals(VideoCodec.Hevc, track.codec)
        assertEquals("hvc1", track.sampleEntry)
        assertEquals(Bytes(fixture.configuration), track.codecConfiguration)
        assertEquals(ByteRange(fixture.sampleOffset, 6uL), track.samples.first().range)
        assertFalse(movie.decoderValidated)
        val overflow = fixture.bytes.copyOf()
        put32(overflow, payloadOffset(overflow, "co64") + 8, UInt.MAX_VALUE)
        assertIs<CoreResult.Failure>(result(overflow))
        val incomplete = fixture.bytes.copyOf()
        incomplete[payloadOffset(incomplete, "hvcC") + 23] = 32
        failure("UNSUPPORTED_CONTAINER", result(incomplete))
        val malformed = fixture.bytes.copyOf()
        malformed[payloadOffset(malformed, "hvcC") + 28] = 0
        failure("CORRUPTED_CONTAINER", result(malformed))
    }

    @Test
    fun inBandConfigWithoutParameterSetsIsUnsupportedRatherThanClaimedCorrupt(): Unit = runImmediate {
        val avc = GoogleFixtures.video().bytes.copyOf()
        replaceType(avc, "avc1", "avc3")
        avc[payloadOffset(avc, "avcC") + 5] = 0xe0.toByte()
        failure("UNSUPPORTED_CONTAINER", result(avc))
        val hevc = GoogleFixtures.video(hevc = true).bytes.copyOf()
        replaceType(hevc, "hvc1", "hev1")
        // Keep the array valid but label the PPS array as a different nonduplicate NAL type.
        hevc[payloadOffset(hevc, "hvcC") + 37] = 0xa3.toByte()
        hevc[payloadOffset(hevc, "hvcC") + 42] = 0x46
        failure("UNSUPPORTED_CONTAINER", result(hevc))
    }

    @Test
    fun movieTrackDurationsAndRequiredMediaHeaderAreValidated(): Unit = runImmediate {
        for ((type, offset, invalid) in listOf(Triple("mvhd", 16, 0u), Triple("tkhd", 20, 0u), Triple("tkhd", 20, 81u), Triple("tkhd", 20, 10u))) {
            val bytes = GoogleFixtures.video().bytes.copyOf()
            put32(bytes, payloadOffset(bytes, type) + offset, invalid)
            failure("CORRUPTED_CONTAINER", result(bytes))
        }
        val missing = GoogleFixtures.video().bytes.copyOf()
        replaceType(missing, "vmhd", "free")
        failure("CORRUPTED_CONTAINER", result(missing))
    }

    @Test
    fun signedCompositionOffsetsAndEditMappingDoNotConfuseDecodeAndPresentationOrder(): Unit = runImmediate {
        val composition = GoogleFixtures.box("ctts", byteArrayOf(1, 0, 0, 0) +
            GoogleFixtures.u32(2u) + GoogleFixtures.u32(1u) + GoogleFixtures.u32(50u) +
            GoogleFixtures.u32(1u) + GoogleFixtures.u32((-30).toUInt()))
        val plain = probe(GoogleFixtures.video(composition = composition).bytes).tracks.single()
        assertEquals(listOf(0uL, 40uL), plain.samples.map { it.decodeTime })
        assertEquals(listOf(50L, 10L), plain.samples.map { it.presentationTime })
        val edit = GoogleFixtures.fullBox("elst", GoogleFixtures.u32(1u) + GoogleFixtures.u32(60u) +
            GoogleFixtures.u32(20u) + byteArrayOf(0, 1, 0, 0))
        val edited = probe(GoogleFixtures.video(editList = edit, trackDuration = 60u).bytes).tracks.single()
        assertEquals(Time(60, 1000u), edited.presentationDuration)
        assertEquals(listOf(-20L, 20L), edited.samples.map { it.presentationTime })
        assertEquals(VideoEdit(1000u, 0uL, 60uL, 20), edited.edit)
    }

    @Test
    fun editEndUsesCompositionTimelineAndStillRejectsMissingPresentedContent(): Unit = runImmediate {
        val composition = GoogleFixtures.fullBox("ctts", GoogleFixtures.u32(1u) + GoogleFixtures.u32(2u) + GoogleFixtures.u32(40u))
        fun edit(start: UInt) = GoogleFixtures.fullBox("elst", GoogleFixtures.u32(1u) + GoogleFixtures.u32(80u) + GoogleFixtures.u32(start) + byteArrayOf(0, 1, 0, 0))
        val track = probe(GoogleFixtures.video(editList = edit(40u), composition = composition).bytes).tracks.single()
        assertEquals(listOf(0L, 40L), track.samples.map { it.presentationTime })
        assertEquals(80uL, track.duration)
        assertEquals(Time(80, 1000u), track.presentationDuration)
        failure("CORRUPTED_CONTAINER", result(GoogleFixtures.video(editList = edit(42u), composition = composition).bytes))
        failure("CORRUPTED_CONTAINER", result(GoogleFixtures.video(editList = edit(120u), composition = composition).bytes))
        failure("CORRUPTED_CONTAINER", result(GoogleFixtures.video(editList = edit(40u)).bytes))
    }

    @Test
    fun leadingEmptyEditShiftsPresentationAndNonUnitRateIsExplicitlyUnsupported(): Unit = runImmediate {
        val entries = GoogleFixtures.u32(2u) + GoogleFixtures.u32(10u) + GoogleFixtures.u32(UInt.MAX_VALUE) + byteArrayOf(0, 1, 0, 0) +
            GoogleFixtures.u32(60u) + GoogleFixtures.u32(20u) + byteArrayOf(0, 1, 0, 0)
        val edited = probe(GoogleFixtures.video(editList = GoogleFixtures.fullBox("elst", entries), trackDuration = 70u).bytes).tracks.single()
        assertEquals(Time(70, 1000u), edited.presentationDuration)
        assertEquals(listOf(-10L, 30L), edited.samples.map { it.presentationTime })
        assertEquals(VideoEdit(1000u, 10uL, 60uL, 20), edited.edit)
        val nonUnit = entries.copyOf()
        nonUnit[nonUnit.lastIndex - 2] = 2
        failure("UNSUPPORTED_CONTAINER", result(GoogleFixtures.video(editList = GoogleFixtures.fullBox("elst", nonUnit), trackDuration = 70u).bytes))
    }

    @Test
    fun embeddedMovieChunkOffsetsAreRelativeToVideoNotOuterSource(): Unit = runImmediate {
        val fixture = GoogleFixtures.video()
        val prefix = ByteArray(37) { 0x55 }
        val outer = prefix + fixture.bytes + ByteArray(11)
        val source = TestSource(outer, maxChunk = 1)
        val movie = value(BmffVideoProbe(BinaryReader(source, context())).probe(ByteRange(37uL, fixture.bytes.size.toULong())))
        assertEquals(ByteRange(fixture.sampleOffset + 37uL, 6uL), movie.tracks.single().samples.first().range)
        assertFalse(source.closed)
    }

    @Test
    fun ftypAndMdatWithoutMovieCannotPassVideoValidation(): Unit = runImmediate {
        val fake = GoogleFixtures.box("ftyp", "isom".encodeToByteArray() + GoogleFixtures.u32(0u) + "mp42".encodeToByteArray()) +
            GoogleFixtures.box("mdat", byteArrayOf(1, 2, 3, 4))
        failure("CORRUPTED_CONTAINER", result(fake))
        val changed = GoogleFixtures.video().bytes.copyOf()
        replaceType(changed, "moov", "free")
        failure("CORRUPTED_CONTAINER", result(changed))
    }

    @Test
    fun malformedSampleTablesCannotBeIgnored(): Unit = runImmediate {
        for ((type, offset, invalid) in listOf(
            Triple("stts", 8, 1u), // claims one timestamp for two samples
            Triple("stts", 12, 0u),
            Triple("stsc", 8, 0u),
            Triple("stsc", 12, 3u),
            Triple("stsc", 16, 2u),
            Triple("stsz", 8, UInt.MAX_VALUE),
            Triple("stsz", 12, 0u),
            Triple("stss", 8, 3u),
            Triple("mdhd", 12, 0u),
            Triple("mdhd", 16, 81u),
            Triple("tkhd", 12, 0u),
        )) {
            val bytes = GoogleFixtures.video().bytes.copyOf()
            put32(bytes, payloadOffset(bytes, type) + offset, invalid)
            assertIs<CoreResult.Failure>(result(bytes), "$type/$offset must fail")
        }
    }

    @Test
    fun chunkPointingIntoHeaderPastMdatOrOverlappingItsEndFails(): Unit = runImmediate {
        val fixture = GoogleFixtures.video()
        for (offset in listOf(0u, fixture.sampleOffset.toUInt() - 1u, fixture.sampleOffset.toUInt() + 12u, UInt.MAX_VALUE)) {
            val changed = fixture.bytes.copyOf()
            put32(changed, payloadOffset(changed, "stco") + 8, offset)
            assertIs<CoreResult.Failure>(result(changed))
        }
    }

    @Test
    fun codecConfigurationAndLengthPrefixedNalMustHaveValidStructure(): Unit = runImmediate {
        val fixture = GoogleFixtures.video()
        for ((offset, replacement) in listOf(0 to 2, 4 to 0xfe, 5 to 0xe0, 8 to 0x68, 12 to 0)) {
            val bytes = fixture.bytes.copyOf()
            bytes[payloadOffset(bytes, "avcC") + offset] = replacement.toByte()
            assertIs<CoreResult.Failure>(result(bytes), "avcC/$offset must fail")
        }
        for ((offset, replacement) in listOf(3 to 0, 3 to 7, 4 to 0xe5, 4 to 0x67, 4 to 0x60)) {
            val bytes = fixture.bytes.copyOf()
            bytes[fixture.sampleOffset.toInt() + offset] = replacement.toByte()
            assertIs<CoreResult.Failure>(result(bytes), "NAL/$offset/$replacement must fail")
        }
    }

    @Test
    fun unsupportedFragmentAndForeignHandlerAreStructuredFailures(): Unit = runImmediate {
        val fixture = GoogleFixtures.video().bytes
        failure("UNSUPPORTED_CONTAINER", result(fixture + GoogleFixtures.box("moof", byteArrayOf())))
        val changed = fixture.copyOf()
        "meta".encodeToByteArray().copyInto(changed, payloadOffset(changed, "hdlr") + 8)
        failure("UNSUPPORTED_CONTAINER", result(changed))
        val noVideo = fixture.copyOf()
        "soun".encodeToByteArray().copyInto(noVideo, payloadOffset(noVideo, "hdlr") + 8)
        assertIs<CoreResult.Failure>(result(noVideo))
    }

    @Test
    fun budgetsCancellationAndSnapshotChangesApplyToMovieProbe(): Unit = runImmediate {
        val bytes = GoogleFixtures.video().bytes
        failure("RESOURCE_LIMIT_EXCEEDED", result(bytes, context(metadata = 32uL)))
        failure("CANCELLED", result(bytes, context(cancellation = Cancellation { true })))
        val source = TestSource(bytes)
        source.onRead = { source.currentIdentity = source.currentIdentity.copy(generation = GenerationToken("changed")) }
        failure("SOURCE_CHANGED", BmffVideoProbe(BinaryReader(source, context())).probe(ByteRange(0uL, bytes.size.toULong())))
    }

    @Test
    fun finiteMutationsNeverEscapeAsUncheckedParserExceptions(): Unit = runImmediate {
        for (bytes in boundedMutations(GoogleFixtures.video().bytes)) {
            when (val parsed = result(bytes)) {
                is CoreResult.Success -> assertTrue(parsed.value.tracks.isNotEmpty())
                is CoreResult.Failure -> assertTrue(parsed.error.code.value.isNotBlank())
            }
        }
    }

    private suspend fun probe(bytes: ByteArray): VideoStructure = value(result(bytes))
    private suspend fun result(bytes: ByteArray, context: Context = context()): CoreResult<VideoStructure> =
        BmffVideoProbe(BinaryReader(TestSource(bytes), context)).probe(ByteRange(0uL, bytes.size.toULong()))
    private fun payloadOffset(bytes: ByteArray, type: String): Int {
        val tag = type.encodeToByteArray()
        val matches = (4..bytes.size - 4).filter { offset ->
            val size = (offset - 4 until offset).fold(0uL) { value, index -> (value shl 8) or (bytes[index].toInt() and 255).toULong() }
            size in 8uL..(bytes.size - offset + 4).toULong() && tag.indices.all { bytes[offset + it] == tag[it] }
        }
        return matches.single() + 4
    }
    private fun replaceType(bytes: ByteArray, old: String, replacement: String) { replacement.encodeToByteArray().copyInto(bytes, payloadOffset(bytes, old) - 4) }
    private fun put32(bytes: ByteArray, offset: Int, value: UInt) { GoogleFixtures.u32(value).copyInto(bytes, offset) }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
    private fun failure(code: String, result: CoreResult<*>) { assertEquals(IssueCode(code), assertIs<CoreResult.Failure>(result).error.code) }
}
