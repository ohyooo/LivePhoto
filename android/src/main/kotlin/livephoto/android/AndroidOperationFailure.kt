package livephoto.android

import kotlinx.coroutines.CancellationException

/** Adapter provenance only; never classify a failure by parsing exception text. */
internal class AndroidOperationFailure(val step: String, val inputRole: String, cause: Exception) : Exception(cause.message, cause)

internal suspend fun <T> androidOperation(step: String, role: String, block: suspend () -> T): T = try { block() }
catch (cancelled: CancellationException) { throw cancelled }
catch (failure: AndroidOperationFailure) { throw failure }
catch (failure: Exception) { throw AndroidOperationFailure(step, role, failure) }
