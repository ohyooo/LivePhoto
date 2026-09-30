package livephoto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PolicyAndOutputContractsTest {
    @Test
    fun safeMutationDefaultsRequireExplicitPermissionForLossOrTranscoding() {
        val policy = MutationPolicy()
        assertEquals(TranscodePolicy.Forbid, policy.transcode)
        assertEquals(PreservationPolicy.BestEffortWithReport, policy.preservation)
        assertEquals(LossPolicy.RejectUnrequested, policy.loss)
        assertEquals(emptyList(), policy.allowedLosses)
        assertEquals(ConflictPolicy.Reject, policy.conflicts)
        assertEquals(ExistingOutput.Fail, policy.existingOutput)
        assertEquals(Atomicity.AssetSetRequired, policy.atomicity)
        assertEquals(DynamicRangePolicy.Preserve, MediaPreference().dynamicRange)
    }

    @Test
    fun trimDefaultsPreserveSamplesAndRejectHiddenContentAndOutOfRangeKeys() {
        val spec = TrimSpec(TimeRange(Time.Zero, Time(1, 1u)))
        assertEquals(TrimMode.LosslessPreferred, spec.mode)
        assertEquals(BoundaryPolicy.CoverRequestedRange, spec.boundary)
        assertEquals(PrerollPolicy.RejectHiddenRetainedContent, spec.preroll)
        assertEquals(Time.Zero, spec.exactTolerance)
        assertEquals(null, spec.maxBoundaryDeviation)
        assertEquals(KeyOutsidePolicy.Reject, spec.keyOutside)
    }

    @Test
    fun trimRejectsEmptyNegativeRangesAndNegativeDeviation() {
        assertFailsWith<IllegalArgumentException> { TrimSpec(TimeRange(Time.Zero, Time.Zero)) }
        assertFailsWith<IllegalArgumentException> { TrimSpec(TimeRange(Time(-1, 1u), Time(1, 1u))) }
        val range = TimeRange(Time.Zero, Time(1, 1u))
        assertFailsWith<IllegalArgumentException> { TrimSpec(range, exactTolerance = Time(-1, 1000u)) }
        assertFailsWith<IllegalArgumentException> { TrimSpec(range, maxBoundaryDeviation = Time(-1, 1000u)) }
    }

    @Test
    fun imageQualityAndCfrParametersCannotSilentlySelectAnotherPolicy() {
        ImageEncoding(ImageFormat.Jpeg, quality = 0u)
        ImageEncoding(ImageFormat.Jpeg, quality = 100u)
        assertFailsWith<IllegalArgumentException> { ImageEncoding(ImageFormat.Jpeg, quality = 101u) }
        assertFailsWith<IllegalArgumentException> { ImageEncoding(ImageFormat.Unknown) }
        assertFailsWith<IllegalArgumentException> {
            VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4, constantFrameRate = RationalRate(30u, 1u))
        }
        assertFailsWith<IllegalArgumentException> {
            VideoEncoding(VideoCodec.Avc, VideoContainer.Mp4, frameRatePolicy = FrameRatePolicy.ExplicitConstantRate)
        }
        assertFailsWith<IllegalArgumentException> { VideoEncoding(VideoCodec.Unknown, VideoContainer.Mp4) }
        assertFailsWith<IllegalArgumentException> { VideoEncoding(VideoCodec.Avc, VideoContainer.Unknown) }
    }

    @Test
    fun outputAssetsMustBeAccessibleAndSuccessfulOutputMustBeCommitted() {
        assertFailsWith<IllegalArgumentException> {
            OutputAsset(AssetId("inaccessible"), AssetRole.PrimaryImage, byteLength = 1uL, mime = "image/jpeg", digest = Digest("sha256-example"))
        }
        val image = outputAsset("image", AssetRole.PrimaryImage, "image/jpeg")
        assertFailsWith<IllegalArgumentException> {
            MediaOutput(listOf(image), receipt(listOf(image.id), TransactionState.Prepared))
        }
        assertFailsWith<IllegalArgumentException> {
            MediaOutput(listOf(image), receipt(listOf(AssetId("another"))))
        }
        assertFailsWith<IllegalArgumentException> {
            MediaOutput(listOf(image, image), receipt(listOf(image.id)))
        }
    }

    @Test
    fun appleStylePairIsRepresentedAsOneCommittedAssetSetWithRelationships() {
        val image = outputAsset("image", AssetRole.PrimaryImage, "image/jpeg").copy(relatedAssets = listOf(AssetId("video")))
        val video = outputAsset("video", AssetRole.MotionVideo, "video/quicktime").copy(relatedAssets = listOf(image.id))
        val output = MediaOutput(listOf(image, video), receipt(listOf(image.id, video.id)))
        assertEquals(listOf(AssetRole.PrimaryImage, AssetRole.MotionVideo), output.assets.map { it.role })
        assertEquals(output.assets.map { it.id }, output.receipt.assetIds)
        assertEquals(listOf(video.id), output.assets[0].relatedAssets)
    }

    @Test
    fun receiptAndCommittedOutputOwnTheirAssetLists() {
        val image = outputAsset("image", AssetRole.PrimaryImage, "image/jpeg")
        val suppliedIds = mutableListOf(image.id)
        val receipt = receipt(suppliedIds)
        suppliedIds.clear()
        assertEquals(listOf(image.id), receipt.assetIds)
        val suppliedAssets = mutableListOf(image)
        val output = MediaOutput(suppliedAssets, receipt)
        suppliedAssets.clear()
        assertEquals(listOf(image), output.assets)
        try {
            (receipt.assetIds as? MutableList<AssetId>)?.clear()
        } catch (_: UnsupportedOperationException) {
            // Immutable collection views are allowed.
        }
        try {
            (output.assets as? MutableList<OutputAsset>)?.clear()
        } catch (_: UnsupportedOperationException) {
            // Immutable collection views are allowed.
        }
        assertEquals(listOf(image.id), receipt.assetIds)
        assertEquals(listOf(image), output.assets)
    }

    private fun outputAsset(id: String, role: AssetRole, mime: String): OutputAsset = OutputAsset(
        id = AssetId(id),
        role = role,
        accessReference = "asset:$id",
        byteLength = 1uL,
        mime = mime,
        digest = Digest("sha256-$id"),
    )

    private fun receipt(ids: List<AssetId>, state: TransactionState = TransactionState.Committed): Receipt =
        Receipt("test-receipt", state, ids, Atomicity.AssetSetRequired, "not-tested")
}
