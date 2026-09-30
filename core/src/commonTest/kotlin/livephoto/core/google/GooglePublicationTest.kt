package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.TestSource
import livephoto.core.memory.*
import kotlin.test.*

/** Faults wrap the real staging adapter; they never implement publication semantics themselves. */
class GooglePublicationTest {
    private fun context(cancellation: Cancellation? = null, output: ULong = 2_000_000uL) =
        Context(Limits(2_000_000uL, output, maxMetadataBytes = 1_000_000uL), cancellation = cancellation)
    private val target = ProtocolSelector(ProtocolId("google.motionphoto.v2"))
    private val core: LivePhotoCore = DefaultLivePhotoCore()
    private fun source(bytes: ByteArray, id: String) = MemoryBinarySource(Bytes(bytes), SourceId(id))
    private suspend fun create(transaction: OutputTransaction, context: Context = context(), video: BinarySource = source(GoogleFixtures.video().bytes, "video")): CoreResult<OperationResult> =
        core.create(CreateRequest(source(GoogleFixtures.jpeg(), "cover"), video, target, output = transaction, context = context))

    @Test
    fun oneByteShortWritesAndReadsStillPublishExactVideoAfterActualReadback(): Unit = runImmediate {
        val context = context()
        val transaction = FaultTransaction(MemoryOutputTransaction(context, "short"), shortWrite = 1, shortRead = 1u)
        val result = value(create(transaction, context))
        assertEquals(TransactionState.Committed, result.output.receipt.state)
        assertTrue(transaction.writeCalls > 1)
        assertTrue(transaction.readCalls > 1)
        assertTrue(transaction.events.indexOf("staged-read") < transaction.events.indexOf("commit"))
        assertEquals(GoogleFixtures.video().bytes.toList(), transaction.delegate.committedAssets().values.single().toByteArray().takeLast(GoogleFixtures.video().bytes.size))
    }

    @Test
    fun zeroProgressOrWriteFailureAbortsStagingWithoutAnyPublication(): Unit = runImmediate {
        for (mode in listOf(Fault.WriteZero, Fault.WriteFailure)) {
            val context = context()
            val transaction = FaultTransaction(MemoryOutputTransaction(context, "write-$mode"), fault = mode)
            assertIs<CoreResult.Failure>(create(transaction, context))
            assertTrue(transaction.delegate.committedAssets().isEmpty())
            assertEquals(TransactionState.Aborted, value(transaction.delegate.query()).state)
            assertEquals(0, transaction.commitCalls)
        }
    }

    @Test
    fun prepareReadbackFailureCorruptionOrWrongReceiptCannotCountAsVerifiedSuccess(): Unit = runImmediate {
        for (mode in listOf(Fault.PrepareFailure, Fault.StagedReadFailure, Fault.StagedCorruption)) {
            val context = context()
            val transaction = FaultTransaction(MemoryOutputTransaction(context, "verify-$mode"), fault = mode)
            assertIs<CoreResult.Failure>(create(transaction, context))
            assertTrue(transaction.delegate.committedAssets().isEmpty())
            assertEquals(TransactionState.Aborted, value(transaction.delegate.query()).state)
            assertEquals(0, transaction.commitCalls)
        }
    }

    @Test
    fun transferredResultReadHandleMustAlsoRejectInputIdentityAlias(): Unit = runImmediate {
        val context = context()
        val transaction = FaultTransaction(MemoryOutputTransaction(context, "result-handle-alias"), fault = Fault.SecondStagedAliasesInputId)
        failure("OUTPUT_ALIASES_INPUT", create(transaction, context))
        assertEquals(0, transaction.commitCalls)
        assertTrue(transaction.delegate.committedAssets().isEmpty())
        assertEquals(TransactionState.Aborted, value(transaction.delegate.query()).state)
    }

    @Test
    fun failureCreatingSecondCleanAssetAbortsFirstRatherThanPublishingHalfSet(): Unit = runImmediate {
        val context = context()
        val transaction = FaultTransaction(MemoryOutputTransaction(context, "second-asset"), fault = Fault.SecondAssetFailure)
        val input = SourceSet.Single(source(GoogleFixtures.v2Photo(), "live"))
        assertIs<CoreResult.Failure>(core.split(SplitRequest(input, output = transaction, context = context)))
        assertEquals(2, transaction.createCalls)
        assertEquals(TransactionState.Aborted, value(transaction.delegate.query()).state)
        assertTrue(transaction.delegate.committedAssets().isEmpty())
        assertEquals(0, transaction.commitCalls)
    }

    @Test
    fun cancellationAfterStagingWriteAbortsAndNeverCallsCommit(): Unit = runImmediate {
        var cancelled = false
        val context = context(Cancellation { cancelled })
        val transaction = FaultTransaction(MemoryOutputTransaction(context, "mid-cancel"), afterWrite = { cancelled = true })
        failure("CANCELLED", create(transaction, context))
        assertTrue(transaction.writeCalls >= 1)
        assertTrue(transaction.delegate.committedAssets().isEmpty())
        assertEquals(TransactionState.Aborted, value(transaction.delegate.query()).state)
        assertEquals(0, transaction.commitCalls)
    }

    @Test
    fun sourceChangesAfterPrepareAreRecheckedBeforeCommit(): Unit = runImmediate {
        val context = context()
        val video = TestSource(GoogleFixtures.video().bytes)
        val transaction = FaultTransaction(MemoryOutputTransaction(context, "change-at-prepare"), afterPrepare = {
            video.currentIdentity = video.currentIdentity.copy(generation = GenerationToken("new-generation"))
        })
        failure("SOURCE_CHANGED", create(transaction, context, video))
        assertTrue(transaction.events.contains("prepare"))
        assertTrue(transaction.delegate.committedAssets().isEmpty())
        assertEquals(TransactionState.Aborted, value(transaction.delegate.query()).state)
        assertFalse(video.closed)
    }

    @Test
    fun atomicAssetSetOrStagedReadCapabilitiesAreRequiredBeforeOutputCreation(): Unit = runImmediate {
        for (capabilities in listOf(OutputCapabilities(true, false, true, false, true), OutputCapabilities(true, true, true, false, false))) {
            val context = context()
            val transaction = FaultTransaction(MemoryOutputTransaction(context, "cap-$capabilities"), capabilityOverride = capabilities)
            assertIs<CoreResult.Failure>(create(transaction, context))
            assertEquals(0, transaction.createCalls)
            assertTrue(transaction.delegate.committedAssets().isEmpty())
        }
    }

    @Test
    fun rawSplitHonorsRequestedAtomicityAndReplacePolicy(): Unit = runImmediate {
        val context = context()
        val input = SourceSet.Single(source(GoogleFixtures.v2Photo(), "raw-live"))
        val noReplace = FaultTransaction(MemoryOutputTransaction(context, "raw-no-replace"), capabilityOverride = OutputCapabilities(true, true, false, false, true))
        assertIs<CoreResult.Failure>(core.split(SplitRequest(input, SplitMode.Raw, policy = MutationPolicy(existingOutput = ExistingOutput.Replace), output = noReplace, context = context)))
        assertEquals(0, noReplace.createCalls)
        assertTrue(noReplace.delegate.committedAssets().isEmpty())
        val weaker = FaultTransaction(MemoryOutputTransaction(context, "raw-explicit-per-asset"), capabilityOverride = OutputCapabilities(true, false, true, false, true))
        val result = value(core.split(SplitRequest(input, SplitMode.Raw, policy = MutationPolicy(atomicity = Atomicity.PerAssetExplicitlyAccepted), output = weaker, context = context)))
        assertEquals(2, result.output.assets.size)
        assertEquals(TransactionState.Committed, result.output.receipt.state)
    }

    @Test
    fun commitResponseLostAfterActualCommitIsRecoveredThroughQuery(): Unit = runImmediate {
        val context = context()
        val transaction = FaultTransaction(MemoryOutputTransaction(context, "recover-commit"), fault = Fault.CommitResponseLost)
        val result = value(create(transaction, context))
        assertEquals(TransactionState.Committed, result.output.receipt.state)
        assertEquals(1, transaction.commitCalls)
        assertTrue(transaction.queryCalls >= 1)
        assertEquals(0, transaction.abortCalls)
        assertEquals(1, transaction.delegate.committedAssets().size)
    }

    @Test
    fun unresolvedCommitOutcomeDoesNotPretendSuccessOrAbortPotentialCommittedOutput(): Unit = runImmediate {
        val context = context()
        val transaction = FaultTransaction(MemoryOutputTransaction(context, "unknown-commit"), fault = Fault.CommitAndQueryResponseLost)
        val failure = assertIs<CoreResult.Failure>(create(transaction, context))
        assertEquals(Stage.Publish, failure.error.stage)
        assertEquals(1, transaction.commitCalls)
        assertTrue(transaction.queryCalls >= 1)
        assertEquals(0, transaction.abortCalls)
        assertEquals(TransactionState.Committed, value(transaction.delegate.query()).state)
    }

    @Test
    fun existingCommittedOutputsRemainUntouchedWhenTransactionReuseIsRejected(): Unit = runImmediate {
        val context = context()
        val transaction = MemoryOutputTransaction(context, "existing")
        val old = value(transaction.create(OutputAssetSpec(AssetRole.PrimaryImage, mime = "image/jpeg")))
        value(old.sink.write(Bytes(byteArrayOf(7, 8, 9))))
        value(transaction.prepare()); value(transaction.commit())
        assertIs<CoreResult.Failure>(create(transaction, context))
        assertEquals(mapOf(old.id to Bytes(byteArrayOf(7, 8, 9))), transaction.committedAssets())
        assertEquals(TransactionState.Committed, value(transaction.query()).state)
    }

    @Test
    fun staleExtractionSnapshotCannotAuthorizeResourceFromNewSourceGeneration(): Unit = runImmediate {
        val context = context()
        val source = TestSource(GoogleFixtures.v2Photo())
        val input = SourceSet.Single(source)
        val inspected = value(core.inspect(ReadRequest(input, context)))
        val resource = inspected.layout.resources.single { it.kind == ResourceKind.Video }
        source.currentIdentity = source.currentIdentity.copy(generation = GenerationToken("new-generation"))
        val transaction = FaultTransaction(MemoryOutputTransaction(context, "stale-extract"))
        failure("SOURCE_CHANGED", core.extract(ExtractRequest(input, listOf(resource.id), inspected.snapshot, output = transaction, context = context)))
        assertEquals(0, transaction.createCalls)
        assertTrue(transaction.delegate.committedAssets().isEmpty())
        assertFalse(source.closed)
    }

    @Test
    fun replacementMemorySourceWithSameIdAndSizeCannotReuseOldExtractionSnapshot(): Unit = runImmediate {
        val context = context()
        val bytes = GoogleFixtures.v2Photo()
        val providerId = SourceId("reopened-file-id")
        val first = SourceSet.Single(MemoryBinarySource(Bytes(bytes), providerId))
        val inspected = value(core.inspect(ReadRequest(first, context)))
        val changed = bytes.copyOf()
        changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
        val reopened = SourceSet.Single(MemoryBinarySource(Bytes(changed), providerId))
        val transaction = FaultTransaction(MemoryOutputTransaction(context, "reopened-stale-extract"))
        failure("SOURCE_CHANGED", core.extract(ExtractRequest(reopened, listOf(inspected.layout.resources.single { it.kind == ResourceKind.Video }.id), inspected.snapshot, output = transaction, context = context)))
        assertEquals(0, transaction.createCalls)
        assertTrue(transaction.delegate.committedAssets().isEmpty())
    }

    @Test
    fun duplicateInputProviderIdentityCannotConfuseCreateBindings(): Unit = runImmediate {
        val context = context()
        val transaction = FaultTransaction(MemoryOutputTransaction(context, "alias-input"))
        val shared = SourceId("same-source-identity")
        val image = MemoryBinarySource(Bytes(GoogleFixtures.jpeg()), shared)
        val video = MemoryBinarySource(Bytes(GoogleFixtures.video().bytes), shared)
        failure("INVALID_ARGUMENT", core.create(CreateRequest(image, video, target, output = transaction, context = context)))
        assertEquals(0, transaction.createCalls)
        assertTrue(transaction.delegate.committedAssets().isEmpty())
    }

    @Test
    fun createHonorsTwoInputSourceBudgetBeforeCreatingOutput(): Unit = runImmediate {
        val context = Context(Limits(2_000_000uL, 2_000_000uL, maxSources = 1u, maxMetadataBytes = 1_000_000uL))
        val transaction = FaultTransaction(MemoryOutputTransaction(context, "source-count"))
        failure("RESOURCE_LIMIT_EXCEEDED", create(transaction, context))
        assertEquals(0, transaction.createCalls)
        assertTrue(transaction.delegate.committedAssets().isEmpty())
    }

    @Test
    fun outputBudgetFailureDoesNotPublishCarrierPrefix(): Unit = runImmediate {
        val context = context(output = 32uL)
        val transaction = MemoryOutputTransaction(context, "too-small")
        failure("RESOURCE_LIMIT_EXCEEDED", create(transaction, context))
        assertTrue(transaction.committedAssets().isEmpty())
        assertTrue(value(transaction.query()).state != TransactionState.Committed)
    }

    private enum class Fault { None, WriteZero, WriteFailure, PrepareFailure, StagedReadFailure, StagedCorruption, SecondStagedAliasesInputId, SecondAssetFailure, CommitResponseLost, CommitAndQueryResponseLost }
    private class FaultTransaction(
        val delegate: MemoryOutputTransaction,
        private val fault: Fault = Fault.None,
        private val shortWrite: Int = Int.MAX_VALUE,
        private val shortRead: UInt = UInt.MAX_VALUE,
        private val afterWrite: (() -> Unit)? = null,
        private val afterPrepare: (() -> Unit)? = null,
        private val capabilityOverride: OutputCapabilities? = null,
    ) : OutputTransaction {
        val events = mutableListOf<String>()
        var createCalls = 0; var writeCalls = 0; var readCalls = 0; var commitCalls = 0; var abortCalls = 0; var queryCalls = 0
        private var stagedOpenCalls = 0
        override fun capabilities(): OutputCapabilities = capabilityOverride ?: delegate.capabilities()
        override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> {
            createCalls++
            if (fault == Fault.SecondAssetFailure && createCalls == 2) return providerFailure(Stage.WriteProtocol)
            val result = delegate.create(spec)
            if (result is CoreResult.Failure) return result
            val handle = (result as CoreResult.Success).value
            return CoreResult.Success(OutputHandle(handle.id, object : BinarySink by handle.sink {
                override suspend fun write(bytes: Bytes): CoreResult<UInt> {
                    writeCalls++
                    if (fault == Fault.WriteZero) return CoreResult.Success(0u)
                    if (fault == Fault.WriteFailure) return providerFailure(Stage.WriteProtocol)
                    val result = handle.sink.write(bytes.slice(0, minOf(bytes.size, shortWrite)))
                    afterWrite?.invoke()
                    return result
                }
            }))
        }
        override suspend fun prepare(): CoreResult<Unit> {
            events += "prepare"
            if (fault == Fault.PrepareFailure) return providerFailure(Stage.Verify)
            val result = delegate.prepare()
            afterPrepare?.invoke()
            return result
        }
        override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> {
            stagedOpenCalls++
            val aliasesInput = fault == Fault.SecondStagedAliasesInputId && stagedOpenCalls == 2
            if (fault == Fault.StagedReadFailure) return providerFailure(Stage.Verify)
            val result = delegate.openStaged(id)
            if (result is CoreResult.Failure) return result
            val source = (result as CoreResult.Success).value
            return CoreResult.Success(object : BinarySource by source {
                override suspend fun identity(): CoreResult<SourceIdentity> = when (val identity = source.identity()) {
                    is CoreResult.Success -> if (aliasesInput) CoreResult.Success(identity.value.copy(id = SourceId("cover"))) else identity
                    is CoreResult.Failure -> identity
                }
                override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> {
                    readCalls++; events += "staged-read"
                    val result = source.readAt(offset, minOf(length, shortRead))
                    if (fault != Fault.StagedCorruption || result is CoreResult.Failure) return result
                    val bytes = (result as CoreResult.Success).value.toByteArray()
                    if (offset == 0uL && bytes.isNotEmpty()) bytes[0] = (bytes[0].toInt() xor 1).toByte()
                    return CoreResult.Success(Bytes(bytes))
                }
            })
        }
        override suspend fun commit(): CoreResult<Receipt> {
            commitCalls++; events += "commit"
            val result = delegate.commit()
            return if (fault in setOf(Fault.CommitResponseLost, Fault.CommitAndQueryResponseLost)) providerFailure(Stage.Publish) else result
        }
        override suspend fun abort(): CoreResult<Unit> { abortCalls++; return delegate.abort() }
        override suspend fun query(): CoreResult<Receipt> {
            queryCalls++
            if (fault == Fault.CommitAndQueryResponseLost && commitCalls > 0) return providerFailure(Stage.Publish)
            return delegate.query()
        }
        private fun providerFailure(stage: Stage): CoreResult.Failure = CoreResult.Failure(CoreError(IssueCode("IO_WRITE_FAILED"), stage, "Injected provider response failure", recoverability = Recoverability.AfterRetry))
    }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
    private fun failure(code: String, result: CoreResult<*>) { assertEquals(IssueCode(code), assertIs<CoreResult.Failure>(result).error.code) }
}
