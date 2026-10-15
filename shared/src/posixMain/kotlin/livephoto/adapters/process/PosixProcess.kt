@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package livephoto.adapters.process

import kotlinx.cinterop.*
import platform.posix.*
import livephoto.adapters.process.posix.lp_spawn
import livephoto.adapters.process.posix.lp_exit
import livephoto.adapters.process.posix.lp_terminate_group

internal actual fun isAbsoluteExecutable(path: String): Boolean = path.startsWith("/")

internal actual fun startProcess(arguments: List<String>): ChildProcess? = memScoped {
    val descriptors = allocArray<IntVar>(2)
    if (pipe(descriptors) != 0) return null
    val input = descriptors[0]
    val output = descriptors[1]
    var transferred = false
    try {
        if (input <= 2 || output <= 2) return null
        // Only the read end is nonblocking: a writer must not lose pipe data.
        if (fcntl(input, F_SETFD, FD_CLOEXEC) != 0 || fcntl(output, F_SETFD, FD_CLOEXEC) != 0 ||
            fcntl(input, F_SETFL, O_NONBLOCK) != 0) return null
        val argv = allocArray<CPointerVar<ByteVar>>(arguments.size + 1)
        arguments.forEachIndexed { index, value -> argv[index] = value.cstr.ptr }
        argv[arguments.size] = null
        val pid = lp_spawn(arguments.first(), argv, input, output)
        if (pid <= 0) return null
        transferred = true
        PosixChild(pid, input)
    } finally {
        close(output)
        if (!transferred) close(input)
    }
}

private class PosixChild(private val pid: pid_t, private val pipe: Int) : ChildProcess {
    override var failed: Boolean = false
        private set
    private var result: Int? = null
    private var terminated = false
    override fun read(buffer: ByteArray): Int = buffer.usePinned {
        val count = platform.posix.read(pipe, it.addressOf(0), buffer.size.convert()).toInt()
        when {
            count > 0 -> count
            count == 0 -> -1
            errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR -> 0
            else -> -2
        }
    }
    override fun exitCode(): Int? {
        if (result != null || failed) return result
        memScoped {
            val status = alloc<IntVar>()
            // Retain the zombie until tree termination: its PID cannot be reused
            // for an unrelated process group between exit observation and kill.
            val waited = lp_exit(pid, status.ptr)
            if (waited == 1) result = status.value
            else if (waited < 0 && errno != EINTR) failed = true
        }
        return result
    }
    override fun terminateTree(): Boolean {
        if (terminated) return true
        // The group was allocated atomically by posix_spawn, not a post-start race.
        val success = lp_terminate_group(pid) == 0
        if (success) terminated = true
        return success
    }
    override fun close(): Boolean {
        val closed = platform.posix.close(pipe) == 0
        var waited: pid_t
        do { waited = waitpid(pid, null, WNOHANG) } while (waited < 0 && errno == EINTR)
        return closed && waited == pid
    }
}
