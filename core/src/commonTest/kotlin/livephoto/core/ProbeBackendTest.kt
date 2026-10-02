package livephoto.core

import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class ProbeBackendTest {
    private val context = Context(Limits(4_000_000uL, 4_000_000uL))
    private fun input(bytes: ByteArray) = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("probe-input")))
    private fun <T> value(result: CoreResult<T>): T = when (result) { is CoreResult.Success -> result.value; is CoreResult.Failure -> fail(result.error.toString()) }
    private fun code(result: CoreResult<*>) = assertIs<CoreResult.Failure>(result).error.code.value

    @Test fun structuralProbeDoesNotCallDecoderAndDoesNotClaimCompleteCoverage(): Unit = runImmediate {
        val backend = Decoder { error("Structural probe must not decode") }
        val core = DefaultLivePhotoCore(backend)
        val movie = value(core.probe(ProbeRequest(ResourceRef(input(GoogleFixtures.video().bytes)), context = context)))
        assertEquals(VideoContainer.Mp4, movie.videoContainer)
        assertEquals(Coverage.Partial, movie.coverage)
        assertEquals(2uL, movie.tracks.single().frameCount)
        val jpeg = value(core.probe(ProbeRequest(ResourceRef(input(GoogleFixtures.jpeg())), context = context)))
        assertEquals(ImageFormat.Jpeg, jpeg.imageFormat)
        assertEquals(Coverage.Partial, jpeg.coverage)
        assertEquals(Implementation.Experimental, core.getMediaCapabilities().operations.single { it.operation == Operation.Probe }.implementation)
        assertEquals(Implementation.Unsupported, core.getMediaCapabilities().operations.single { it.operation == Operation.ExtractFrame }.implementation)
    }

    @Test fun decoderReceivesOnlyTheVerifiedEmbeddedVideoAndCannotCloseItsOwner(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val source = input(GoogleFixtures.v2Photo(video))
        val inspection = value(DefaultLivePhotoCore().inspect(ReadRequest(source, context)))
        val id = inspection.layout.resources.single { it.kind == ResourceKind.Video }.id
        var calls = 0
        val backend = Decoder { request ->
            calls++
            assertTrue(request.decodeCheck); assertSame(context, request.context)
            assertNull(request.media.resourceId); assertNull(request.media.snapshot)
            val isolated = assertIs<SourceSet.Single>(request.media.input).source
            assertEquals(video.size.toULong(), value(isolated.size()))
            assertEquals(Bytes(video), value(isolated.readAt(0uL, video.size.toUInt())))
            assertIs<CoreResult.Failure>(isolated.readAt(video.size.toULong(), 1u))
            isolated.close()
            CoreResult.Success(MediaFacts(videoContainer = VideoContainer.Mp4, coverage = Coverage.Partial))
        }
        val facts = value(DefaultLivePhotoCore(backend).probe(ProbeRequest(ResourceRef(source, id, inspection.snapshot), true, context)))
        assertEquals(1, calls); assertEquals(Coverage.Partial, facts.coverage)
        assertEquals(2uL, facts.tracks.single().frameCount)
        assertIs<CoreResult.Success<SourceIdentity>>(source.source.identity())
    }

    @Test fun imageProbeDoesNotExposeTheLiveVideoSuffix(): Unit = runImmediate {
        val bytes = GoogleFixtures.v1Photo()
        val source = input(bytes)
        val inspected = value(DefaultLivePhotoCore().inspect(ReadRequest(source, context)))
        val extent = inspected.layout.resources.single { it.kind == ResourceKind.PrimaryImage }.extents.single().range
        val core = DefaultLivePhotoCore(Decoder { request ->
            val isolated = (request.media.input as SourceSet.Single).source
            assertEquals(extent.length, value(isolated.size()))
            assertEquals(Bytes(bytes.copyOfRange(0, extent.length.toInt())), value(isolated.readAt(0uL, extent.length.toUInt())))
            CoreResult.Success(MediaFacts(imageFormat = ImageFormat.Jpeg, coverage = Coverage.Complete))
        })
        assertEquals(Coverage.Complete, value(core.probe(ProbeRequest(ResourceRef(source), true, context))).coverage)
    }

    @Test fun staleSnapshotUnknownResourceAndMalformedMediaNeverReachDecoder(): Unit = runImmediate {
        val core = DefaultLivePhotoCore(Decoder { error("Invalid resources must not reach decoder") })
        val source = input(GoogleFixtures.v1Photo())
        assertEquals("SOURCE_CHANGED", code(core.probe(ProbeRequest(ResourceRef(source, snapshot = Snapshot(emptyList(), GenerationToken("stale"))), true, context))))
        assertEquals("INVALID_ARGUMENT", code(core.probe(ProbeRequest(ResourceRef(source, ResourceId("not-a-resource")), true, context))))
        assertIs<CoreResult.Failure>(core.probe(ProbeRequest(ResourceRef(input(byteArrayOf(1, 2, 3))), true, context)))
    }

    @Test fun noBackendIsUnsupportedAndFailuresAreNotTurnedIntoStructuralSuccess(): Unit = runImmediate {
        val request = ProbeRequest(ResourceRef(input(GoogleFixtures.jpeg())), true, context)
        assertEquals("CAPABILITY_UNSUPPORTED", code(DefaultLivePhotoCore().probe(request)))
        val expected = CoreResult.Failure(CoreError(IssueCode("DECODE_FAILED"), Stage.Validate, "fixture decoder failure"))
        assertEquals(expected, DefaultLivePhotoCore(Decoder { expected }).probe(request))
    }

    @Test fun sourceChangeDuringBackendExecutionInvalidatesResult(): Unit = runImmediate {
        val original = input(GoogleFixtures.jpeg()).source
        var changed = false
        val wrapped = object : BinarySource by original {
            override suspend fun identity(): CoreResult<SourceIdentity> = CoreResult.Success(value(original.identity()).let {
                if (changed) it.copy(generation = GenerationToken("changed")) else it
            })
        }
        for (backendFails in listOf(false, true)) {
            changed = false
            val core = DefaultLivePhotoCore(Decoder {
                changed = true
                if (backendFails) CoreResult.Failure(CoreError(IssueCode("DECODE_FAILED"), Stage.Validate, "failure"))
                else CoreResult.Success(MediaFacts(coverage = Coverage.Complete))
            })
            assertEquals("SOURCE_CHANGED", code(core.probe(ProbeRequest(ResourceRef(SourceSet.Single(wrapped)), true, context))))
        }
    }

    @Test fun contradictoryDecoderFactsAreRejectedAndCancellationIsCheckedAfterReturn(): Unit = runImmediate {
        val source = input(GoogleFixtures.jpeg())
        assertEquals("POSTCONDITION_FAILED", code(DefaultLivePhotoCore(Decoder {
            CoreResult.Success(MediaFacts(videoContainer = VideoContainer.Mp4, coverage = Coverage.Complete))
        }).probe(ProbeRequest(ResourceRef(source), true, context))))
        var cancelled = false
        val cancellable = context.copy(cancellation = Cancellation { cancelled })
        val core = DefaultLivePhotoCore(Decoder { cancelled = true; CoreResult.Success(MediaFacts(coverage = Coverage.Complete)) })
        assertEquals("CANCELLED", code(core.probe(ProbeRequest(ResourceRef(source), true, cancellable))))
    }

    private class Decoder(private val action: suspend (ProbeRequest) -> CoreResult<MediaFacts>) : MediaBackend {
        override fun capabilities() = MediaCapabilities(listOf("test-decoder"), emptyList())
        override suspend fun probe(request: ProbeRequest) = action(request)
        override suspend fun trim(job: BackendJob): CoreResult<BackendResult> = error("Probe cannot trim")
        override suspend fun remux(job: BackendJob): CoreResult<BackendResult> = error("Probe cannot remux")
        override suspend fun transcode(job: BackendJob): CoreResult<BackendResult> = error("Probe cannot transcode")
        override suspend fun extractFrame(job: BackendJob): CoreResult<BackendResult> = error("Probe cannot extract")
    }
}
