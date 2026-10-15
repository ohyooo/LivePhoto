package livephoto.core.bmff

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.implementation.selectFrame
import livephoto.core.implementation.selectKey
import livephoto.core.implementation.microseconds
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class FractionalEditTest {
    private val context = Context(Limits(1_000_000u, 1_000_000u))
    private suspend fun probe(bytes: ByteArray): CoreResult<VideoStructure> =
        BmffVideoProbe(BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("fractional-edit-fixture")), context)).probe(ByteRange(0uL, bytes.size.toULong()))
    private fun <T> success(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result, result.toString()).value

    @Test fun audioGapDoesNotBecomeAnExtraSampleOrLoseFractionalTicks(): Unit = runImmediate {
        val bytes = fractionalAudioVideo()
        val before = Bytes(bytes)
        val audio = success(probe(bytes)).tracks.single { it.handler == "soun" }
        assertEquals(48000u, audio.timescale)
        assertEquals(240000u, audio.presentationTimescale)
        assertEquals(147750uL, audio.headerDuration)
        assertEquals(136192uL, audio.duration)
        assertEquals(133, audio.samples.size)
        assertEquals(0uL, audio.samples.first().decodeTime)
        assertEquals(1024u, audio.samples.first().duration)
        assertEquals(57792L, audio.samples.first().presentationTime)
        assertEquals(62912L, audio.samples[1].presentationTime)
        assertEquals(Time(30781, 10000u), audio.presentationDuration)
        assertEquals(before, Bytes(bytes))
    }

    @Test fun exactAudioHeaderWithFractionalGapAlsoRemainsValid(): Unit = runImmediate {
        val audio = success(probe(fractionalAudioVideo(136192u))).tracks.single { it.handler == "soun" }
        assertEquals(audio.duration, audio.headerDuration)
        assertEquals(240000u, audio.presentationTimescale)
    }

    @Test fun fractionalClockDoesNotRelaxIndependentHeaderConsistencyChecks(): Unit = runImmediate {
        for (duration in listOf(136191u, 136193u, 147740u, 147760u)) {
            val failure = assertIs<CoreResult.Failure>(probe(fractionalAudioVideo(duration)))
            assertEquals("CORRUPTED_CONTAINER", failure.error.code.value)
        }
    }

    @Test fun videoSelectionUsesEditedClockNotTheDecodeClock(): Unit = runImmediate {
        val video = success(probe(fractionalVideo()))
        val track = video.tracks.single()
        assertEquals(600u, track.timescale)
        assertEquals(3000u, track.presentationTimescale)
        assertEquals(listOf(0uL, 60uL), track.samples.map { it.decodeTime })
        assertEquals(listOf(3L, 303L), track.samples.map { it.presentationTime })
        val frame = selectFrame(video, CoverPosition.FrameIndex(1uL))
        assertEquals(0, frame.time.compareTo(Time(101, 1000u)))
        assertEquals(1uL, selectFrame(video, CoverPosition.Timestamp(Time(101, 1000u), Selection.Exact)).index)
        assertEquals(0uL, selectFrame(video, CoverPosition.Timestamp(Time(100, 1000u), Selection.AtOrBefore, Time(100, 1000u))).index)
        assertEquals(1uL, selectFrame(video, CoverPosition.Timestamp(Time(100, 1000u), Selection.Nearest, Time(1, 1000u))).index)
    }

    @Test fun derivedDefaultSelectsARepresentableFrameWithoutRoundingItsPts(): Unit = runImmediate {
        val video = success(probe(nonMicrosecondVideo()))
        val ordinary = selectKey(video, null)
        assertEquals(6190L, ordinary.position?.value)
        val key = selectKey(video, null, 1_000_000u)
        assertEquals(KeySource.DerivedDefault, key.source)
        assertEquals(0L, microseconds(assertNotNull(key.position)))
    }

    @Test fun explicitUnrepresentableFrameIsNotChangedToAnotherFrame(): Unit = runImmediate {
        val video = success(probe(nonMicrosecondVideo()))
        val key = selectKey(video, CoverPosition.FrameIndex(1uL), 1_000_000u)
        assertEquals(6190L, key.position?.value)
        assertEquals("VALUE_NOT_REPRESENTABLE", assertFailsWith<CoreFault> { microseconds(assertNotNull(key.position)) }.error.code.value)
    }
}
