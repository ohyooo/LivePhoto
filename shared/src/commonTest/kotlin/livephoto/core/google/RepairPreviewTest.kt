package livephoto.core.google

import livephoto.core.*
import livephoto.core.memory.*
import livephoto.core.binary.TestSource
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

    @Test fun explicitFilterIsRespectedForPreviewAndApply(): Unit = runImmediate {
        val broken = input(GoogleFixtures.v1Photo(length = "1"))
        val result = value(core.repair(RepairRequest(broken, allowedIssueCodes = listOf(IssueCode("OTHER_ISSUE")), context = context)))
        assertTrue(result.proposedChanges.isEmpty())
        assertEquals(IssueCode("MOTION_VIDEO_LENGTH_MISMATCH"), result.blocked.single().code)
        val output = MemoryOutputTransaction(context, "repair-apply-gated")
        val applied = value(core.repair(RepairRequest(broken, allowedIssueCodes = listOf(IssueCode("OTHER_ISSUE")), dryRun = false, output = output, context = context)))
        assertTrue(applied.changesApplied.isEmpty())
        assertEquals(result.blocked, applied.blocked)
        assertTrue(value(output.query()).assetIds.isEmpty())
    }

    @Test fun emptyFilterRepairsAllProvenIssuesAndSecondRepairIsANoOp(): Unit = runImmediate {
        for (codes in listOf(emptyList(), listOf(IssueCode("MOTION_VIDEO_LENGTH_MISMATCH")))) {
            val output = MemoryOutputTransaction(context, "repair-all-${codes.size}")
            val request = RepairRequest(input(GoogleFixtures.v1Photo(length = "1")), allowedIssueCodes = codes, dryRun = false, output = output, context = context)
            assertEquals(Availability.Conditional, value(core.plan(request)).capabilities.availability)
            assertTrue(value(output.query()).assetIds.isEmpty())
            val repaired = value(core.repair(request))
            assertEquals(repaired.proposedChanges, repaired.changesApplied)
            assertEquals(1, repaired.changesApplied.size)
            assertTrue(repaired.issuesAfter.none { it.severity == Severity.Error })
            val asset = repaired.operation!!.output.assets.single()
            val secondOutput = MemoryOutputTransaction(context, "repair-again-${codes.size}")
            val second = value(core.repair(RepairRequest(SourceSet.Single(asset.readableSource!!), dryRun = false, output = secondOutput, context = context)))
            assertTrue(second.changesApplied.isEmpty())
            assertNull(second.operation)
            assertTrue(value(secondOutput.query()).assetIds.isEmpty())
            val extracted = MemoryOutputTransaction(context, "repair-video-${codes.size}")
            value(core.extract(ExtractRequest(SourceSet.Single(asset.readableSource), emptyList(), output = extracted, context = context)))
            assertEquals(Bytes(GoogleFixtures.video().bytes), extracted.committedAssets().values.single())
        }
    }

    @Test fun changedInputAbortsBeforeCommit(): Unit = runImmediate {
        val source = TestSource(GoogleFixtures.v1Photo(length = "0"))
        val transaction = MemoryOutputTransaction(context, "repair-source-changed")
        val output = object : OutputTransaction by transaction {
            override suspend fun prepare(): CoreResult<Unit> {
                source.currentIdentity = source.currentIdentity.copy(generation = GenerationToken("changed"))
                return transaction.prepare()
            }
        }
        assertEquals(IssueCode("SOURCE_CHANGED"), assertIs<CoreResult.Failure>(core.repair(RepairRequest(SourceSet.Single(source), dryRun = false, output = output, context = context))).error.code)
        assertEquals(TransactionState.Aborted, value(transaction.query()).state)
        assertTrue(transaction.committedAssets().isEmpty())
        assertFalse(source.closed)
    }

    @Test fun newStagingProtocolErrorRollsBackTheWholeRepair(): Unit = runImmediate {
        val transaction = MemoryOutputTransaction(context, "repair-corrupt-staging")
        val output = object : OutputTransaction by transaction {
            override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> {
                val original = value(transaction.openStaged(id))
                val bytes = value(original.readAt(0uL, value(original.size()).toUInt())).toByteArray()
                val field = "MicroVideoVersion".encodeToByteArray()
                val start = bytes.indices.first { i -> i + field.size <= bytes.size && field.indices.all { bytes[i + it] == field[it] } } + field.size
                val digit = (start until start + 8).first { bytes[it] == '1'.code.toByte() }
                bytes[digit] = '2'.code.toByte()
                return CoreResult.Success(MemoryBinarySource(Bytes(bytes), SourceId("corrupt-staging")))
            }
        }
        assertIs<CoreResult.Failure>(core.repair(RepairRequest(input(GoogleFixtures.v1Photo(length = "1")), dryRun = false, output = output, context = context)))
        assertEquals(TransactionState.Aborted, value(transaction.query()).state)
        assertTrue(transaction.committedAssets().isEmpty())
    }

    @Test fun allRepairableOffsetEncodingsWorkWithoutAnAllowlist(): Unit = runImmediate {
        val video = GoogleFixtures.video().bytes
        for (length in listOf("0", "not-a-number", (video.size + 1).toString())) {
            val output = MemoryOutputTransaction(context, "repair-offset-$length")
            val result = value(core.repair(RepairRequest(input(GoogleFixtures.v1Photo(video, length = length)), dryRun = false, output = output, context = context)))
            assertEquals(1, result.changesApplied.size)
            assertEquals(TransactionState.Committed, value(output.query()).state)
        }
        val output = MemoryOutputTransaction(context, "repair-offset-code")
        val result = value(core.repair(RepairRequest(input(GoogleFixtures.v1Photo(video, length = (video.size + 1).toString())),
            allowedIssueCodes = listOf(IssueCode("OFFSET_OUT_OF_BOUNDS")), dryRun = false, output = output, context = context)))
        assertEquals(1, result.changesApplied.size)
    }

    @Test fun unknownPreservationAndUnavailableAtomicityCannotBeOverriddenByRepairAll(): Unit = runImmediate {
        val original = GoogleFixtures.v1Photo(length = "1")
        val exif = "Exif\u0000\u0000".encodeToByteArray() + byteArrayOf(77, 77, 0, 42) + GoogleFixtures.u32(8u) + ByteArray(6)
        val withExif = original.copyOfRange(0, 2) + GoogleFixtures.segment(0xe1, exif) + original.copyOfRange(2, original.size)
        val strict = MemoryOutputTransaction(context, "repair-strict")
        assertEquals(IssueCode("PRESERVATION_REQUIREMENT_FAILED"), assertIs<CoreResult.Failure>(core.repair(RepairRequest(input(withExif), dryRun = false,
            policy = MutationPolicy(preservation = PreservationPolicy.Strict), output = strict, context = context))).error.code)
        assertTrue(strict.committedAssets().isEmpty())
        assertEquals(TransactionState.Aborted, value(strict.query()).state)
        val transaction = MemoryOutputTransaction(context, "repair-non-atomic")
        val output = object : OutputTransaction by transaction {
            override fun capabilities(): OutputCapabilities = transaction.capabilities().copy(assetSetAtomic = false)
        }
        val request = RepairRequest(input(original), dryRun = false, output = output, context = context)
        assertEquals(IssueCode("ATOMIC_PUBLICATION_UNAVAILABLE"), assertIs<CoreResult.Failure>(core.plan(request)).error.code)
        assertEquals(IssueCode("ATOMIC_PUBLICATION_UNAVAILABLE"), assertIs<CoreResult.Failure>(core.repair(request)).error.code)
        assertTrue(value(output.query()).assetIds.isEmpty())
    }

    @Test fun missingOffsetIsAddedWithoutChangingOtherMetadata(): Unit = runImmediate {
        val xml = "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description rdf:about='' xmlns:g='http://ns.google.com/photos/1.0/camera/' xmlns:p='urn:ordinary' g:MicroVideo='1' g:MicroVideoVersion='1' g:MicroVideoPresentationTimestampUs='0' p:rating='5'/></rdf:RDF>"
        val original = GoogleFixtures.jpeg(GoogleFixtures.xmpSegment(xml)) + GoogleFixtures.video().bytes
        val output = MemoryOutputTransaction(context, "repair-missing-offset")
        val repaired = value(core.repair(RepairRequest(input(original), dryRun = false, output = output, context = context)))
        assertNull(repaired.changesApplied.single().before)
        val inspected = value(core.inspect(ReadRequest(SourceSet.Single(repaired.operation!!.output.assets.single().readableSource!!), context)))
        assertEquals(Value.Text("5"), inspected.metadata.single { it.selector == "{urn:ordinary}rating" }.value)
        assertEquals(Time(0, 1_000_000u), inspected.keyPhoto.position)
    }

    @Test fun repairAllDoesNotGuessUnrelatedKeyOrAmbiguousVideo(): Unit = runImmediate {
        val output = MemoryOutputTransaction(context, "repair-all-blocked")
        val blocked = value(core.repair(RepairRequest(input(GoogleFixtures.v1Photo(length = "1", timestamp = "999999")), dryRun = false, output = output, context = context)))
        assertTrue(blocked.changesApplied.isEmpty())
        assertEquals(IssueCode("INVALID_PRESENTATION_TIMESTAMP"), blocked.blocked.single().code)
        val video = GoogleFixtures.video().bytes
        assertEquals(IssueCode("REPAIR_NOT_POSSIBLE"), assertIs<CoreResult.Failure>(core.repair(RepairRequest(input(GoogleFixtures.v1Photo(video + video, length = "1")), dryRun = false, output = output, context = context))).error.code)
        assertTrue(value(output.query()).assetIds.isEmpty())
    }

    @Test fun qualifiedAuthorityCannotBeUsedAsRepairEvidence(): Unit = runImmediate {
        val bytes = GoogleFixtures.v1Photo(length = "1", extra = "<g:MicroVideoVersion xmlns:g='http://ns.google.com/photos/1.0/camera/' xmlns:p='urn:private' p:meaning='unknown'>1</g:MicroVideoVersion>")
        assertIs<CoreResult.Failure>(core.repair(RepairRequest(input(bytes), context = context)))
    }
}
