package livephoto.android

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal suspend fun readBounded(stream: InputStream, limit: Int, checkCancelled: suspend () -> Unit): ByteArray {
    require(limit >= 0)
    val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        checkCancelled()
        val count = stream.read(buffer)
        if (count == -1) break
        check(count > 0) { "Input stream made no progress" }
        if (count > limit - total) throw InputBudgetExceededException()
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}
