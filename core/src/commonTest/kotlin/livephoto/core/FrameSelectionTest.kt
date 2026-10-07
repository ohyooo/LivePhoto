package livephoto.core

import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

class FrameSelectionTest {
    private val context = Context(Limits(8_000_000uL, 8_000_000uL))
    private suspend fun video(): VideoStructure {
        val reader = BinaryReader(MemoryBinarySource(Bytes(GoogleFixtures.video().bytes), SourceId("selection")), context)
        return BmffVideoProbe(reader).probe(ByteRange(0uL, reader.identity().orThrow().size)).orThrow()
    }
    @Test fun vfrAndBFramesUsePresentationOrderNotDecodeOrder(): Unit = runImmediate {
        val original = video()
        val track = original.tracks.single()
        val changed = track.copy(samples = listOf(track.samples[0].copy(presentationTime = 60), track.samples[1].copy(presentationTime = 10)))
        val selected = selectFrame(original.copy(tracks = listOf(changed)), CoverPosition.FrameIndex(0uL))
        assertEquals(1, selected.sampleOrdinal); assertEquals(Time(10, 1000u), selected.time)
        assertEquals(0, selectFrame(original.copy(tracks = listOf(changed)), CoverPosition.FrameIndex(1uL)).sampleOrdinal)
    }
    @Test fun samePtsUsesDtsThenStableSampleOrdinal(): Unit = runImmediate {
        val original = video(); val track = original.tracks.single()
        val changed = track.copy(samples = listOf(track.samples[0].copy(presentationTime = 0, decodeTime = 40uL), track.samples[1].copy(presentationTime = 0, decodeTime = 0uL)))
        assertEquals(1, selectFrame(original.copy(tracks = listOf(changed)), CoverPosition.FrameIndex(0uL)).sampleOrdinal)
        assertEquals(0, selectFrame(original.copy(tracks = listOf(changed)), CoverPosition.FrameIndex(1uL)).sampleOrdinal)
    }
    @Test fun ninetyKhzSelectionNeverRoundsToMicroseconds(): Unit = runImmediate {
        val original = video(); val track = original.tracks.single()
        val changed = track.copy(timescale = 90000u, presentationDuration = Time(4, 90000u),
            samples = listOf(track.samples[0].copy(presentationTime = 0), track.samples[1].copy(presentationTime = 2)))
        val source = original.copy(tracks = listOf(changed))
        val nearest = selectFrame(source, CoverPosition.Timestamp(Time(1, 90000u), Selection.Nearest, Time(1, 90000u)))
        assertEquals(0uL, nearest.index)
        assertEquals(Time(2, 90000u), selectFrame(source, CoverPosition.Timestamp(Time(1, 45000u), Selection.Exact)).time)
        assertEquals("INVALID_PRESENTATION_TIMESTAMP", assertIs<CoreResult.Failure>(attemptNow { selectFrame(source, CoverPosition.Timestamp(Time(1, 90000u), Selection.Nearest)) }).error.code.value)
    }
    @Test fun differingFractionScalesAndLargeValuesDoNotOverflow(): Unit = runImmediate {
        val original = video(); val track = original.tracks.single()
        val scale = UInt.MAX_VALUE
        val changed = track.copy(timescale = scale, presentationDuration = Time(Long.MAX_VALUE, 1u),
            samples = listOf(track.samples[0].copy(presentationTime = Long.MAX_VALUE - 3), track.samples[1].copy(presentationTime = Long.MAX_VALUE - 1)))
        val source = original.copy(tracks = listOf(changed))
        assertEquals(0uL, selectFrame(source, CoverPosition.Timestamp(Time(Long.MAX_VALUE - 2, scale), Selection.Nearest, Time(1, scale))).index)
        val small = track.copy(timescale = 3u, presentationDuration = Time(1, 1u),
            samples = listOf(track.samples[0].copy(presentationTime = 0), track.samples[1].copy(presentationTime = 1)))
        assertEquals(1uL, selectFrame(original.copy(tracks = listOf(small)), CoverPosition.Timestamp(Time(1, 4u), Selection.Nearest, Time(1, 12u))).index)
    }
    @Test fun editHiddenSamplesAndDurationEndAreNotPresented(): Unit = runImmediate {
        val original = video(); val track = original.tracks.single()
        val changed = track.copy(samples = listOf(track.samples[0].copy(presentationTime = -1), track.samples[1].copy(presentationTime = 80)))
        assertEquals("FRAME_INDEX_UNAVAILABLE", assertIs<CoreResult.Failure>(attemptNow { selectFrame(original.copy(tracks = listOf(changed)), CoverPosition.FrameIndex(0uL)) }).error.code.value)
    }
    @Test fun multipleTracksNeedExplicitIdAndOutOfRangeNeverClamps(): Unit = runImmediate {
        val original = video(); val track = original.tracks.single()
        val multiple = original.copy(tracks = listOf(track, track.copy(trackId = 2u)))
        assertEquals("FRAME_INDEX_UNAVAILABLE", assertIs<CoreResult.Failure>(attemptNow { selectFrame(multiple, CoverPosition.FrameIndex(0uL)) }).error.code.value)
        assertEquals(2u, selectFrame(multiple, CoverPosition.FrameIndex(0uL, TrackId("2"))).track.trackId)
        assertEquals("FRAME_INDEX_OUT_OF_RANGE", assertIs<CoreResult.Failure>(attemptNow { selectFrame(original, CoverPosition.FrameIndex(ULong.MAX_VALUE)) }).error.code.value)
    }
    @Test fun emptyEditNeverMakesPrerollSamplesVisible(): Unit = runImmediate {
        val original = video(); val track = original.tracks.single()
        val changed = track.copy(presentationDuration = Time(120, 1000u), edit = VideoEdit(1000u, 40uL, 80uL, 0),
            samples = listOf(track.samples[0].copy(presentationTime = 20), track.samples[1].copy(presentationTime = 40)))
        assertEquals(1, selectFrame(original.copy(tracks = listOf(changed)), CoverPosition.FrameIndex(0uL)).sampleOrdinal)
    }
}
