package livephoto.adapters.files

import okio.FileSystem
import okio.Path

internal actual fun systemPathIsLink(path: Path): Boolean =
    FileSystem.SYSTEM.metadataOrNull(path)?.symlinkTarget != null
