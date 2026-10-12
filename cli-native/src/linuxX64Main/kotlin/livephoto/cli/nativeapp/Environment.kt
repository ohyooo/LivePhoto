@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package livephoto.cli.nativeapp
import kotlinx.cinterop.toKString
import platform.posix.getenv
internal actual fun hostEnvironment(name: String): String? = getenv(name)?.toKString()
