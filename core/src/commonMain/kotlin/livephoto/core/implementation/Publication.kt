package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*

internal data class AssetVerification(val validation: ValidationReport, val guarantees: List<GuaranteeRecord>, val key: KeyPhotoResult? = null)
internal data class StagedAsset(
    val spec: OutputAssetSpec,
    val imageFormat: ImageFormat? = null,
    val container: VideoContainer? = null,
    val write: suspend (BinaryWriter) -> Unit,
    val verify: suspend (AssetId, BinaryReader) -> AssetVerification,
)

/** Single publication path for all protocol handlers. No handler can commit independently. */
internal suspend fun publish(
    output: OutputTransaction, policy: MutationPolicy, context: Context,
    inputs: List<BinaryReader>, assets: List<StagedAsset>, changes: List<Change> = emptyList(),
): CoreResult<OperationResult> {
    val resultHandles = mutableListOf<BinarySource>()
    var ownsOpenTransaction = false
    var commitAttempted = false
    try {
        if (assets.isEmpty()) fail("INVALID_ARGUMENT", "Publication requires at least one asset", Stage.Plan)
        checkCancelled(context)
        val initial = output.query().orThrow()
        if (initial.state != TransactionState.Open || initial.assetIds.isNotEmpty()) fail("INVALID_ARGUMENT", "Output transaction must be empty and open", Stage.Publish)
        val capabilities = output.capabilities()
        if (!capabilities.canReadStaged || policy.atomicity == Atomicity.AssetSetRequired && !capabilities.assetSetAtomic ||
            policy.existingOutput == ExistingOutput.Replace && !capabilities.replacesAtomically) fail("ATOMIC_PUBLICATION_UNAVAILABLE", "Output cannot provide required verification and atomic publication", Stage.Plan)
        for (input in inputs) input.validateIdentity().orThrow()
        ownsOpenTransaction = true
        val sharedBudget = OutputBudget(context)
        val handles = mutableListOf<OutputHandle>()
        for (asset in assets) {
            checkCancelled(context)
            val handle = output.create(asset.spec).orThrow()
            if (handles.any { it.id == handle.id || it.sink === handle.sink }) fail("POSTCONDITION_FAILED", "Transaction reused an asset identity or staging sink", Stage.WriteProtocol)
            if (inputs.any { it.source as Any === handle.sink as Any }) fail("OUTPUT_ALIASES_INPUT", "Staging sink aliases a borrowed input", Stage.WriteProtocol)
            handles += handle
            var writeFailure: CoreFault? = null
            try {
                asset.write(BinaryWriter(handle.sink, context, sharedBudget))
                handle.sink.flush().orThrow()
            } catch (fault: CoreFault) { writeFailure = fault }
            finally {
                when (val closed = handle.sink.close()) {
                    is CoreResult.Success -> Unit
                    is CoreResult.Failure -> {
                        val original = writeFailure
                        writeFailure = if (original == null) CoreFault(closed.error) else CoreFault(original.error.copy(details = original.error.details + ("sinkCloseFailure" to Value.Text(closed.error.code.value))))
                    }
                }
            }
            writeFailure?.let { throw it }
        }
        output.prepare().orThrow()
        val verified = mutableListOf<AssetVerification>()
        val outputs = mutableListOf<OutputAsset>()
        val stagedIdentities = mutableListOf<SourceIdentity>()
        for ((index, handle) in handles.withIndex()) {
            checkCancelled(context)
            val source = output.openStaged(handle.id).orThrow()
            if (inputs.any { it.source === source }) fail("OUTPUT_ALIASES_INPUT", "Staged reader aliases an input", Stage.Verify)
            var digest: Digest
            var size: ULong
            var borrowedAlias = false
            try {
                val reader = BinaryReader(source, context)
                val identity = reader.identity().orThrow()
                if (inputs.any { it.identity().orThrow().id == identity.id }) { borrowedAlias = true; fail("OUTPUT_ALIASES_INPUT", "Staged identity aliases an input", Stage.Verify) }
                stagedIdentities += identity
                size = identity.size
                val verification = assets[index].verify(handle.id, reader)
                if (verification.validation.verdict == Verdict.Invalid || verification.validation.issues.any { it.severity == Severity.Error }) fail("POSTCONDITION_FAILED", "Staged asset failed required verification", Stage.Verify)
                verified += verification
                digest = sha256Range(reader, ByteRange(0uL, size)).orThrow()
            } finally { if (!borrowedAlias) source.close() }
            // This separate handle is transferred to the successful result, never the verifier.
            val accessible = output.openStaged(handle.id).orThrow()
            if (inputs.any { it.source === accessible }) fail("OUTPUT_ALIASES_INPUT", "Result reader aliases borrowed input", Stage.Verify)
            val accessibleReader = BinaryReader(accessible, context)
            var accessibleAlias = false
            val accessibleIdentity = try {
                val identity = accessibleReader.identity().orThrow()
                if (inputs.any { it.identity().orThrow().id == identity.id }) { accessibleAlias = true; fail("OUTPUT_ALIASES_INPUT", "Result reader identity aliases input", Stage.Verify) }
                identity
            } catch (fault: CoreFault) {
                if (!accessibleAlias) accessible.close()
                throw fault
            }
            resultHandles += accessible
            if (accessibleIdentity.size != size || sha256Range(accessibleReader, ByteRange(0uL, size)).orThrow() != digest) fail("POSTCONDITION_FAILED", "Prepared asset changed between staged readers", Stage.Verify)
            outputs += OutputAsset(handle.id, assets[index].spec.role, readableSource = accessible, byteLength = size,
                mime = assets[index].spec.mime, imageFormat = assets[index].imageFormat, videoContainer = assets[index].container, digest = digest)
        }
        val records = verified.flatMap { it.guarantees }
        if (stagedIdentities.map { it.id }.distinct().size != stagedIdentities.size) fail("POSTCONDITION_FAILED", "Different staged assets reused one source identity", Stage.Verify)
        val snapshotHash = Sha256()
        for (identity in stagedIdentities) for (field in listOf(identity.id.value, identity.generation.value, identity.size.toString(), identity.digest?.value ?: "")) {
            val bytes = Bytes(field.encodeToByteArray()); snapshotHash.update(unsignedBytes(bytes.size.toULong(), 8, Endian.Big)); snapshotHash.update(bytes)
        }
        val stagedSnapshot = Snapshot(stagedIdentities, GenerationToken(snapshotHash.finish().value))
        if (policy.preservation == PreservationPolicy.Strict && records.any { it.outcome == GuaranteeOutcome.Unknown || it.outcome == GuaranteeOutcome.Changed }) fail("PRESERVATION_REQUIREMENT_FAILED", "Strict preservation lacks a proof for every applicable guarantee", Stage.Verify)
        for (asset in outputs) for (guarantee in policy.requiredGuarantees) if (records.none { it.assetId == asset.id && it.guarantee == guarantee && it.outcome in setOf(GuaranteeOutcome.Verified, GuaranteeOutcome.NotApplicable) }) {
            fail("PRESERVATION_REQUIREMENT_FAILED", "Required guarantee lacks verification for an output asset", Stage.Verify)
        }
        for (input in inputs) input.validateIdentity().orThrow()
        checkCancelled(context)
        commitAttempted = true
        val committed = output.commit()
        val receipt = when (committed) {
            is CoreResult.Success -> committed.value
            is CoreResult.Failure -> when (val recovery = output.query()) {
                is CoreResult.Success -> if (recovery.value.state == TransactionState.Committed) recovery.value else fail("PUBLICATION_STATE_UNKNOWN", "Commit failed and publication is not confirmed", Stage.Publish)
                is CoreResult.Failure -> fail("PUBLICATION_STATE_UNKNOWN", "Commit failed and publication state could not be queried", Stage.Publish)
            }
        }
        if (receipt.state != TransactionState.Committed || receipt.token != initial.token || receipt.assetIds.toSet() != handles.map { it.id }.toSet() || receipt.assetIds.size != handles.size ||
            policy.atomicity == Atomicity.AssetSetRequired && receipt.atomicity != Atomicity.AssetSetRequired) fail("PUBLICATION_STATE_UNKNOWN", "Committed receipt does not match the verified asset set", Stage.Publish)
        val checks = verified.flatMapIndexed { index, verification -> verification.validation.checks.map { it.copy(id = "${handles[index].id.value}:${it.id}") } }
        val coverage = if (checks.all { it.coverage == Coverage.Complete }) Coverage.Complete else if (checks.all { it.coverage == Coverage.NotRun }) Coverage.NotRun else Coverage.Partial
        val validation = ValidationReport(if (coverage == Coverage.Complete && checks.all { it.verdict == Verdict.Valid }) Verdict.Valid else Verdict.Warning,
            coverage, checks, verified.flatMap { it.validation.issues }, stagedSnapshot)
        return CoreResult.Success(OperationResult(MediaOutput(outputs, receipt), validation,
            PreservationReport(records, changes), execution = listOf(
                ExecutionRecord(Stage.Verify, reason = "Independently reread every prepared asset and rechecked borrowed source identities", transcoded = false, remuxed = false),
                ExecutionRecord(Stage.Publish, reason = "Confirmed the complete verified asset set in the transaction receipt", transcoded = false, remuxed = false)),
            keyPhoto = verified.firstNotNullOfOrNull { it.key }))
    } catch (fault: CoreFault) {
        for (handle in resultHandles) handle.close()
        var error = fault.error
        if (ownsOpenTransaction && !commitAttempted) when (val abort = output.abort()) {
            is CoreResult.Success -> Unit
            is CoreResult.Failure -> error = error.copy(details = error.details + ("abortFailure" to Value.Text(abort.error.code.value)))
        }
        return CoreResult.Failure(error)
    }
}
