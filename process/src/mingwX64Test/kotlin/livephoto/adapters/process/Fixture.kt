@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package livephoto.adapters.process
import kotlinx.cinterop.*
import platform.windows.GetEnvironmentVariableW
internal actual fun fixtureExecutable(): String = memScoped {
    val name = "LIVEPHOTO_PROCESS_FIXTURE".wcstr.ptr
    val length = GetEnvironmentVariableW(name, null, 0u)
    require(length in 1u..32768u) { "Missing native process fixture" }
    val value = allocArray<UShortVar>(length.toInt())
    val copied = GetEnvironmentVariableW(name, value, length)
    require(copied in 1u until length)
    CharArray(copied.toInt()) { value[it].toInt().toChar() }.concatToString()
}
