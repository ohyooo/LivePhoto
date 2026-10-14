package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.CoreFault

/** Machine-readable provenance. No filenames, UI strings, or message-text classification. */
internal class FailureContext(private val operation: String? = null) {
    private var step = "validate-request"
    private var role = "request"
    private var source: SourceId? = null

    fun at(step: String, role: String, source: SourceId? = null) {
        this.step = step; this.role = role; this.source = source
    }

    fun annotate(error: CoreError): CoreError {
        val defaults = mutableMapOf<String, Value>("livephoto.step" to Value.Text(step), "livephoto.inputRole" to Value.Text(role))
        operation?.let { defaults["livephoto.operation"] = Value.Text(it) }
        // More specific nested provenance wins; retain the original code, stage,
        // message, recoverability, location fields, and backend details.
        val details = defaults + error.details
        val location = if (error.location?.source != null || source == null ||
            (details["livephoto.inputRole"] as? Value.Text)?.value != role) error.location
        else (error.location ?: Location()).copy(source = source)
        return error.copy(location = location, details = details)
    }
}

internal suspend fun <T> createAttempt(block: suspend (FailureContext) -> T): CoreResult<T> {
    val context = FailureContext("create")
    return try { CoreResult.Success(block(context)) }
    catch (fault: CoreFault) { CoreResult.Failure(context.annotate(fault.error)) }
}
