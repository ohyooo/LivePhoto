package livephoto.android

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

internal data class PickedDocument(val uri: Uri, val mayPersistRead: Boolean)

/** Preserve the system result flags, which the standard URI-only contract discards. */
internal object OpenReadDocument : ActivityResultContract<Array<String>, PickedDocument?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        ActivityResultContracts.OpenDocument().createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)

    override fun parseResult(resultCode: Int, intent: Intent?): PickedDocument? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        val uri = intent.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT } ?: return null
        val required = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        return PickedDocument(uri, (intent.flags and required) == required)
    }
}

/** A tiny app-private journal lets the next task release its own orphaned grants after abrupt exit. */
internal class AndroidDocumentReadPermissions(context: Context) : DocumentReadPermissions {
    private val resolver = context.contentResolver
    private val journal = context.getSharedPreferences("document-read-grants", Context.MODE_PRIVATE)
    private val owned: Set<String> get() = journal.getStringSet("reads", emptySet()).orEmpty()

    override fun retain(uri: String): Boolean {
        val document = Uri.parse(uri)
        val tracked = owned
        if (resolver.persistedUriPermissions.any { it.uri == document && it.isReadPermission }) {
            // A pre-existing read grant not acquired here belongs to someone else in the app.
            return uri in tracked
        }
        // Journal ownership before acquiring across the binder; if the process dies in between,
        // the next start can safely reconcile the marker against only its own grants.
        if (!journal.edit().putStringSet("reads", tracked + uri).commit()) return false
        return try {
            resolver.takePersistableUriPermission(document, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            journal.edit().putStringSet("reads", tracked - uri).commit()
            false // Provider may only allow a temporary grant; keep the URI for this task anyway.
        }
    }

    override fun release(uri: String) {
        val tracked = owned
        if (uri !in tracked) return
        try { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { /* Already revoked or an acquisition was interrupted. */ }
        journal.edit().putStringSet("reads", tracked - uri).commit()
    }

    fun discardOrphans(retainedReads: Set<String>) {
        (owned - retainedReads).forEach(::release)
    }
}

internal fun Bundle?.documentSelections(): DocumentReadLeases.Snapshot {
    val ids = this?.getStringArrayList("references").orEmpty()
    val uris = this?.getStringArrayList("uris").orEmpty()
    return DocumentReadLeases.Snapshot(ids.zip(uris).toMap(), this?.getStringArrayList("reads").orEmpty().toSet())
}

internal fun DocumentReadLeases.Snapshot.toBundle(): Bundle = Bundle().apply {
    putStringArrayList("references", ArrayList(references.keys))
    putStringArrayList("uris", ArrayList(references.values))
    putStringArrayList("reads", ArrayList(retainedReads))
}
