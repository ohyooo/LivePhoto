@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package livephoto.adapters.files

import kotlinx.cinterop.*
import okio.*
import platform.windows.*
import livephoto.adapters.files.winio.lp_win32_read
import livephoto.adapters.files.winio.lp_win32_write

public actual val localFileSystem: FileSystem = object : ForwardingFileSystem(FileSystem.SYSTEM) {
    override fun openReadOnly(file: Path): FileHandle = open(file, false, false, true)
    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
        open(file, true, mustCreate, mustExist)
}

private fun ioFault(operation: String, write: Boolean, number: UInt = GetLastError()): Nothing {
    throw NativeFileIOException(operation, number, write)
}

private fun open(path: Path, write: Boolean, mustCreate: Boolean, mustExist: Boolean): FileHandle = memScoped {
    require(!mustCreate || !mustExist)
    val access = GENERIC_READ.toUInt() or (if (write) GENERIC_WRITE.toUInt() else 0u)
    val creation = when { mustCreate -> CREATE_NEW; mustExist -> OPEN_EXISTING; else -> OPEN_ALWAYS }
    val handle = CreateFileW(path.toString().wcstr.ptr, access.toUInt(),
        (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).toUInt(), null,
        creation.toUInt(), FILE_ATTRIBUTE_NORMAL.toUInt(), null)
    if (handle == null || handle == INVALID_HANDLE_VALUE) ioFault("open", write)
    LocalWindowsHandle(write, handle)
}

/** Synchronous handles, initialized OVERLAPPED offsets and explicit transferred-byte counters.
 * No async request outlives a pinned buffer/scope. No shared seek position escapes this adapter. */
private class LocalWindowsHandle(write: Boolean, private val handle: HANDLE) : FileHandle(write) {
    override fun protectedSize(): Long = memScoped {
        val size = alloc<LARGE_INTEGER>()
        if (GetFileSizeEx(handle, size.ptr) == 0) ioFault("size", false)
        size.QuadPart
    }

    private fun MemScope.offset(position: Long): CPointer<_OVERLAPPED> = alloc<_OVERLAPPED> {
        Internal = 0uL
        InternalHigh = 0uL
        Offset = position.toUInt()
        OffsetHigh = (position ushr 32).toUInt()
        hEvent = null
    }.ptr

    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
        if (byteCount == 0) return 0
        return memScoped {
            val count = alloc<UIntVar> { value = 0u }
            val overlapped = offset(fileOffset)
            val error = alloc<UIntVar>()
            val succeeded = array.usePinned {
                lp_win32_read(handle, it.addressOf(arrayOffset), byteCount.toUInt(), count.ptr, overlapped, error.ptr)
            }
            if (succeeded == 0) {
                if (error.value.toInt() == ERROR_HANDLE_EOF) return@memScoped -1
                ioFault("read", false, error.value)
            }
            if (count.value == 0u) -1 else count.value.toInt()
        }
    }

    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
        var completed = 0
        while (completed < byteCount) memScoped {
            val count = alloc<UIntVar> { value = 0u }
            val overlapped = offset(fileOffset + completed)
            val error = alloc<UIntVar>()
            val succeeded = array.usePinned {
                lp_win32_write(handle, it.addressOf(arrayOffset + completed), (byteCount - completed).toUInt(), count.ptr, overlapped, error.ptr)
            }
            if (succeeded == 0) ioFault("write", true, error.value)
            if (count.value == 0u || count.value > (byteCount - completed).toUInt()) {
                throw NativeFileIOException("write-progress", 0u, true)
            }
            completed += count.value.toInt()
        }
    }

    override fun protectedResize(size: Long) = memScoped {
        val distance = alloc<LARGE_INTEGER> { QuadPart = size }
        if (SetFilePointerEx(handle, distance.readValue(), null, FILE_BEGIN.toUInt()) == 0) ioFault("seek", true)
        if (SetEndOfFile(handle) == 0) ioFault("resize", true)
    }

    override fun protectedFlush() {
        if (FlushFileBuffers(handle) == 0) ioFault("flush", true)
    }
    override fun protectedClose() {
        if (CloseHandle(handle) == 0) ioFault("close", false)
    }
}
