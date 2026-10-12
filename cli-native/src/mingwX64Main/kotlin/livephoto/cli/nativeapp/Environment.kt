@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package livephoto.cli.nativeapp
import kotlinx.cinterop.*
import platform.windows.GetEnvironmentVariableW

internal actual fun hostEnvironment(name: String): String? = memScoped {
    val size = GetEnvironmentVariableW(name.wcstr.ptr, null, 0u)
    if (size == 0u || size > 32768u) return@memScoped null
    val buffer = allocArray<UShortVar>(size.toInt())
    val read = GetEnvironmentVariableW(name.wcstr.ptr, buffer, size)
    if (read == 0u || read >= size) return@memScoped null
    CharArray(read.toInt()) { buffer[it].toInt().toChar() }.concatToString()
}
