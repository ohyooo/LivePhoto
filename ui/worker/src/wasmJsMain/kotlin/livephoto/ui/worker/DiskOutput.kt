@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package livephoto.ui.worker

import kotlin.js.*
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.ArrayBuffer
import org.w3c.files.Blob
import livephoto.core.*
import livephoto.ui.UiOutput

private external interface OutputModule : JsAny { fun createOutputJob(): Promise<OutputJob> }
private external interface OutputJob : JsAny { val id: String; fun create(name: String): Promise<OutputFile>; fun finish(keep: Boolean): Promise<JsAny?> }
private external interface OutputFile : JsAny {
    fun write(bytes: Int8Array, offset: Double): Int
    fun truncate(length: Double)
    fun flush()
    fun close()
    fun freeze(): Promise<Blob>
}
private fun loadOutput(): Promise<OutputModule> = js("import(/* webpackIgnore: true */ new URL('../output-storage.mjs',self.location.href).href)")
private fun setByte(array: Int8Array, index: Int, value: Int): Unit = js("{ array[index]=value; }")
private fun getByte(array: Int8Array, index: Int): Byte = js("array[index]")
private fun readPart(blob: Blob, offset: Double, length: Int): Promise<ArrayBuffer> = js("blob.slice(offset,offset+length).arrayBuffer()")
private fun sizeOf(blob: Blob): Double = js("blob.size")

/** One operation owns the cleanup scope; committed File snapshots keep their disk backing. */
internal class DiskSession {
    private var job: OutputJob? = null
    private suspend fun active(): OutputJob = job ?: loadOutput().await().createOutputJob().await().also { job = it }
    suspend fun finish(keep: Boolean): String {
        val active = job ?: return ""
        active.finish(keep).await()
        return if (keep) active.id else ""
    }
    fun output(context: Context, token: String): UiOutput<Blob> = DiskTransaction(context, token)
    private inner class DiskTransaction(context: Context, token: String) : UiOutput<Blob> {
        private val assets = linkedMapOf<AssetId, Blob>()
        private val transaction = StagedOutputTransaction(context, token, StagedFileFactory { id, _ -> io(Stage.WriteProtocol) {
            val handle = active().create(id.value).await()
            object : StagedFile {
                var position = 0uL
                var closed = false
                override val sink: BinarySink = object : BinarySink {
                    override suspend fun write(bytes: Bytes): CoreResult<UInt> = io(Stage.WriteProtocol) {
                        check(!closed)
                        var written = 0
                        while (written < bytes.size) {
                            val chunk = Int8Array(minOf(65536, bytes.size - written))
                            for (index in 0 until chunk.length) setByte(chunk, index, bytes[written + index].toInt())
                            val count = handle.write(chunk, position.toDouble())
                            check(count in 1..chunk.length)
                            written += count; position += count.toULong()
                        }
                        written.toUInt()
                    }
                    override suspend fun seek(offset: ULong): CoreResult<Unit> = io(Stage.WriteProtocol) { check(!closed); position = offset }
                    override suspend fun truncate(length: ULong): CoreResult<Unit> = io(Stage.WriteProtocol) { check(!closed); handle.truncate(length.toDouble()); position = minOf(position, length) }
                    override suspend fun flush(): CoreResult<Unit> = io(Stage.WriteProtocol) { check(!closed); handle.flush() }
                    override suspend fun close(): CoreResult<Unit> = io(Stage.WriteProtocol) { closed = true; handle.close() }
                }
                override suspend fun freeze(): CoreResult<Unit> = io(Stage.Verify) { closed = true; assets[id] = handle.freeze().await() }
                override suspend fun openSource(): CoreResult<BinarySource> = io(Stage.Verify) {
                    val file = assets.getValue(id)
                    object : BinarySource {
                        var closed = false
                        override suspend fun size(): CoreResult<ULong> = io(Stage.Read) { check(!closed); sizeOf(file).toULong() }
                        override suspend fun identity(): CoreResult<SourceIdentity> = io(Stage.Read) { check(!closed); SourceIdentity(SourceId("disk:$token:${id.value}"), GenerationToken(token), sizeOf(file).toULong()) }
                        override suspend fun readAt(offset: ULong, length: UInt): CoreResult<Bytes> = io(Stage.Read) {
                            check(!closed && offset <= sizeOf(file).toULong())
                            val count = minOf(length.toULong(), 65536uL, sizeOf(file).toULong() - offset).toInt()
                            val bytes = Int8Array(readPart(file, offset.toDouble(), count).await())
                            check(bytes.length == count)
                            Bytes(ByteArray(count) { getByte(bytes, it) })
                        }
                        override suspend fun close() { closed = true }
                    }
                }
            }
        })
        override fun capabilities(): OutputCapabilities = transaction.capabilities()
        override suspend fun create(spec: OutputAssetSpec): CoreResult<OutputHandle> = transaction.create(spec)
        override suspend fun prepare(): CoreResult<Unit> = transaction.prepare()
        override suspend fun openStaged(id: AssetId): CoreResult<BinarySource> = transaction.openStaged(id)
        override suspend fun commit(): CoreResult<Receipt> = transaction.commit()
        override suspend fun abort(): CoreResult<Unit> = transaction.abort()
        override suspend fun query(): CoreResult<Receipt> = transaction.query()
        override fun asset(id: AssetId): Blob { check(transaction.isCommitted(id)); return assets.getValue(id) }
    }
}
private suspend inline fun <T> io(stage: Stage, block: () -> T): CoreResult<T> = try { CoreResult.Success(block()) }
catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
catch (_: Exception) { CoreResult.Failure(CoreError(IssueCode(if (stage == Stage.Read) "IO_READ_FAILED" else "IO_WRITE_FAILED"), stage, "Browser private output storage is unavailable or full")) }
