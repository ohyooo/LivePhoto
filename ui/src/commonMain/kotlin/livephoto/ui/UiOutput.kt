package livephoto.ui

import livephoto.core.*
import livephoto.core.memory.MemoryOutputTransaction

/** Same application checks for memory adapters and browser disk-backed files. */
public interface UiOutput<out T> : OutputTransaction {
    public fun asset(id: AssetId): T
}
public typealias UiOutputFactory<T> = (Context, String) -> UiOutput<T>
public class MemoryUiOutput private constructor(private val memory: MemoryOutputTransaction) : UiOutput<Bytes>, OutputTransaction by memory {
    public constructor(context: Context, token: String) : this(MemoryOutputTransaction(context, token))
    override fun asset(id: AssetId): Bytes = memory.committedAssets().getValue(id)
}
