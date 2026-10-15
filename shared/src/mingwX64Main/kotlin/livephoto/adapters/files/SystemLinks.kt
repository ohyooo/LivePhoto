@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package livephoto.adapters.files

import okio.Path
import platform.windows.*

/** Okio's Windows stat follows links; conservatively reject every reparse point. */
internal actual fun systemPathIsLink(path: Path): Boolean {
    val attributes = GetFileAttributesW(path.toString())
    return attributes != INVALID_FILE_ATTRIBUTES.toUInt() && attributes and FILE_ATTRIBUTE_REPARSE_POINT.toUInt() != 0u
}
