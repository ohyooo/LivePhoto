@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package livephoto.adapters.process
import kotlinx.cinterop.toKString
import platform.posix.getenv
internal actual fun fixtureExecutable(): String =
    requireNotNull(getenv("LIVEPHOTO_PROCESS_FIXTURE")?.toKString()) { "Missing native process fixture" }
