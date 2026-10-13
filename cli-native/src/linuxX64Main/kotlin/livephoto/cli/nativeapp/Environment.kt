@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package livephoto.cli.nativeapp
import kotlinx.cinterop.toKString
import platform.posix.getenv
import livephoto.core.*
internal actual fun hostEnvironment(name: String): String? = getenv(name)?.toKString()
internal actual fun hostArguments(fallback: Array<String>): CoreResult<List<String>> = CoreResult.Success(fallback.toList())
