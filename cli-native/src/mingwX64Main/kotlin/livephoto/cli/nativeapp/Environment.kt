@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package livephoto.cli.nativeapp
import kotlinx.cinterop.*
import platform.windows.GetEnvironmentVariableW
import platform.windows.GetCommandLineW
import platform.windows.CommandLineToArgvW
import platform.windows.LocalFree
import livephoto.core.*

internal actual fun hostEnvironment(name: String): String? = memScoped {
    val size = GetEnvironmentVariableW(name.wcstr.ptr, null, 0u)
    if (size == 0u || size > 32768u) return@memScoped null
    val buffer = allocArray<UShortVar>(size.toInt())
    val read = GetEnvironmentVariableW(name.wcstr.ptr, buffer, size)
    if (read == 0u || read >= size) return@memScoped null
    CharArray(read.toInt()) { buffer[it].toInt().toChar() }.concatToString()
}

/** Do not interpret Windows CRT narrow argv as UTF-8. Let the OS parse wide arguments,
 * including quoting/backslashes, and free the one allocation after copying strings. */
internal actual fun hostArguments(fallback: Array<String>): CoreResult<List<String>> = memScoped {
    fun failed(): CoreResult<List<String>> = CoreResult.Failure(CoreError(IssueCode("INVALID_ARGUMENT"),
        Stage.Read, "Could not read the Unicode command line"))
    val command = GetCommandLineW() ?: return@memScoped failed()
    val count = alloc<IntVar> { value = 0 }
    val arguments = CommandLineToArgvW(command, count.ptr) ?: return@memScoped failed()
    try {
        if (count.value !in 1..32768) return@memScoped failed()
        CoreResult.Success(List(count.value - 1) { index ->
            checkNotNull(arguments[index + 1]).toKString()
        })
    } finally { LocalFree(arguments) }
}
