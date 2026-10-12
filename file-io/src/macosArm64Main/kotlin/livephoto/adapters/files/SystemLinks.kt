package livephoto.adapters.files

import okio.FileSystem
import okio.Path
import okio.SYSTEM

internal actual fun systemPathIsLink(path: Path): Boolean =
    FileSystem.SYSTEM.metadataOrNull(path)?.symlinkTarget != null
