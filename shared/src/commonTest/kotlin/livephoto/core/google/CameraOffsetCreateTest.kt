package livephoto.core.google

import livephoto.core.*
import livephoto.core.bmff.cameraOffsetVideo
import livephoto.core.bmff.fractionalAudioVideo
import livephoto.core.bmff.nonMicrosecondVideo
import livephoto.core.memory.MemoryBinarySource
import livephoto.core.memory.MemoryOutputTransaction
import kotlin.test.*

class CameraOffsetCreateTest {
    @Test fun oplusCopyPackagingPreservesStartOffsetMovie(): Unit = runImmediate { roundTrip(ProtocolIds.Oplus, "jpeg-no-tail") }
    @Test fun googleV2CopyPackagingPreservesStartOffsetMovie(): Unit = runImmediate { roundTrip(ProtocolIds.GoogleV2, "jpeg") }
    @Test fun googleV1CopyPackagingPreservesStartOffsetMovie(): Unit = runImmediate { roundTrip(ProtocolIds.GoogleV1, "jpeg") }
    @Test fun oplusCopyPackagingPreservesFractionalAudioEdit(): Unit = runImmediate { roundTrip(ProtocolIds.Oplus, "jpeg-no-tail", fractionalAudioVideo()) }
    @Test fun googleV2CopyPackagingPreservesFractionalAudioEdit(): Unit = runImmediate { roundTrip(ProtocolIds.GoogleV2, "jpeg", fractionalAudioVideo()) }
    @Test fun googleV1CopyPackagingPreservesFractionalAudioEdit(): Unit = runImmediate { roundTrip(ProtocolIds.GoogleV1, "jpeg", fractionalAudioVideo()) }
    @Test fun oplusCopyPackagingReportsVideoDurationVariance(): Unit = runImmediate { roundTrip(ProtocolIds.Oplus, "jpeg-no-tail", fractionalAudioVideo(videoVariance = true), true) }
    @Test fun googleV2CopyPackagingReportsVideoDurationVariance(): Unit = runImmediate { roundTrip(ProtocolIds.GoogleV2, "jpeg", fractionalAudioVideo(videoVariance = true), true) }
    @Test fun googleV1CopyPackagingReportsVideoDurationVariance(): Unit = runImmediate { roundTrip(ProtocolIds.GoogleV1, "jpeg", fractionalAudioVideo(videoVariance = true), true) }
    @Test fun defaultKeyWithFiniteProtocolPrecisionDoesNotRequireVideoEncoding(): Unit = runImmediate {
        for ((protocol, profile) in listOf(ProtocolIds.Oplus to "jpeg-no-tail", ProtocolIds.GoogleV2 to "jpeg", ProtocolIds.GoogleV1 to "jpeg")) {
            roundTrip(protocol, profile, nonMicrosecondVideo())
        }
    }

    private suspend fun roundTrip(protocol: ProtocolId, profile: String, movie: ByteArray = cameraOffsetVideo(), timingWarning: Boolean = false) {
        val context = Context(Limits(1_000_000u, 1_000_000u))
        val core = DefaultLivePhotoCore()
        val imageBytes = Bytes(GoogleFixtures.jpeg())
        val videoBytes = Bytes(movie)
        val image = MemoryBinarySource(imageBytes, SourceId("camera-offset-image"))
        val video = MemoryBinarySource(videoBytes, SourceId("camera-offset-video"))
        var created: OperationResult? = null
        var extracted: OperationResult? = null
        try {
            val output = MemoryOutputTransaction(context, "camera-offset-create")
            val result = success(core.create(CreateRequest(image, video, ProtocolSelector(protocol, ProfileId(profile)), output = output, context = context)))
            created = result
            assertTrue(result.execution.none { it.transcoded || it.remuxed })
            if (timingWarning) {
                assertEquals(Verdict.Warning, result.validation.verdict)
                assertTrue(result.validation.issues.any { it.code.value == "VIDEO_DURATION_HEADER_SAMPLE_VARIANCE" })
                assertEquals(Verdict.Valid, result.validation.checks.single { it.id.endsWith(":jpeg.frame") }.verdict)
                assertEquals(Verdict.Warning, result.validation.checks.single { it.id.endsWith(":bmff.samples") }.verdict)
            }
            val source = assertNotNull(result.output.assets.single().readableSource)
            val input = SourceSet.Single(source)
            assertEquals(protocol, success(core.detect(ReadRequest(input, context))).primaryProtocol?.protocol)
            val validation = success(core.validate(ValidationRequest(input, layers = listOf(Layer.Structure, Layer.Protocol), context = context)))
            assertNotEquals(Verdict.Invalid, validation.verdict)
            assertEquals(Coverage.Complete, validation.coverage)
            val extractionOutput = MemoryOutputTransaction(context, "camera-offset-extract")
            val extraction = success(core.extract(ExtractRequest(input, emptyList(), output = extractionOutput, context = context)))
            extracted = extraction
            assertEquals(videoBytes, extractionOutput.committedAssets().getValue(extraction.output.assets.single().id))
            assertEquals(imageBytes, success(image.readAt(0uL, imageBytes.size.toUInt())))
            assertEquals(videoBytes, success(video.readAt(0uL, videoBytes.size.toUInt())))
        } finally {
            extracted?.output?.assets?.forEach { it.readableSource?.close() }
            created?.output?.assets?.forEach { it.readableSource?.close() }
            image.close(); video.close()
        }
    }

    private fun <T> success(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result, result.toString()).value
}
