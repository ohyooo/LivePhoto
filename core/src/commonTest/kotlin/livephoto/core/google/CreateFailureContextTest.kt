package livephoto.core.google

import livephoto.core.*
import livephoto.core.implementation.FailureContext
import livephoto.core.memory.MemoryBinarySource
import livephoto.core.memory.MemoryOutputTransaction
import kotlin.test.*

class CreateFailureContextTest {
    @Test fun malformedImageIsIdentifiedBeforeVideoInspection(): Unit = runImmediate {
        val error = rejected(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), GoogleFixtures.video().bytes)
        assertContext(error, "inspect-image", "image")
    }

    @Test fun malformedVideoIsNotReportedAsAnImageFailure(): Unit = runImmediate {
        val error = rejected(GoogleFixtures.jpeg(), byteArrayOf(0, 1, 2))
        assertContext(error, "inspect-video", "video")
        assertEquals(SourceId("diagnostic-video"), error.location?.source)
    }

    @Test fun imageBindingConflictIsIdentifiedAsMetadataPreparation(): Unit = runImmediate {
        val error = rejected(GoogleFixtures.v2Photo(), GoogleFixtures.video().bytes)
        assertEquals("SOURCE_ALREADY_LIVE", error.code.value)
        assertContext(error, "prepare-image-metadata", "image")
        assertEquals(SourceId("diagnostic-image"), error.location?.source)
    }

    @Test fun contextualizationPreservesOriginalDiagnosticAndInnerProvenance() {
        val original = CoreError(IssueCode("IO_WRITE_FAILED"), Stage.WriteProtocol, "specific failure",
            Location(source = SourceId("staged-output"), range = ByteRange(7u, 9u), track = TrackId("3")),
            mapOf("nativeCode" to Value.Number("28"), "livephoto.step" to Value.Text("write-output"),
                "livephoto.inputRole" to Value.Text("output")), Recoverability.AfterRetry)
        val context = FailureContext("create")
        context.at("prepare-image-metadata", "image", SourceId("cover"))
        val result = context.annotate(original)
        assertEquals(original, result.copy(details = original.details))
        assertContext(result, "write-output", "output")
        assertEquals(Value.Number("28"), result.details["nativeCode"])
        assertEquals(original.location, result.location)
    }

    private suspend fun rejected(imageBytes: ByteArray, videoBytes: ByteArray): CoreError {
        val context = Context(Limits(1_000_000u, 1_000_000u))
        val image = MemoryBinarySource(Bytes(imageBytes), SourceId("diagnostic-image"))
        val video = MemoryBinarySource(Bytes(videoBytes), SourceId("diagnostic-video"))
        val output = MemoryOutputTransaction(context, "diagnostic-create")
        try {
            val result = DefaultLivePhotoCore().create(CreateRequest(image, video,
                ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg")), output = output, context = context))
            assertTrue(output.committedAssets().isEmpty())
            return assertIs<CoreResult.Failure>(result).error
        } finally { image.close(); video.close() }
    }

    private fun assertContext(error: CoreError, step: String, role: String) {
        assertEquals(Value.Text("create"), error.details["livephoto.operation"])
        assertEquals(Value.Text(step), error.details["livephoto.step"])
        assertEquals(Value.Text(role), error.details["livephoto.inputRole"])
    }
}
