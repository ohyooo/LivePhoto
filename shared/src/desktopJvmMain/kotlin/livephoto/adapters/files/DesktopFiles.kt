package livephoto.adapters.files

import okio.FileSystem
import okio.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

public actual val localFileSystem: FileSystem = FileSystem.SYSTEM
internal actual val nativeWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
internal actual fun nativeExecutable(path: String): Boolean = Files.isRegularFile(Paths.get(path), LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(Paths.get(path))
internal actual fun systemPathIsLink(path: Path): Boolean = Files.isSymbolicLink(path.toNioPath())
