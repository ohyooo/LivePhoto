package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*

/** No transaction/commit authority is exposed to a media backend. Core alone freezes and rereads. */
internal class BackendSingleAssetStaging(private val expected: OutputAssetSpec, private val writer: BinaryWriter, private val context: Context, private val stage: Stage) : StagingArea {
    private val id = AssetId("backend-single-asset")
    private var created = false
    private var closed = false
    private var length = 0uL
    override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = attempt {
        checkCancelled(context)
        if (created || spec.role != expected.role || spec.mime != expected.mime) fail("POSTCONDITION_FAILED", "Backend may create only the requested asset", stage)
        created = true
        OutputHandle(id, object : BinarySink {
            override suspend fun write(bytes: Bytes): CoreResult<UInt> = attempt {
                if (closed) fail("IO_WRITE_FAILED", "Backend staging writer is closed", stage)
                writer.writeAll(bytes).orThrow(); length = checkedAdd(length, bytes.size.toULong()); bytes.size.toUInt()
            }
            override suspend fun seek(offset: ULong): CoreResult<Unit> = unsupported()
            override suspend fun truncate(length: ULong): CoreResult<Unit> = unsupported()
            override suspend fun flush(): CoreResult<Unit> = attempt<Unit> { checkCancelled(context); if (closed) fail("IO_WRITE_FAILED", "Backend staging writer is closed", stage) }
            override suspend fun close(): CoreResult<Unit> { closed = true; return CoreResult.Success(Unit) }
        })
    }
    override suspend fun openForRead(id: AssetId): CoreResult<BinarySource> = unsupported()
    private fun unsupported(): CoreResult.Failure = CoreResult.Failure(CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), stage, "Only Core may reread after prepare"))
    fun validateResult(result: BackendResult) {
        val asset = result.assets.singleOrNull()
        if (!created || !closed || length == 0uL || asset == null || asset.id != id || asset.role != expected.role || asset.mime != expected.mime || asset.byteLength != length)
            fail("POSTCONDITION_FAILED", "Backend declaration contradicts isolated staging writes", Stage.Verify)
    }
}
