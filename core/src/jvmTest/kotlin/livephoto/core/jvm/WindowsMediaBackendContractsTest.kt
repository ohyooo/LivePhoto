package livephoto.core.jvm

import livephoto.core.*
import livephoto.core.google.GoogleFixtures
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

/** Portable preflight contracts: these tests do not claim to invoke Windows on another OS. */
class WindowsMediaBackendContractsTest {
    private val backend = WindowsMediaFoundationBackend(listOf("must-not-execute"))
    private val context = Context(Limits(4_000_000uL, 4_000_000uL))
    private val source = MemoryBinarySource(Bytes(GoogleFixtures.video().bytes), SourceId("system-contract-video"))
    @Test fun finiteProbeAndFrameAreAdvertisedAndOtherOperationsCannotStage(): Unit = runImmediate {
        assertEquals(listOf("windows-media-foundation"), backend.capabilities().backendIds)
        assertEquals(Implementation.Experimental, backend.capabilities().operations.single { it.operation == Operation.Probe }.implementation)
        assertEquals(Implementation.Experimental, backend.capabilities().operations.single { it.operation == Operation.ExtractFrame }.implementation)
        assertEquals(Implementation.Experimental, backend.capabilities().operations.single { it.operation == Operation.Remux }.implementation)
        assertEquals(Implementation.Experimental, backend.capabilities().operations.single { it.operation == Operation.Trim }.implementation)
        assertEquals(Implementation.Unsupported, backend.capabilities().operations.single { it.operation == Operation.Transcode }.implementation)
        val staging = object : StagingArea {
            override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = error("Unsupported cannot stage")
            override suspend fun openForRead(id: AssetId): CoreResult<BinarySource> = error("Unsupported cannot read staging")
        }
        val job = BackendJob(Operation.Remux, listOf(ResourceRef(SourceSet.Single(source))), remuxContainer = VideoContainer.Mov,
            context = context, destination = staging)
        assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(backend.remux(job)).error.code.value)
        assertEquals("INVALID_ARGUMENT", assertIs<CoreResult.Failure>(backend.trim(job)).error.code.value)
        val transcode = job.copy(operation = Operation.Transcode, remuxContainer = null, videoEncoding = VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4))
        assertEquals("TRANSCODE_NOT_AUTHORIZED", assertIs<CoreResult.Failure>(backend.transcode(transcode)).error.code.value)
    }
    @Test fun unresolvedReferencesAndNonVideoAreRejectedBeforeWorkerExecution(): Unit = runImmediate {
        val ref = ResourceRef(SourceSet.Single(source), ResourceId("unresolved"))
        assertEquals("INVALID_ARGUMENT", assertIs<CoreResult.Failure>(backend.probe(ProbeRequest(ref, true, context))).error.code.value)
        val image = MemoryBinarySource(Bytes(GoogleFixtures.jpeg()), SourceId("system-contract-image"))
        assertEquals("CAPABILITY_UNSUPPORTED", assertIs<CoreResult.Failure>(backend.probe(ProbeRequest(ResourceRef(SourceSet.Single(image)), true, context))).error.code.value)
    }
}
