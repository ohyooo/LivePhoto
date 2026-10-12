package livephoto.adapters.files

import okio.Path
import java.nio.file.Files

internal actual fun systemPathIsLink(path: Path): Boolean =
    Files.isSymbolicLink(java.nio.file.Path.of(path.toString()))
