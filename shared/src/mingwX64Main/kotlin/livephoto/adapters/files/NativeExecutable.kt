package livephoto.adapters.files
internal actual val nativeWindows: Boolean = true
// Regular-file validation plus CreateProcessW/-version supplies the executable proof.
internal actual fun nativeExecutable(path: String): Boolean = true
