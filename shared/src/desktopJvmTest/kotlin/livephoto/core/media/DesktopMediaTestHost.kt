package livephoto.core.media

import kotlinx.coroutines.runBlocking
import kotlin.test.assertNotNull

internal actual fun runMediaTest(block: suspend (MediaTestHost) -> Unit): Unit = runBlocking {
    runFileMediaTest(assertNotNull(System.getenv("LIVEPHOTO_FFMPEG"), "CI must supply FFmpeg"), block)
}
