@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package livephoto.adapters.files
internal actual val nativeWindows: Boolean = false
internal actual fun nativeExecutable(path: String): Boolean = platform.posix.access(path, platform.posix.X_OK) == 0
