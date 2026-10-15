package livephoto.core.oplus

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*
import livephoto.core.implementation.*
import livephoto.core.memory.*
import kotlin.test.*

/** Synthetic coverage of the upstream edit model: current cover != original capture. */
class OplusKeyMetadataTest {
    private val context = Context(Limits(4_000_000uL, 4_000_000uL, maxMetadataBytes = 4_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private fun input(bytes: ByteArray) = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("oplus-key")))
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value

    @Test fun changingCoverPreservesOriginalPhotoTimestampIncludingAbsentAndUnknown(): Unit = runImmediate {
        for (primary in listOf("00000", "40000", "-1", null)) for (padding in listOf(null, "0")) {
            val source = input(OplusFixtures.photo(vendorTimestamp = primary, secondaryPadding = padding))
            val before = value(SourceSession.open(source, context, ParseBudget(context)))
            val output = MemoryOutputTransaction(context, "oplus-key-$primary-$padding")
            val request = SetKeyRequest(source, CoverPosition.FrameIndex(1uL), output = output, context = context)
            val plan = value(core.plan(request))
            assertEquals(1, plan.predictedPreservation.changes.size)
            assertTrue(value(output.query()).assetIds.isEmpty())
            val result = value(core.setKeyPhotoPosition(request))
            val after = value(SourceSession.open(SourceSet.Single(result.output.assets.single().readableSource!!), context, ParseBudget(context)))
            assertEquals(ProtocolIds.Oplus, after.inspection.detection.primaryProtocol?.protocol)
            assertEquals(Time(40_000, 1_000_000u), result.keyPhoto?.position)
            assertTrue(after.bindings.all { it.key.position == Time(40_000, 1_000_000u) })
            assertEquals(primary, value(after.xmp!!.scalar(OPLUS_URI, "MotionPhotoPrimaryPresentationTimestampUs")))
            for (field in OPLUS_FIELDS) assertEquals(value(before.xmp!!.scalar(OPLUS_URI, field)), value(after.xmp.scalar(OPLUS_URI, field)))
            assertEquals(codingDigest(before), codingDigest(after))
            assertEquals(ordinaryDigest(before), ordinaryDigest(after))
            assertEquals(value(sha256Range(before.reader, before.jpeg!!.trailing)), value(sha256Range(after.reader, after.jpeg!!.trailing)))
            assertTrue(result.execution.none { it.transcoded })
        }
    }

    @Test fun missingOrUnknownCoverDoesNotBecomeTheOriginalCaptureTime(): Unit = runImmediate {
        for (cover in listOf(null, "-1")) {
            val source = input(OplusFixtures.photo(googleTimestamp = cover, vendorTimestamp = "40000"))
            val before = value(core.inspect(ReadRequest(source, context)))
            assertNull(before.keyPhoto.position)
            assertTrue(before.keyPhoto.rawFields.any { it.rawValue == Value.Text("40000") })
            val result = value(core.setKeyPhotoPosition(SetKeyRequest(source, CoverPosition.FrameIndex(0uL), output = MemoryOutputTransaction(context, "oplus-zero-$cover"), context = context)))
            assertEquals(Time(0, 1_000_000u), result.keyPhoto?.position)
            assertTrue(result.keyPhoto!!.rawFields.any { it.rawValue == Value.Text("40000") })
        }
    }

    @Test fun malformedOriginalTimeAndUnknownDependenciesCannotBeOverwrittenBySetKey(): Unit = runImmediate {
        val cases = listOf(
            OplusFixtures.photo(vendorTimestamp = "bad"),
            OplusFixtures.photo(vendorTimestamp = "-2"),
            OplusFixtures.photo(extra = "<o:MotionPhotoPrimaryPresentationTimestampUs>40000</o:MotionPhotoPrimaryPresentationTimestampUs>"),
            OplusFixtures.photo(extra = "<o:PrivateTiming>1</o:PrivateTiming>"),
            OplusFixtures.photo(tail = byteArrayOf(1, 2, 3)),
            OplusFixtures.photo(version = "3"),
            OplusFixtures.photo(comment = null),
            OplusFixtures.photo(secondaryPadding = "1"),
        )
        for ((index, bytes) in cases.withIndex()) {
            val output = MemoryOutputTransaction(context, "oplus-rejected-$index")
            val request = SetKeyRequest(input(bytes), CoverPosition.FrameIndex(0uL), output = output, context = context)
            assertIs<CoreResult.Failure>(core.plan(request))
            assertIs<CoreResult.Failure>(core.setKeyPhotoPosition(request))
            assertTrue(value(output.query()).assetIds.isEmpty())
        }
    }

    @Test fun alteredOriginalTimestampInStagingIsRejectedEvenWhenStructurallyValid(): Unit = runImmediate {
        val transaction = MemoryOutputTransaction(context, "oplus-tampered")
        val output = object : OutputTransaction by transaction {
            override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> {
                val original = value(transaction.openStaged(id))
                val bytes = value(original.readAt(0uL, value(original.size()).toUInt())).toByteArray()
                val field = "MotionPhotoPrimaryPresentationTimestampUs".encodeToByteArray()
                val start = bytes.indices.first { i -> i + field.size <= bytes.size && field.indices.all { bytes[i + it] == field[it] } } + field.size
                val digit = (start until start + 8).first { bytes[it] == '0'.code.toByte() }
                bytes[digit] = '1'.code.toByte()
                return CoreResult.Success(MemoryBinarySource(Bytes(bytes), SourceId("tampered-primary-time")))
            }
        }
        val result = core.setKeyPhotoPosition(SetKeyRequest(input(OplusFixtures.photo()), CoverPosition.FrameIndex(1uL), output = output, context = context))
        assertEquals(IssueCode("POSTCONDITION_FAILED"), assertIs<CoreResult.Failure>(result).error.code)
        assertEquals(TransactionState.Aborted, value(transaction.query()).state)
        assertTrue(transaction.committedAssets().isEmpty())
    }

    @Test fun strictDoesNotClaimUnprovenExifPreservationAndTailProfileRemainsUnsupported(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "oplus-strict-key")
        val result = core.setKeyPhotoPosition(SetKeyRequest(input(OplusFixtures.photo()), CoverPosition.FrameIndex(1uL), policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = output, context = context))
        assertEquals(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), assertIs<CoreResult.Failure>(result).error.code)
        assertEquals(TransactionState.Aborted, value(output.query()).state)
        assertTrue(output.committedAssets().isEmpty())
        for ((profile, expected) in listOf("jpeg-no-tail" to Implementation.Experimental, "oneplus-tail-bearing" to Implementation.Unsupported)) {
            val capability = core.getProtocolCapabilities(ProtocolSelector(ProtocolIds.Oplus, ProfileId(profile))).operations.single { it.operation == Operation.SetKey }
            assertEquals(expected, capability.implementation)
        }
    }
}
