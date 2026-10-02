package livephoto.core.vivo

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

/** Synthetic protocol tests, not a claim of vivo Gallery/device compatibility. */
class VivoKeyMetadataTest {
    private val context = Context(Limits(4_000_000uL, 4_000_000uL, maxMetadataBytes = 4_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private fun input(bytes: ByteArray) = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("vivo-key")))
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value

    @Test fun setPreservesVendorIdentityDirectoryImageAndCompleteVideo(): Unit = runImmediate {
        for (timestamp in listOf("0", "-1", "bad", null)) {
            val source = input(VivoFixtures.photo(timestamp = timestamp, extra = "<p:rating xmlns:p='urn:ordinary'>5</p:rating>").bytes)
            val before = value(SourceSession.open(source, context, ParseBudget(context)))
            val output = MemoryOutputTransaction(context, "vivo-key-$timestamp")
            val request = SetKeyRequest(source, CoverPosition.FrameIndex(1uL), output = output, context = context)
            val plan = value(core.plan(request))
            assertEquals(1, plan.predictedPreservation.changes.size)
            assertTrue(value(output.query()).assetIds.isEmpty())
            val result = value(core.setKeyPhotoPosition(request))
            val after = value(SourceSession.open(SourceSet.Single(result.output.assets.single().readableSource!!), context, ParseBudget(context)))
            assertEquals(ProtocolIds.VivoModern, after.inspection.detection.primaryProtocol?.protocol)
            assertTrue(after.bindings.all { it.key.position == Time(40_000, 1_000_000u) })
            assertEquals(codingDigest(before), codingDigest(after))
            assertEquals(ordinaryDigest(before), ordinaryDigest(after))
            assertEquals(value(sha256Range(before.reader, before.jpeg!!.trailing)), value(sha256Range(after.reader, after.jpeg!!.trailing)))
            for (field in VIVO_FIELDS) assertEquals(value(before.xmp!!.scalar(VIVO_URI, field)), value(after.xmp!!.scalar(VIVO_URI, field)))
            // JPEG length changes, while the suffix resource lengths and semantics do not.
            assertEquals(before.bindings.map { it.items.map { item -> item.semantic } }, after.bindings.map { it.items.map { item -> item.semantic } })
            assertEquals(before.bindings.map { it.items.drop(1).map { item -> item.range.length } }, after.bindings.map { it.items.drop(1).map { item -> item.range.length } })
            assertTrue(result.execution.none { it.transcoded })
            assertEquals(1, result.preservation.changes.size)
        }
    }

    @Test fun zeroAndVfrUseActualPresentationTiming(): Unit = runImmediate {
        val video = GoogleFixtures.video(sampleDurations = 10u to 70u).bytes
        for ((index, micros) in listOf(0uL to 0L, 1uL to 10_000L)) {
            val result = value(core.setKeyPhotoPosition(SetKeyRequest(input(VivoFixtures.photo(video = video).bytes), CoverPosition.FrameIndex(index), output = MemoryOutputTransaction(context, "vivo-vfr-$index"), context = context)))
            assertEquals(Time(micros, 1_000_000u), result.keyPhoto?.position)
        }
    }

    @Test fun dualGoogleAndVivoBindingsShareTheRequestedKey(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        val v1 = "<g:MicroVideo xmlns:g='$CAMERA_URI'>1</g:MicroVideo><g:MicroVideoVersion xmlns:g='$CAMERA_URI'>1</g:MicroVideoVersion>" +
            "<g:MicroVideoOffset xmlns:g='$CAMERA_URI'>${video.size}</g:MicroVideoOffset><g:MicroVideoPresentationTimestampUs xmlns:g='$CAMERA_URI'>0</g:MicroVideoPresentationTimestampUs>"
        val result = value(core.setKeyPhotoPosition(SetKeyRequest(input(VivoFixtures.photo(video = video, extra = v1).bytes), CoverPosition.FrameIndex(1uL), output = MemoryOutputTransaction(context, "vivo-dual"), context = context)))
        val session = value(SourceSession.open(SourceSet.Single(result.output.assets.single().readableSource!!), context, ParseBudget(context)))
        assertEquals(3, session.bindings.size)
        assertTrue(session.bindings.all { it.key.position == Time(40_000, 1_000_000u) })
        assertEquals(2, result.preservation.changes.size)
    }

    @Test fun incompleteProfilesAndAuxiliaryRelocationNeverStage(): Unit = runImmediate {
        val unsupported = listOf(
            VivoFixtures.photo(version = "2").bytes,
            VivoFixtures.photo(extra = "<v:VMotionPhotoFlags>0</v:VMotionPhotoFlags>").bytes,
            VivoFixtures.photo(extra = "<v:PrivateTiming>1</v:PrivateTiming>").bytes,
            VivoFixtures.photo(extra = "<v:VMotionPhotoSource xmlns:p='urn:private' p:meaning='unknown'>1</v:VMotionPhotoSource>").bytes,
            VivoFixtures.photo(primaryAttrs = "item:Length='0'").bytes,
            VivoFixtures.photo(motionAttrs = "item:Padding='1'").bytes,
            VivoFixtures.photo(motionAttrs = "").bytes,
            VivoFixtures.photo(gainMap = VivoFixtures.gainMap()).bytes,
            VivoFixtures.photo(videoLength = "1").bytes,
        )
        for ((index, bytes) in unsupported.withIndex()) {
            val output = MemoryOutputTransaction(context, "vivo-rejected-$index")
            val request = SetKeyRequest(input(bytes), CoverPosition.FrameIndex(0uL), output = output, context = context)
            assertIs<CoreResult.Failure>(core.plan(request))
            assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(request))
            assertTrue(value(output.query()).assetIds.isEmpty())
        }
    }

    @Test fun unrequestedButValidVendorFieldChangeAbortsPublication(): Unit = runImmediate {
        val transaction = MemoryOutputTransaction(context, "vivo-corrupt-staging")
        val output = object : OutputTransaction by transaction {
            override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> {
                val original = value(transaction.openStaged(id))
                val bytes = value(original.readAt(0uL, value(original.size()).toUInt())).toByteArray()
                val field = "VMotionPhotoSource".encodeToByteArray()
                val start = bytes.indices.first { i -> i + field.size <= bytes.size && field.indices.all { bytes[i + it] == field[it] } } + field.size
                val digit = (start until start + 8).first { bytes[it] == '1'.code.toByte() }
                bytes[digit] = '2'.code.toByte()
                return CoreResult.Success(MemoryBinarySource(Bytes(bytes), SourceId("tampered-vivo")))
            }
        }
        val result = core.setKeyPhotoPosition(SetKeyRequest(input(VivoFixtures.photo().bytes), CoverPosition.FrameIndex(1uL), output = output, context = context))
        assertEquals(IssueCode("POSTCONDITION_FAILED"), assertIs<CoreResult.Failure>(result).error.code)
        assertEquals(TransactionState.Aborted, value(transaction.query()).state)
        assertTrue(transaction.committedAssets().isEmpty())
    }

    @Test fun capabilityIsExperimentalAndGainmapBoundaryIsExplicit() {
        val capability = core.getProtocolCapabilities(ProtocolSelector(ProtocolIds.VivoModern)).operations.single { it.operation == Operation.SetKey }
        assertEquals(Implementation.Experimental, capability.implementation)
        assertTrue(capability.conditions.any { it.value == Value.Text("vivo-version-one-single-xmp-complete-video-suffix-no-gainmap-no-unknown-vendor-fields") })
    }
}
