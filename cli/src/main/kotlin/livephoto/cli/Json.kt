package livephoto.cli

import livephoto.cli.shared.CliJson
import livephoto.core.jvm.DirectoryOutputTransaction

/** Compatibility bridge for JVM callers; production JSON uses no reflection. */
internal object Json {
    fun encode(value: Any?, output: DirectoryOutputTransaction? = null): String =
        CliJson.encode(value, output?.let { transaction -> { id -> transaction.assetPath(id).toString() } })
}
