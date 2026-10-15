package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.cameraOffsetVideo
import livephoto.core.implementation.SourceSession
import livephoto.core.memory.MemoryBinarySource
import livephoto.core.memory.MemoryOutputTransaction
import kotlin.test.*

class OpaqueImageSuffixCreateTest {
    private val context = Context(Limits(1_000_000u, 1_000_000u))
    // Synthetic opaque bytes, not an assertion about any vendor's private schema.
    private val suffix = ByteArray(435) { ((it * 37 + 11) and 255).toByte() }
    private val core = DefaultLivePhotoCore()

    @Test fun googleV1KeepsSuffixGapAndExactVideo(): Unit = runImmediate { roundTrip(ProtocolIds.GoogleV1, "jpeg") }
    @Test fun googleV2DeclaresPaddingWithoutDroppingSuffix(): Unit = runImmediate { roundTrip(ProtocolIds.GoogleV2, "jpeg") }
    @Test fun oplusKeepsImageSuffixDistinctFromMotionTrailer(): Unit = runImmediate { roundTrip(ProtocolIds.Oplus, "jpeg-no-tail") }

    @Test fun strictDoesNotPretendUnknownSuffixSemanticsAreVerified(): Unit = runImmediate {
        val image = MemoryBinarySource(Bytes(GoogleFixtures.jpeg() + suffix), SourceId("strict-cover"))
        val video = MemoryBinarySource(Bytes(cameraOffsetVideo()), SourceId("strict-video"))
        val output = MemoryOutputTransaction(context, "strict-suffix")
        try {
            val failure = assertIs<CoreResult.Failure>(core.create(CreateRequest(image, video,
                ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg")),
                policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = output, context = context)))
            assertEquals("PRESERVATION_REQUIREMENT_FAILED", failure.error.code.value)
            assertEquals(Value.Text("verify-output"), failure.error.details["livephoto.step"])
            assertTrue(output.committedAssets().isEmpty())
        } finally { image.close(); video.close() }
    }

    private suspend fun roundTrip(protocol: ProtocolId, profile: String) {
        val imageBytes = Bytes(GoogleFixtures.jpeg() + suffix)
        val videoBytes = Bytes(cameraOffsetVideo())
        val image = MemoryBinarySource(imageBytes, SourceId("suffix-cover"))
        val video = MemoryBinarySource(videoBytes, SourceId("suffix-video"))
        var created: OperationResult? = null
        var extracted: OperationResult? = null
        try {
            val output = MemoryOutputTransaction(context, "suffix-create")
            val result = success(core.create(CreateRequest(image, video, ProtocolSelector(protocol, ProfileId(profile)), output = output, context = context)))
            created = result
            val source = assertNotNull(result.output.assets.single().readableSource)
            val session = success(SourceSession.open(SourceSet.Single(source), context, ParseBudget(context)))
            val binding = session.bindings.single { it.protocol == protocol }
            assertNull(binding.trailer)
            assertEquals(ProfileId(profile), binding.profile)
            val padding = assertNotNull(binding.padding)
            assertEquals(435uL, padding.length)
            assertEquals(session.jpeg!!.primary.endExclusive, padding.offset)
            assertEquals(padding.endExclusive, binding.video!!.offset)
            assertEquals(Bytes(suffix), success(source.readAt(padding.offset, padding.length.toUInt())))
            assertEquals(GuaranteeOutcome.Unknown, result.preservation.records.single { it.guarantee == Guarantee.MetadataPreserving }.outcome)
            assertTrue(result.execution.none { it.remuxed || it.transcoded })
            val extractionOutput = MemoryOutputTransaction(context, "suffix-extract")
            val extraction = success(core.extract(ExtractRequest(SourceSet.Single(source), emptyList(), output = extractionOutput, context = context)))
            extracted = extraction
            assertEquals(videoBytes, extractionOutput.committedAssets().values.single())
            val splitOutput = MemoryOutputTransaction(context, "suffix-clean-must-not-drop")
            val split = assertIs<CoreResult.Failure>(core.split(SplitRequest(SourceSet.Single(source), output = splitOutput, context = context)))
            assertEquals("UNSAFE_METADATA_REWRITE", split.error.code.value)
            assertEquals("jpeg:opaque-suffix", split.error.location?.selector)
            assertTrue(splitOutput.committedAssets().isEmpty())
            val convertedOutput = MemoryOutputTransaction(context, "suffix-convert-must-not-drop")
            val other = if (protocol == ProtocolIds.GoogleV1) ProtocolIds.GoogleV2 else ProtocolIds.GoogleV1
            val converted = assertIs<CoreResult.Failure>(core.convert(ConvertRequest(SourceSet.Single(source),
                ProtocolSelector(other, ProfileId("jpeg")), output = convertedOutput, context = context)))
            assertEquals("UNSAFE_METADATA_REWRITE", converted.error.code.value)
            assertTrue(convertedOutput.committedAssets().isEmpty())
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
