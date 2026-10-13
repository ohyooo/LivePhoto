@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package livephoto.adapters.process

import kotlinx.cinterop.*
import platform.posix.memset
import platform.windows.*
import livephoto.adapters.process.winio.lp_win32_read
import livephoto.adapters.process.winio.lp_win32_peek

internal actual fun isAbsoluteExecutable(path: String): Boolean =
    '"' !in path && path.endsWith(".exe", ignoreCase = true) &&
        (path.startsWith("\\\\") || path.length >= 3 && path[0].isLetter() && path[1] == ':' && path[2] in "/\\")

internal actual fun startProcess(arguments: List<String>): ChildProcess? = memScoped {
    val command = windowsCommandLine(arguments)
    if (command.length >= 32767) return null
    val security = alloc<SECURITY_ATTRIBUTES>().apply {
        nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt()
        lpSecurityDescriptor = null
        bInheritHandle = 1
    }
    val read = alloc<HANDLEVar>().apply { value = null }
    val write = alloc<HANDLEVar>().apply { value = null }
    var input: HANDLE? = null
    var job: HANDLE? = null
    val process = alloc<PROCESS_INFORMATION>()
    memset(process.ptr, 0, sizeOf<PROCESS_INFORMATION>().convert())
    var spawned = false
    var transferred = false
    var attributesReady = false
    var attributeMemory: CPointer<ByteVar>? = null
    try {
        if (CreatePipe(read.ptr, write.ptr, security.ptr, 0u) == 0 ||
            SetHandleInformation(read.value, HANDLE_FLAG_INHERIT.toUInt(), 0u) == 0) return null
        input = CreateFileW("NUL".wcstr.ptr, GENERIC_READ, FILE_SHARE_READ.toUInt() or FILE_SHARE_WRITE.toUInt(),
            security.ptr, OPEN_EXISTING.toUInt(), FILE_ATTRIBUTE_NORMAL.toUInt(), null)
        if (input == INVALID_HANDLE_VALUE) { input = null; return null }
        job = CreateJobObjectW(null, null) ?: return null
        val limits = alloc<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>()
        memset(limits.ptr, 0, sizeOf<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>().convert())
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE.toUInt()
        if (SetInformationJobObject(job, JobObjectExtendedLimitInformation, limits.ptr,
                sizeOf<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>().toUInt()) == 0) return null
        val startup = alloc<STARTUPINFOEXW>()
        memset(startup.ptr, 0, sizeOf<STARTUPINFOEXW>().convert())
        startup.StartupInfo.cb = sizeOf<STARTUPINFOEXW>().toUInt()
        startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES.toUInt()
        startup.StartupInfo.hStdInput = input
        startup.StartupInfo.hStdOutput = write.value
        startup.StartupInfo.hStdError = write.value
        val bytes = alloc<SIZE_TVar>().apply { value = 0uL }
        InitializeProcThreadAttributeList(null, 1u, 0u, bytes.ptr)
        if (bytes.value == 0uL || bytes.value > 65_536uL) return null
        attributeMemory = allocArray<ByteVar>(bytes.value.toInt())
        startup.lpAttributeList = attributeMemory.reinterpret()
        if (InitializeProcThreadAttributeList(startup.lpAttributeList, 1u, 0u, bytes.ptr) == 0) return null
        attributesReady = true
        val inherited = allocArray<HANDLEVar>(2)
        inherited[0] = input
        inherited[1] = write.value
        if (UpdateProcThreadAttribute(startup.lpAttributeList, 0u, PROC_THREAD_ATTRIBUTE_HANDLE_LIST.toULong(),
                inherited, (sizeOf<HANDLEVar>() * 2).toULong(), null, null) == 0) return null
        // Mutable UTF-16 command buffer; exact application name removes ambiguous path parsing.
        val mutableCommand = allocArray<UShortVar>(command.length + 1)
        command.forEachIndexed { index, value -> mutableCommand[index] = value.code.toUShort() }
        mutableCommand[command.length] = 0u
        if (CreateProcessW(arguments.first().wcstr.ptr, mutableCommand, null, null, 1,
                (CREATE_SUSPENDED or CREATE_NO_WINDOW or EXTENDED_STARTUPINFO_PRESENT).toUInt(),
                null, null, startup.ptr.reinterpret(), process.ptr) == 0) return null
        spawned = true
        // Assign before any application code runs. Never fall back to an uncontained child.
        if (AssignProcessToJobObject(job, process.hProcess) == 0 || ResumeThread(process.hThread) == UInt.MAX_VALUE) return null
        CloseHandle(process.hThread)
        process.hThread = null
        transferred = true
        WindowsChild(process.hProcess!!, job!!, read.value!!)
    } finally {
        if (attributesReady) DeleteProcThreadAttributeList(attributeMemory?.reinterpret())
        if (write.value != null) CloseHandle(write.value)
        if (input != null) CloseHandle(input)
        if (!transferred) {
            if (spawned) {
                TerminateProcess(process.hProcess, 1u)
                WaitForSingleObject(process.hProcess, 2000u)
                CloseHandle(process.hThread)
                CloseHandle(process.hProcess)
            }
            if (read.value != null) CloseHandle(read.value)
            if (job != null) CloseHandle(job)
        }
    }
}

private class WindowsChild(private val process: HANDLE, private val job: HANDLE, private val pipe: HANDLE) : ChildProcess {
    override var failed: Boolean = false
        private set
    private var terminated = false
    override fun read(buffer: ByteArray): Int = memScoped {
        val available = alloc<DWORDVar>()
        val error = alloc<UIntVar>()
        if (lp_win32_peek(pipe, available.ptr, error.ptr) == 0)
            return if (error.value == ERROR_BROKEN_PIPE.toUInt()) -1 else -2
        if (available.value == 0u) return 0
        val count = alloc<DWORDVar>()
        val success = buffer.usePinned {
            lp_win32_read(pipe, it.addressOf(0), minOf(available.value, buffer.size.toUInt()), count.ptr, null, error.ptr)
        }
        if (success == 0) return if (error.value == ERROR_BROKEN_PIPE.toUInt()) -1 else -2
        if (count.value == 0u) -2 else count.value.toInt()
    }
    override fun exitCode(): Int? = memScoped {
        when (WaitForSingleObject(process, 0u)) {
            WAIT_TIMEOUT.toUInt() -> null
            WAIT_OBJECT_0.toUInt() -> {
                val code = alloc<DWORDVar>()
                if (GetExitCodeProcess(process, code.ptr) == 0) { failed = true; null } else code.value.toInt()
            }
            else -> { failed = true; null }
        }
    }
    override fun terminateTree(): Boolean {
        if (terminated) return true
        val success = TerminateJobObject(job, 1u) != 0
        if (success) terminated = true
        return success
    }
    override fun close(): Boolean {
        val pipeClosed = CloseHandle(pipe) != 0
        val jobClosed = CloseHandle(job) != 0
        val processClosed = CloseHandle(process) != 0
        return pipeClosed && jobClosed && processClosed
    }
}
