package livephoto.adapters.files
import okio.FileSystem

/** Local provider used by these adapters. Native Windows supplies initialized synchronous
 * Win32 random-access handles; directory/metadata operations still use Okio. Serialized use. */
public expect val localFileSystem: FileSystem

internal fun isSystemProvider(fileSystem: FileSystem): Boolean =
    fileSystem === localFileSystem || fileSystem === FileSystem.SYSTEM
