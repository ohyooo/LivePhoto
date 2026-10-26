@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package livephoto.core.media

import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlin.test.assertNotNull

internal actual fun runMediaTest(block: suspend (MediaTestHost) -> Unit): Unit = runBlocking {
    runFileMediaTest(assertNotNull(platform.posix.getenv("LIVEPHOTO_FFMPEG")?.toKString(), "CI must supply FFmpeg"), block)
}
