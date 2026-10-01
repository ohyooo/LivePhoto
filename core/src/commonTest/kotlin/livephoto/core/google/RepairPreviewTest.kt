package livephoto.core.google

import livephoto.core.*
import livephoto.core.memory.*
import kotlin.test.*

class RepairPreviewTest {
    private val context = Context(Limits(4_000_000uL, 4_000_000uL, maxMetadataBytes = 4_000_000uL))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private fun input(bytes: ByteArray) = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("repair-source")))
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value

    @Test fun uniquePhysicalSuffixProducesProposalWithoutWriting(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        for (length in listOf("0", "1", (video.size + 1).toString(), "not-a-number")) {
            val output = MemoryOutputTransaction(context, "repair-preview-$length")
            val result = value(core.repair(RepairRequest(input(GoogleFixtures.v1Photo(video, length = length)), output = output, context = context)))
            assertEquals(Value.Text(video.size.toString()), result.proposedChanges.single().after)
            assertTrue(result.changesApplied.isEmpty())
            assertNull(result.operation)
            assertEquals(result.issuesBefore, result.issuesAfter)
            assertTrue(value(output.query()).assetIds.isEmpty())
            assertEquals(TransactionState.Open, value(output.query()).state)
            val plan = value(core.plan(RepairRequest(input(GoogleFixtures.v1Photo(video, length = length)), output = output, context = context)))
            assertEquals(Availability.Conditional, plan.capabilities.availability)
            assertEquals(result.proposedChanges, plan.predictedPreservation.changes)
            assertTrue(value(output.query()).assetIds.isEmpty())
        }
    }

    @Test fun alreadyCorrectOffsetIsANoOp(): Unit = runImmediate {
        val result = value(core.repair(RepairRequest(input(GoogleFixtures.v1Photo()), context = context)))
        assertTrue(result.proposedChanges.isEmpty())
        assertTrue(result.changesApplied.isEmpty())
        assertNull(result.operation)
    }

    @Test fun noMagicScanningOrSelectionBetweenConcatenatedVideos(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        for (suffix in listOf(byteArrayOf(0) + video, video + video)) {
            val result = core.repair(RepairRequest(input(GoogleFixtures.v1Photo(suffix, length = "1")), context = context))
            assertEquals(IssueCode("REPAIR_NOT_POSSIBLE"), assertIs<CoreResult.Failure>(result).error.code)
        }
    }

    @Test fun unrelatedInvalidKeyBlocksRatherThanBeingSilentlyRepaired(): Unit = runImmediate {
        val result = value(core.repair(RepairRequest(input(GoogleFixtures.v1Photo(length = "1", timestamp = "999999")), context = context)))
        assertTrue(result.proposedChanges.isEmpty())
        assertEquals(IssueCode("INVALID_PRESENTATION_TIMESTAMP"), result.blocked.single().code)
    }

    @Test fun explicitFilterIsRespectedAndApplyIsStillGated(): Unit = runImmediate {
        val broken = input(GoogleFixtures.v1Photo(length = "1"))
        val result = value(core.repair(RepairRequest(broken, allowedIssueCodes = listOf(IssueCode("OTHER_ISSUE")), context = context)))
        assertTrue(result.proposedChanges.isEmpty())
        assertEquals(IssueCode("MOTION_VIDEO_LENGTH_MISMATCH"), result.blocked.single().code)
        val output = MemoryOutputTransaction(context, "repair-apply-gated")
        assertEquals(IssueCode("CAPABILITY_PLANNED"), assertIs<CoreResult.Failure>(core.repair(RepairRequest(broken, dryRun = false, output = output, context = context))).error.code)
        assertTrue(value(output.query()).assetIds.isEmpty())
    }

    @Test fun qualifiedAuthorityCannotBeUsedAsRepairEvidence(): Unit = runImmediate {
        val bytes = GoogleFixtures.v1Photo(length = "1", extra = "<g:MicroVideoVersion xmlns:g='http://ns.google.com/photos/1.0/camera/' xmlns:p='urn:private' p:meaning='unknown'>1</g:MicroVideoVersion>")
        assertIs<CoreResult.Failure>(core.repair(RepairRequest(input(bytes), context = context)))
    }
}
