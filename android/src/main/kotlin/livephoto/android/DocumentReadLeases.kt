package livephoto.android

import java.util.UUID

/** Platform port; returns true only for a persisted read grant managed by this adapter. */
internal interface DocumentReadPermissions {
    fun retain(uri: String): Boolean
    fun release(uri: String)
}

/** Main-thread selection ownership. Each picker result owns an independent reference. */
internal class DocumentReadLeases(
    private val permissions: DocumentReadPermissions,
    saved: Snapshot = Snapshot(emptyMap(), emptySet()),
) {
    data class Snapshot(val references: Map<String, String>, val retainedReads: Set<String>)

    private val references = saved.references.toMutableMap()
    private val retainedReads = saved.retainedReads.intersect(references.values.toSet()).toMutableSet()

    fun select(uri: String, mayPersist: Boolean): String {
        if (mayPersist && permissions.retain(uri)) retainedReads += uri
        val reference = UUID.randomUUID().toString()
        references[reference] = uri
        return reference
    }

    fun resolve(reference: String): String? = references[reference]

    fun release(reference: String) {
        val uri = references.remove(reference) ?: return
        if (uri !in references.values && retainedReads.remove(uri)) permissions.release(uri)
    }

    fun close() = references.keys.toList().forEach(::release)

    fun snapshot(): Snapshot = Snapshot(references.toMap(), retainedReads.toSet())
}
