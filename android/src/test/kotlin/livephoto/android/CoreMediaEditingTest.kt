package livephoto.android

import kotlinx.coroutines.runBlocking
import livephoto.core.*
import livephoto.ui.*
import kotlin.test.*

class CoreMediaEditingTest {
    @Test fun mediaBudgetAndRangesRejectBeforeCoreCalls() = runBlocking {
        val start = Time(400, 1000u); val end = Time(1600, 1000u)
        val range = TimeRange(start, end)
        val map = listOf(TimelineSegment(range, TimeRange(Time.Zero, Time(1200, 1000u))))
        assertEquals(range, retainedCoreVideoWindow(start, end, map, true, false))
        assertNull(retainedCoreVideoWindow(start, end, map, false, false))
        assertNull(retainedCoreVideoWindow(start, end, map, true, true))
        assertNull(retainedCoreVideoWindow(start, end, map + map, true, false))
        assertNull(retainedCoreVideoWindow(start, end, emptyList(), true, false))
        assertNull(retainedCoreVideoWindow(start, end, listOf(map.single().copy(output = TimeRange(Time.Zero, Time(1, 1u)))), true, false))
        assertNull(retainedCoreVideoWindow(start, end, listOf(map.single().copy(output = TimeRange(Time(1, 1000u), Time(1201, 1000u)))), true, false))
        assertTrue(start in range); assertFalse(end in range)
        val core = object : LivePhotoCore by DefaultLivePhotoCore() {
            override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> = error("Must not probe invalid input")
        }
        assertEquals("RESOURCE_LIMIT_EXCEEDED", assertIs<CoreResult.Failure>(prepareCoreMedia(core, ByteArray(ANALYSIS_INPUT_LIMIT + 1), CoreMediaAction.Probe)).error.code.value)
        for ((start, end) in listOf(Time(-1, 1u) to Time.Zero, Time.Zero to Time(61, 1u), Time(1, 1u) to Time.Zero)) {
            assertEquals("INVALID_ARGUMENT", assertIs<CoreResult.Failure>(prepareCoreMedia(core, byteArrayOf(0), CoreMediaAction.Trim, start, end)).error.code.value)
        }
    }
    @Test fun mediaProbeIsReadOnlyExplicitDecodeAndDisclosesPartialCoverage() = runBlocking {
        val cancellation = Cancellation { false }
        var calls = 0
        val core = object : LivePhotoCore by DefaultLivePhotoCore() {
            override suspend fun probe(request: ProbeRequest): CoreResult<MediaFacts> {
                calls++; assertTrue(request.decodeCheck)
                assertSame(cancellation, request.context.cancellation)
                assertEquals(32uL * 1024u * 1024u, request.context.limits.maxSpoolBytes)
                return CoreResult.Success(MediaFacts(coverage = Coverage.Partial))
            }
        }
        val bytes = byteArrayOf(1, 2, 3)
        val result = assertIs<CoreResult.Success<PreparedCoreMedia<Bytes>>>(prepareCoreMedia(core, bytes, CoreMediaAction.Probe, cancellation = cancellation)).value
        assertEquals(1, calls); assertNull(result.bytes); assertNull(result.mime)
        assertTrue(result.summary.contains("Partial")); assertContentEquals(byteArrayOf(1, 2, 3), bytes)
    }
    @Test fun mediaActionsDispatchOnceWithoutFallbackAndOnlyTranscodeIsAuthorized() = runBlocking {
        val actions = mutableListOf<CoreMediaAction>()
        fun <T> refused(action: CoreMediaAction): CoreResult<T> {
            actions += action
            return CoreResult.Failure(CoreError(IssueCode("EXPECTED_REFUSAL"), Stage.Plan, "No output"))
        }
        val core = object : LivePhotoCore by DefaultLivePhotoCore() {
            override suspend fun remux(request: RemuxRequest): CoreResult<OperationResult> {
                assertEquals(TranscodePolicy.Forbid, request.policy.transcode); return refused(CoreMediaAction.Remux)
            }
            override suspend fun trim(request: TrimRequest): CoreResult<TrimResult> {
                assertEquals(TranscodePolicy.Forbid, request.policy.transcode); return refused(CoreMediaAction.Trim)
            }
            override suspend fun transcode(request: TranscodeRequest): CoreResult<OperationResult> {
                assertEquals(TranscodePolicy.Explicit, request.policy.transcode); return refused(CoreMediaAction.Transcode)
            }
            override suspend fun extractFrame(request: ExtractFrameRequest): CoreResult<FrameResult> {
                val position = assertIs<CoverPosition.Timestamp>(request.position)
                assertEquals(Selection.Nearest, position.selection)
                assertEquals(Time(100, 1000u), position.tolerance); return refused(CoreMediaAction.ExtractFrame)
            }
        }
        val expected = listOf(CoreMediaAction.Remux, CoreMediaAction.Trim, CoreMediaAction.Transcode, CoreMediaAction.ExtractFrame)
        for (action in expected) assertEquals("EXPECTED_REFUSAL", assertIs<CoreResult.Failure>(prepareCoreMedia(core, byteArrayOf(0), action, Time.Zero, Time(1, 1u))).error.code.value)
        assertEquals(expected, actions)
    }
}
