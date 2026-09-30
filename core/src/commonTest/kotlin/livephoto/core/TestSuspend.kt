package livephoto.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** In-memory test adapters complete immediately; real asynchronous suspensions are rejected. */
internal fun <T> runImmediate(block: suspend () -> T): T {
    var completed: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { completed = result }
    })
    return checkNotNull(completed) { "Test adapter unexpectedly suspended asynchronously" }.getOrThrow()
}
