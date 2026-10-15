package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class CameraDurationVarianceTest {
    private val context = Context(Limits(1_000_000u, 1_000_000u))
    private fun fixture(declared: UInt = 119972u, trackTicks: UInt = 13331u, hevc: Boolean = true,
        composition: ByteArray? = null, edit: ByteArray? = null, deltas: Pair<UInt, UInt> = 59991u to 59991u): ByteArray {
        val bytes = GoogleFixtures.video(hevc = hevc, co64 = true, sampleDurations = deltas,
            trackDuration = trackTicks, composition = composition, editList = edit).bytes
        fun field(type: String, offset: Int, value: UInt) {
            val tag = type.encodeToByteArray()
            val start = (4..bytes.size - 4).single { index -> tag.indices.all { bytes[index + it] == tag[it] } } + 4
            GoogleFixtures.u32(value).copyInto(bytes, start + offset)
        }
        field("mvhd", 12, 10000u); field("mvhd", 16, trackTicks)
        field("mdhd", 12, 90000u); field("mdhd", 16, declared)
        return bytes
    }
    private fun reader(bytes: ByteArray) = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("camera-duration-fixture")), context)
    private suspend fun probe(bytes: ByteArray): CoreResult<VideoStructure> = BmffVideoProbe(reader(bytes)).probe(ByteRange(0uL, bytes.size.toULong()))
    private fun <T> success(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result, result.toString()).value
    private suspend fun rejected(bytes: ByteArray) {
        assertEquals("CORRUPTED_CONTAINER", assertIs<CoreResult.Failure>(probe(bytes)).error.code.value)
    }

    @Test fun finiteVarianceKeepsBothDurationsAndEveryOriginalSample(): Unit = runImmediate {
        val bytes = fixture()
        val before = Bytes(bytes)
        val track = success(probe(bytes)).tracks.single()
        assertEquals(119972uL, track.headerDuration)
        assertEquals(119982uL, track.duration)
        assertEquals(Time(119972, 90000u), track.presentationDuration)
        assertEquals(listOf(0uL, 59991uL), track.samples.map { it.decodeTime })
        assertEquals(listOf(0L, 59991L), track.samples.map { it.presentationTime })
        assertEquals(listOf(59991u, 59991u), track.samples.map { it.duration })
        assertEquals(before, Bytes(bytes))
    }

    @Test fun endpointBoundIsNotAnArbitraryPerFrameTolerance(): Unit = runImmediate {
        for (declared in listOf(119972u, 119973u, 119981u, 119982u)) success(probe(fixture(declared)))
        for (declared in listOf(119971u, 119983u, 120000u, 0u)) rejected(fixture(declared))
    }

    @Test fun compositionAndEvenTrivialEditsDoNotAcquireThisReadAllowance(): Unit = runImmediate {
        val composition = GoogleFixtures.fullBox("ctts", GoogleFixtures.u32(1u) + GoogleFixtures.u32(2u) + GoogleFixtures.u32(0u))
        rejected(fixture(composition = composition))
        val edit = GoogleFixtures.fullBox("elst", GoogleFixtures.u32(1u) + GoogleFixtures.u32(13331u) + GoogleFixtures.u32(0u) + byteArrayOf(0, 1, 0, 0))
        rejected(fixture(edit = edit))
    }

    @Test fun decodedEndpointMustIndependentlyAgreeWithTheTrackHeader(): Unit = runImmediate {
        // 13330 agrees with mdhd within one movie tick but not with stts.
        rejected(fixture(trackTicks = 13330u))
        rejected(fixture(trackTicks = 13332u))
    }

    @Test fun allowanceCannotHideAWholeFinalSampleOrRelaxAudioAndCoarseClocks(): Unit = runImmediate {
        rejected(fixture(declared = 59987u, trackTicks = 6666u, deltas = 59991u to 6u))
        val coarse = GoogleFixtures.video().bytes
        val mdhd = coarse.indexOfType("mdhd") + 4
        GoogleFixtures.u32(79u).copyInto(coarse, mdhd + 16)
        rejected(coarse)
        rejected(fractionalAudioVideo(136191u))
    }

    @Test fun probeReportsStructuredWarningWithoutDecoderOrTimelineRepair(): Unit = runImmediate {
        val bytes = fixture()
        val source = MemoryBinarySource(Bytes(bytes), SourceId("duration-warning"))
        try {
            val facts = success(DefaultLivePhotoCore().probe(ProbeRequest(ResourceRef(SourceSet.Single(source)), context = context)))
            val warning = facts.issues.single { it.code.value == VIDEO_DURATION_VARIANCE }
            assertEquals(Severity.Warning, warning.severity)
            assertEquals(Layer.Structure, warning.layer)
            assertEquals(TrackId("1"), warning.location?.track)
            assertEquals("bmff:mdhd/stts-duration", warning.location?.selector)
            assertEquals(Time(119972, 90000u), facts.duration)
        } finally { source.close() }
    }

    @Test fun finiteTrimAndTranscodeStillRequireExactDurationAgreement(): Unit = runImmediate {
        val bytes = fixture(hevc = false)
        val video = success(probe(bytes))
        assertEquals("CAPABILITY_UNSUPPORTED", assertFailsWith<CoreFault> {
            transcodeProfile(video, VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4))
        }.error.code.value)
        val failure = attempt { planLosslessTrim(reader(bytes), video, TrimSpec(TimeRange(Time.Zero, Time(59991, 90000u)))) }
        assertEquals("LOSSLESS_TRIM_UNAVAILABLE", assertIs<CoreResult.Failure>(failure).error.code.value)
    }

    @Test fun declaredPresentationEndIsNotExtendedToTheDecodeDeltaSum(): Unit = runImmediate {
        val video = success(probe(fixture()))
        assertEquals(1uL, selectFrame(video, CoverPosition.FrameIndex(1uL)).index)
        val failure = attemptNow { selectFrame(video, CoverPosition.Timestamp(Time(119972, 90000u), Selection.Nearest, Time(1, 1u))) }
        assertEquals("INVALID_PRESENTATION_TIMESTAMP", assertIs<CoreResult.Failure>(failure).error.code.value)
    }

    private fun ByteArray.indexOfType(type: String): Int {
        val tag = type.encodeToByteArray()
        return (4..size - 4).single { index -> tag.indices.all { this[index + it] == tag[it] } }
    }
}
