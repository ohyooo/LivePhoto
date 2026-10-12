package livephoto.cli.nativeapp

import livephoto.adapters.files.*
import livephoto.cli.shared.*
import livephoto.core.*
import okio.Path.Companion.toPath
import kotlin.random.Random

/** Thin CLI IO glue, never media/protocol logic. Serialized runs only.
 * Workspace must be an existing caller-controlled private local directory.
 * No ambient/shared temp fallback; all snapshot files are released by finish(). */
internal abstract class FileCliPlatform(private val workspace: () -> String) : CliPlatform {
    private var session: FileInputSession? = null
    private var operation: Context? = null

    override fun openSource(path: String, context: Context): BinarySource {
        val inputs = session ?: run {
            val parent = workspace()
            if (parent.isBlank()) throw CliAdapterFailure(CoreError(IssueCode("INVALID_ARGUMENT"), Stage.Read,
                "Set LIVEPHOTO_WORKSPACE to an existing private local directory"))
            val token = List(4) { Random.nextLong().toULong().toString(16) }.joinToString("-")
            FileInputSession((parent.toPath() / "livephoto-input-$token").toString(), context).also {
                session = it; operation = context
            }
        }
        if (operation !== context) throw CliAdapterFailure(CoreError(IssueCode("INVALID_ARGUMENT"), Stage.Read,
            "An input session cannot span operations; finish the previous session first"))
        return when (val result = inputs.capture(path)) {
            is CoreResult.Success -> result.value
            is CoreResult.Failure -> throw CliAdapterFailure(result.error)
        }
    }

    override fun output(path: String, context: Context): CliOutput {
        val transaction = FileOutputTransaction(path, context)
        return CliOutput(transaction, transaction::assetPath)
    }

    override fun finish(): CoreResult<Unit> {
        val result = session?.close() ?: CoreResult.Success(Unit)
        if (result is CoreResult.Success) { session = null; operation = null }
        return result // On failure retain ownership so cleanup can be retried, never scan/delete unknown files.
    }

    override fun discover(ffmpegPath: String?): CliDiscovery = CliDiscovery(null, null, listOf(Issue(
        IssueCode("CAPABILITY_UNSUPPORTED"), Severity.Warning, Layer.Media,
        observed = Value.Text("Native FFmpeg adapter is not implemented; no executable was invoked"))))
}
